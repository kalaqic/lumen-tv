package ba.lumen.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URL;
import java.net.URLEncoder;

/**
 * Dodavanje liste:
 *  - TYPE_LOGIN: Xtream prijava (server, korisnik, lozinka) -> profil "Moje prijave".
 *    U polje za server moze se zalijepiti i cijeli get.php link: korisnik i lozinka se sami popune.
 *  - TYPE_LINK: obicni M3U link bez prijave -> profil "Besplatni kanali".
 *    Ako je zalijepljeni link ipak get.php sa korisnikom i lozinkom, ide u "Moje prijave".
 *
 * Podaci se cuvaju SAMO na ovom uredaju, nikad u kodu aplikacije.
 */
public class XtreamActivity extends Activity {

    public static final String EXTRA_TYPE = "type";
    public static final String TYPE_LOGIN = "login";
    public static final String TYPE_LINK = "link";

    private Repo repo;
    private SharedPreferences prefs;
    private EditText inName, inHost, inUser, inPass;
    private boolean linkMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_xtream);
        Ui.applyFonts(getWindow().getDecorView());
        repo = Repo.get(this);
        prefs = getSharedPreferences("lumen", Context.MODE_PRIVATE);
        linkMode = TYPE_LINK.equals(getIntent().getStringExtra(EXTRA_TYPE));

        inName = findViewById(R.id.in_name);
        inHost = findViewById(R.id.in_host);
        inUser = findViewById(R.id.in_user);
        inPass = findViewById(R.id.in_pass);
        TextView title = findViewById(R.id.form_title);
        TextView sub = findViewById(R.id.form_sub);
        TextView btn = findViewById(R.id.btn_login);

        if (linkMode) {
            title.setText("Dodaj M3U link");
            sub.setText("Link plejliste koja ne traži prijavu. Kanali idu u „Besplatni kanali“.");
            inHost.setHint("M3U link  (http://…)");
            inUser.setVisibility(View.GONE);
            inPass.setVisibility(View.GONE);
            btn.setText("Dodaj link");
            inHost.requestFocus();
        } else {
            title.setText(repo.logins().isEmpty() ? "Xtream prijava" : "Nova prijava");
            sub.setText("Svi kanali iz svih prijava su zajedno u „Moje prijave“. Može i cijeli get.php link u polje za server. Podaci ostaju samo na ovom TV-u.");
            String host = prefs.getString("xtream_host", "");
            inHost.setText(host);
            btn.setText("Prijavi se");
            if (!host.isEmpty()) inUser.requestFocus();
            else inHost.requestFocus();
        }

        // upisan/zalijepljen cijeli get.php link -> kad se izadje iz polja, razdvoji ga
        // na server, korisnika i lozinku (ne dok se kuca, da se lozinka ne odsijece)
        inHost.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && splitPastedLink()) inName.requestFocus();
            }
        });

        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
    }

    /** Ako je u polju za server cijeli get.php link, rasporedi ga u polja. */
    private boolean splitPastedLink() {
        if (linkMode) return false;
        Xtream x = Xtream.from(cleanPasted(inHost.getText().toString()));
        if (x == null) return false;
        inHost.setText(x.host);
        inUser.setText(x.user);
        inPass.setText(x.pass);
        return true;
    }

    private void save() {
        splitPastedLink();
        String host = inHost.getText().toString().trim();
        String name = inName.getText().toString().trim();

        if (linkMode) {
            if (host.isEmpty()) { toast("Upiši link plejliste."); return; }
            host = cleanPasted(host);
            try { new URL(host); } catch (Exception e) { toast("Link nije ispravan."); return; }
            Xtream x = Xtream.from(host);
            if (x != null) {
                // link sa korisnikom i lozinkom je prijava, ne obicni link
                repo.addLogin(name.isEmpty() ? defaultName(x.host) : name, loginUrl(x.host, x.user, x.pass));
                repo.setLoginsMode();
                toast("Ovo je link sa prijavom pa je dodan u „Moje prijave“.");
            } else {
                repo.addLink(name.isEmpty() ? defaultName(host) : name, host);
                repo.setFreeMode();
                toast("Link dodan u „Besplatni kanali“.");
            }
            finishToMain();
            return;
        }

        String user = inUser.getText().toString().trim();
        String pass = inPass.getText().toString().trim();
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            toast("Popuni server, korisničko ime i lozinku.");
            return;
        }
        if (!host.startsWith("http://") && !host.startsWith("https://")) host = "http://" + host;
        // samo adresa servera (bez /get.php i slicnog)
        try {
            URL u = new URL(host);
            host = u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            if (u.getHost() == null || u.getHost().isEmpty()) throw new Exception();
        } catch (Exception e) {
            toast("Adresa servera nije ispravna.");
            return;
        }
        String url;
        try {
            url = loginUrl(host, user, pass);
        } catch (Exception e) {
            toast("Adresa servera nije ispravna.");
            return;
        }
        prefs.edit().putString("xtream_host", host).apply();
        repo.addLogin(name.isEmpty() ? defaultName(host) + " · " + user : name, url);
        repo.setLoginsMode();
        toast("Prijava sačuvana na ovom TV-u.");
        finishToMain();
    }

    /**
     * Ocisti zalijepljen link: razmaci, "http://" ako fali, i link umotan u
     * Facebook/Messenger preusmjerenje (l.facebook.com/l.php?u=...).
     */
    static String cleanPasted(String raw) {
        String s = raw == null ? "" : raw.trim().replace(" ", "");
        if (s.isEmpty()) return s;
        if (!s.matches("(?i)^https?://.*")) s = "http://" + s;
        try {
            URL u = new URL(s);
            String h = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
            if ((h.endsWith("facebook.com") || h.endsWith("messenger.com")) && u.getQuery() != null) {
                for (String kv : u.getQuery().split("&")) {
                    if (kv.startsWith("u=")) {
                        String inner = java.net.URLDecoder.decode(kv.substring(2), "UTF-8").trim();
                        // Facebook doda svoj parametar na kraj (&fbclid=...)
                        inner = inner.replaceAll("[?&]fbclid=[^&]*", "");
                        return cleanPasted(inner);
                    }
                }
            }
        } catch (Exception ignored) { }
        return s;
    }

    static String loginUrl(String host, String user, String pass) {
        try {
            return host + "/get.php?username=" + URLEncoder.encode(user, "UTF-8")
                    + "&password=" + URLEncoder.encode(pass, "UTF-8")
                    + "&type=m3u_plus&output=ts";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** "http://server.com:8080/x.m3u" -> "server.com" */
    static String defaultName(String url) {
        String h = url.replaceFirst("^https?://", "");
        int cut = h.length();
        for (char ch : new char[]{':', '/', '?'}) {
            int i = h.indexOf(ch);
            if (i > 0 && i < cut) cut = i;
        }
        h = h.substring(0, cut);
        return h.isEmpty() ? "Lista" : h;
    }

    private void finishToMain() {
        hideKeyboard();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }
}
