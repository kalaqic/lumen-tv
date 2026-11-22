package ba.lumen.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URL;
import java.util.List;

/**
 * Upravljanje listama: Xtream prijave (profil "Moje prijave") i obicni M3U linkovi
 * (profil "Besplatni kanali") su u dva odvojena odjeljka. Brisanje: OK dvaput na listi.
 */
public class ListsActivity extends Activity {

    private Repo repo;
    private ViewGroup loginsBox, linksBox;
    /** id liste koja ceka drugi OK za brisanje. */
    private String armedId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_lists);
        Ui.applyFonts(getWindow().getDecorView());
        repo = Repo.get(this);
        loginsBox = findViewById(R.id.logins_box);
        linksBox = findViewById(R.id.links_box);

        findViewById(R.id.add_login).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openForm(XtreamActivity.TYPE_LOGIN); }
        });
        findViewById(R.id.add_link).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openForm(XtreamActivity.TYPE_LINK); }
        });
        findViewById(R.id.btn_done).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { done(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        armedId = null;
        fill();
        View first = loginsBox.getChildCount() > 0 ? loginsBox.getChildAt(0) : findViewById(R.id.add_login);
        if (getCurrentFocus() == null) first.requestFocus();
    }

    private void openForm(String type) {
        Intent i = new Intent(this, XtreamActivity.class);
        i.putExtra(XtreamActivity.EXTRA_TYPE, type);
        startActivity(i);
    }

    private void fill() {
        fillBox(loginsBox, repo.logins(), true);
        fillBox(linksBox, repo.links(), false);
    }

    private void fillBox(ViewGroup box, List<Repo.Source> sources, boolean logins) {
        box.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        if (sources.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(logins ? "Još nema prijava." : "Nema dodatnih linkova. Balkanske liste su uvijek uključene.");
            empty.setTextColor(getResources().getColor(R.color.text_3));
            empty.setTextSize(14);
            empty.setPadding(0, Ui.dp(this, 10), 0, 0);
            box.addView(empty);
            return;
        }
        for (final Repo.Source s : sources) {
            final View row = inf.inflate(R.layout.item_list_source, box, false);
            Ui.applyFonts(row);
            final TextView title = row.findViewById(R.id.title);
            final TextView sub = row.findViewById(R.id.sub);
            title.setText(s.name);
            final String info = describe(s, logins);
            sub.setText(info);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (s.id.equals(armedId)) {
                        repo.removeSource(s.id);
                        armedId = null;
                        Toast.makeText(ListsActivity.this, "Uklonjeno: " + s.name, Toast.LENGTH_SHORT).show();
                        int idx = ((ViewGroup) row.getParent()).indexOfChild(row);
                        fill();
                        focusAfterRemove(logins, idx);
                    } else {
                        armedId = s.id;
                        sub.setText("Pritisni OK još jednom da ukloniš ovu listu");
                        sub.setTextColor(getResources().getColor(R.color.live));
                    }
                }
            });
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus && s.id.equals(armedId)) {
                        armedId = null;
                        sub.setText(info);
                        sub.setTextColor(getResources().getColor(R.color.text_3));
                    }
                }
            });
            box.addView(row);
        }
    }

    private void focusAfterRemove(boolean logins, int idx) {
        ViewGroup box = logins ? loginsBox : linksBox;
        View target = null;
        if (box.getChildCount() > 0 && box.getChildAt(0).isFocusable()) {
            target = box.getChildAt(Math.min(idx, box.getChildCount() - 1));
        }
        if (target == null) target = findViewById(logins ? R.id.add_login : R.id.add_link);
        target.requestFocus();
    }

    /** "server.com · korisnik" za prijavu, "server.com" za link (lozinka se nikad ne prikazuje). */
    private static String describe(Repo.Source s, boolean login) {
        if (login) {
            Xtream x = Xtream.from(s.url);
            if (x != null) return x.host.replaceFirst("^https?://", "") + "  ·  " + x.user;
        }
        try {
            URL u = new URL(s.url);
            return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getPath();
        } catch (Exception e) {
            return "";
        }
    }

    /** Nazad na izbor profila (glavni ekran sam primijeti promjenu i ponovo ucita). */
    private void done() {
        finish();
    }
}
