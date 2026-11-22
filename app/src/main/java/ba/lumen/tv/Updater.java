package ba.lumen.tv;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provjerava da li na GitHubu postoji novija verzija (release "build-N"),
 * skida APK i pokrece instalaciju.
 */
public final class Updater {

    private static boolean checkedThisSession = false;
    private static final Pattern TAG = Pattern.compile("/tag/build-(\\d+)");

    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String repo;

    private Dialog dialog;
    private TextView dTitle, dText, dInstall, dLater;
    private ProgressBar dProgress;
    private File pendingApk;          // skinut, ceka dozvolu za instalaciju
    private volatile boolean busy;

    public Updater(Activity a) {
        act = a;
        repo = a.getString(R.string.update_repo);
    }

    /** Jednom po pokretanju aplikacije, tiho. */
    public void checkOnStart() {
        if (checkedThisSession) return;
        checkedThisSession = true;
        main.postDelayed(new Runnable() {
            @Override public void run() { check(false); }
        }, 2500);
    }

    /** Pozvati iz onResume – nastavlja instalaciju nakon sto korisnik dozvoli "nepoznate izvore". */
    public void onResume() {
        if (pendingApk != null && canInstall()) {
            File apk = pendingApk;
            pendingApk = null;
            install(apk);
        }
    }

    public void check(final boolean manual) {
        if (busy) return;
        busy = true;
        if (manual) toast("Provjeravam ažuriranja…");
        new Thread(new Runnable() {
            @Override public void run() {
                final int latest = fetchLatestBuild();
                main.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (act.isFinishing()) return;
                        int current = currentVersionCode();
                        if (latest <= 0) {
                            if (manual) toast("Ne mogu provjeriti ažuriranja. Provjeri internet konekciju.");
                        } else if (latest > current) {
                            showOffer(latest, current);
                        } else if (manual) {
                            toast("Imaš najnoviju verziju (1.0." + current + ")");
                        }
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ mreza

    private int fetchLatestBuild() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://github.com/" + repo + "/releases/latest").openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "LumenTV-Updater");
            c.getResponseCode();
            String loc = c.getHeaderField("Location");
            if (loc == null) return -1;
            Matcher m = TAG.matcher(loc);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private int currentVersionCode() {
        try {
            //noinspection deprecation
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private void download(final int build) {
        busy = true;
        setProgress(0, "Preuzimam verziju 1.0." + build + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    File dir = updatesDir();
                    File[] old = dir.listFiles();
                    if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    final File out = new File(dir, "LumenTV-" + build + ".apk");

                    String url = "https://github.com/" + repo + "/releases/download/build-" + build + "/LumenTV.apk";
                    for (int hop = 0; hop < 6; hop++) {
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        c.setRequestProperty("User-Agent", "LumenTV-Updater");
                        int code = c.getResponseCode();
                        if (code >= 300 && code < 400) {
                            String loc = c.getHeaderField("Location");
                            c.disconnect();
                            c = null;
                            if (loc == null) throw new Exception("redirect");
                            url = new URL(new URL(url), loc).toString();
                            continue;
                        }
                        if (code != 200) throw new Exception("HTTP " + code);
                        break;
                    }
                    if (c == null) throw new Exception("redirect");

                    final int total = c.getContentLength();
                    try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[64 * 1024];
                        long done = 0;
                        int lastPct = -1, n;
                        while ((n = in.read(buf)) != -1) {
                            os.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                final int pct = (int) (done * 100 / total);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    main.post(new Runnable() {
                                        @Override public void run() { setProgress(pct, "Preuzimam… " + pct + "%"); }
                                    });
                                }
                            }
                        }
                    }
                    //noinspection ResultOfMethodCallIgnored
                    out.setReadable(true, false);
                    main.post(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            install(out);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            showError("Preuzimanje nije uspjelo. Provjeri internet i pokušaj ponovo.");
                        }
                    });
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    private File updatesDir() {
        // Android 7+ koristi FileProvider; starije verzije trebaju fajl koji instaler moze procitati
        File base = Build.VERSION.SDK_INT >= 24 ? act.getCacheDir() : act.getExternalCacheDir();
        if (base == null) base = act.getCacheDir();
        File dir = new File(base, "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    // ------------------------------------------------------------------ instalacija

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || act.getPackageManager().canRequestPackageInstalls();
    }

    private void install(File apk) {
        if (!canInstall()) {
            pendingApk = apk;
            showMessage("Još jedan korak",
                    "TV traži dozvolu da Lumen TV instalira ažuriranja. Na sljedećem ekranu uključi dozvolu za Lumen TV, pa se vrati nazad tipkom Nazad.",
                    "Otvori postavke");
            dInstall.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openUnknownSourcesSettings(); }
            });
            return;
        }
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = FileProvider.getUriForFile(act, act.getPackageName() + ".files", apk);
            } else {
                uri = Uri.fromFile(apk);
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            dismiss();
        } catch (Exception e) {
            showError("Ne mogu pokrenuti instalaciju na ovom uređaju. Ažuriraj preko Downloadera.");
        }
    }

