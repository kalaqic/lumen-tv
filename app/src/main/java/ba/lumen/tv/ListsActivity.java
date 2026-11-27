package ba.lumen.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URL;
import java.util.List;

/**
 * Pregled lista na TV-u. Liste se dodaju i brisu na sajtu, ovdje se samo vide.
 * Dok je ekran otvoren, liste se osvjezavaju svakih par sekundi, pa se nova lista
 * dodana na telefonu pojavi odmah.
 */
public class ListsActivity extends Activity {

    private static final int REQ_PAIR = 1;
    private static final long POLL_MS = 5000;

    private Repo repo;
    private Cloud cloud;
    private ViewGroup loginsBox, linksBox;
    private TextView pairTitle, pairSub, intro, subsState;
    private boolean unpairArmed;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!cloud.paired()) return;
            cloud.syncAsync(new Cloud.Callback() {
                @Override public void onSync(int r) {
                    if (isFinishing()) return;
                    if (r == Cloud.SYNC_CHANGED || r == Cloud.SYNC_UNPAIRED) fill();
                    if (r == Cloud.SYNC_UNPAIRED) {
                        Toast.makeText(ListsActivity.this, "Ovaj TV je odspojen sa sajta.", Toast.LENGTH_LONG).show();
                    }
                    handler.removeCallbacks(poll);
                    handler.postDelayed(poll, POLL_MS);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_lists);
        Ui.applyFonts(getWindow().getDecorView());
        repo = Repo.get(this);
        cloud = Cloud.get(this);
        loginsBox = findViewById(R.id.logins_box);
        linksBox = findViewById(R.id.links_box);
        pairTitle = findViewById(R.id.pair_title);
        pairSub = findViewById(R.id.pair_sub);
        intro = findViewById(R.id.lists_intro);
        subsState = findViewById(R.id.subs_state);

        final View pairRow = findViewById(R.id.row_pair);
        pairRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!cloud.paired()) {
                    startActivityForResult(new Intent(ListsActivity.this, PairActivity.class), REQ_PAIR);
                } else if (unpairArmed) {
                    unpairArmed = false;
                    cloud.unpair();
                    fill();
                    Toast.makeText(ListsActivity.this, "TV je odspojen. Liste sa sajta su uklonjene sa TV-a.", Toast.LENGTH_LONG).show();
                } else {
                    unpairArmed = true;
                    pairSub.setText("Pritisni OK još jednom da odspojiš ovaj TV");
                    pairSub.setTextColor(getResources().getColor(R.color.live));
                }
            }
        });
        pairRow.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && unpairArmed) { unpairArmed = false; fill(); }
            }
        });
        findViewById(R.id.btn_done).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        pairRow.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        fill();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PAIR && resultCode == RESULT_OK) {
            fill();
            if (repo.logins().isEmpty() && repo.links().isEmpty()) {
                Toast.makeText(this, "Povezano. Dodaj listu na sajtu i pojaviće se ovdje.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void fill() {
        boolean paired = cloud.paired();
        String site = cloud.siteLabel();
        intro.setText(paired
                ? "Liste dodaješ i brišeš na sajtu " + site + ". Prijave idu u „Moje prijave“, linkovi bez prijave u „Besplatni kanali“."
                : "Liste se dodaju na sajtu " + site + " (telefon ili računar). Prvo poveži ovaj TV sa sajtom.");
        pairSub.setTextColor(getResources().getColor(R.color.text_3));
        if (paired) {
            pairTitle.setText("Povezano" + (cloud.ownerName().isEmpty() ? "" : " · " + cloud.ownerName()));
            pairSub.setText("OK dvaput da odspojiš ovaj TV");
        } else {
            pairTitle.setText("Poveži TV sa sajtom");
            pairSub.setText("QR kod ili kod od 6 slova, bez kucanja na TV-u");
        }
        fillBox(loginsBox, repo.logins(), true, paired);
        fillBox(linksBox, repo.links(), false, paired);
        Subs subs = new Subs(this);
        subsState.setText(subs.configured()
                ? "Podešeni (OpenSubtitles)."
                : "Nisu podešeni. OpenSubtitles ključ upiši na sajtu, u dijelu „Titlovi“.");
    }

    private void fillBox(ViewGroup box, List<Repo.Source> sources, boolean logins, boolean paired) {
        box.removeAllViews();
        if (sources.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(logins
                    ? (paired ? "Nema prijava. Dodaj prijavu na sajtu." : "Nema prijava.")
                    : "Nema dodatnih linkova. Balkanske liste su uvijek uključene.");
            empty.setTextColor(getResources().getColor(R.color.text_3));
            empty.setTextSize(14);
            empty.setPadding(0, Ui.dp(this, 10), 0, 0);
            box.addView(empty);
            return;
        }
        LayoutInflater inf = LayoutInflater.from(this);
        for (Repo.Source s : sources) {
            View row = inf.inflate(R.layout.item_list_source, box, false);
            Ui.applyFonts(row);
            ((TextView) row.findViewById(R.id.title)).setText(s.name);
            ((TextView) row.findViewById(R.id.sub)).setText(describe(s, logins));
            box.addView(row);   // fokusira se samo radi listanja, OK ne radi nista
        }
    }

    /** "server.com · korisnik" za prijavu, "server.com/putanja" za link (lozinka se nikad ne prikazuje). */
    static String describe(Repo.Source s, boolean login) {
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
}