    private void openUnknownSourcesSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception e2) {
                showError("Otvori Postavke → Aplikacije → Sigurnost → Nepoznati izvori i uključi Lumen TV.");
            }
        }
    }

    // ------------------------------------------------------------------ dijalog

    private void ensureDialog() {
        if (dialog != null) return;
        // kontejner zadrzava sirinu iz layouta (520dp)
        FrameLayout holder = new FrameLayout(act);
        View v = LayoutInflater.from(act).inflate(R.layout.dialog_update, holder, true);
        Ui.applyFonts(v);
        dTitle = v.findViewById(R.id.upd_title);
        dText = v.findViewById(R.id.upd_text);
        dProgress = v.findViewById(R.id.upd_progress);
        dInstall = v.findViewById(R.id.upd_install);
        dLater = v.findViewById(R.id.upd_later);
        dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(holder);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setDimAmount(0.7f);
        }
        dLater.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { dismiss(); }
        });
    }

    private void showOffer(final int latest, int current) {
        ensureDialog();
        dialog.setCancelable(true);
        dTitle.setText("Nova verzija je dostupna");
        dText.setText("Lumen TV 1.0." + latest + " je spremna. Trenutno imaš verziju 1.0." + current
                + ".\nPlejliste i favoriti ostaju sačuvani.");
        dProgress.setVisibility(View.GONE);
        dInstall.setVisibility(View.VISIBLE);
        dInstall.setText("Instaliraj");
        dLater.setVisibility(View.VISIBLE);
        dLater.setText("Kasnije");
        dInstall.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { download(latest); }
        });
        show();
        dInstall.requestFocus();
    }

    private void setProgress(int pct, String text) {
        ensureDialog();
        dTitle.setText("Ažuriranje");
        dText.setText(text);
        dProgress.setVisibility(View.VISIBLE);
        dProgress.setProgress(pct);
        dInstall.setVisibility(View.GONE);
        dLater.setVisibility(View.GONE);
        dialog.setCancelable(false);
        show();
    }

    private void showMessage(String title, String text, String button) {
        ensureDialog();
        dialog.setCancelable(true);
        dTitle.setText(title);
        dText.setText(text);
        dProgress.setVisibility(View.GONE);
        dInstall.setVisibility(View.VISIBLE);
        dInstall.setText(button);
        dLater.setVisibility(View.VISIBLE);
        dLater.setText("Odustani");
        show();
        dInstall.requestFocus();
    }

    private void showError(String text) {
        showMessage("Ups", text, "U redu");
        dLater.setVisibility(View.GONE);
        dInstall.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
    }

    private void show() {
        if (!dialog.isShowing() && !act.isFinishing()) dialog.show();
    }

    private void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }
}
