package ba.lumen.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Pocetni ekran i izbor profila:
 *  - Besplatni kanali: javne balkanske liste + vlastiti M3U linkovi bez prijave
 *  - Moje prijave: sve Xtream prijave zajedno
 *  - Upravljaj listama: dodavanje i brisanje
 *
 * Pri pokretanju aplikacije: ako je profil vec izabran, odmah otvara glavni ekran.
 * Iz glavnog ekrana (EXTRA_SWITCH): uvijek prikazuje izbor.
 */
public class WelcomeActivity extends Activity {

    /** Extra: otvoreno iz glavnog ekrana radi promjene profila. */
    public static final String EXTRA_SWITCH = "switch";

    private static final int REQ_PAIR = 1;

    private Repo repo;
    private Cloud cloud;
    private boolean switching;
    private boolean firstFocus = true;
    private boolean ui;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = Repo.get(this);
        cloud = Cloud.get(this);
        switching = getIntent().getBooleanExtra(EXTRA_SWITCH, false);

        if (!switching && repo.mode() != null) {
            openMain();
            return;
        }

        Ui.init(this);
        setContentView(R.layout.activity_welcome);
        ui = true;
        Ui.applyFonts(getWindow().getDecorView());

        findViewById(R.id.opt_free).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                repo.setFreeMode();
                openMain();
            }
        });
        findViewById(R.id.opt_logins).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (repo.logins().isEmpty()) {
                    // jos nema prijave: povezi TV sa sajtom, ili pokazi gdje se dodaju liste
                    if (!cloud.paired()) {
                        startActivityForResult(new Intent(WelcomeActivity.this, PairActivity.class), REQ_PAIR);
                    } else {
                        Toast.makeText(WelcomeActivity.this, "Još nema prijava. Dodaj prijavu na sajtu " + cloud.siteLabel() + ".",
                                Toast.LENGTH_LONG).show();
                        startActivity(new Intent(WelcomeActivity.this, ListsActivity.class));
                    }
                } else {
                    repo.setLoginsMode();
                    openMain();
                }
            }
        });
        findViewById(R.id.opt_manage).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(WelcomeActivity.this, ListsActivity.class));
            }
        });
        View back = findViewById(R.id.opt_back);
        back.setVisibility(switching ? View.VISIBLE : View.GONE);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ui) return; // preusmjereno na glavni ekran
        refreshLabels();
        // liste su mozda promijenjene na sajtu
        cloud.syncAsync(new Cloud.Callback() {
            @Override public void onSync(int r) {
                if (!isFinishing() && (r == Cloud.SYNC_CHANGED || r == Cloud.SYNC_UNPAIRED)) refreshLabels();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PAIR || resultCode != RESULT_OK) return;
        if (!repo.logins().isEmpty()) {
            // prijave sa sajta su stigle: odmah na kanale
            repo.setLoginsMode();
            openMain();
        } else {
            Toast.makeText(this, "Povezano. Dodaj prijavu na sajtu i pojaviće se na TV-u.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, ListsActivity.class));
        }
    }

    /** Opisi profila (broj prijava/linkova se mijenja nakon "Upravljaj listama"). */
    private void refreshLabels() {
        List<Repo.Source> logins = repo.logins();
        List<Repo.Source> links = repo.links();

        TextView freeSub = findViewById(R.id.opt_free_sub);
        freeSub.setText(links.isEmpty()
                ? "Javni streamovi · BiH, Srbija, Hrvatska i ostale"
                : "Balkanske liste + " + plural(links.size(), "tvoj link", "tvoja linka", "tvojih linkova"));

        TextView loginsSub = findViewById(R.id.opt_logins_sub);
        if (logins.isEmpty()) {
            loginsSub.setText(cloud.paired()
                    ? "Dodaj prijavu na sajtu " + cloud.siteLabel()
                    : "Poveži TV sa sajtom i dodaj prijavu (bez kucanja na TV-u)");
        } else if (logins.size() == 1) {
            loginsSub.setText(logins.get(0).name);
        } else {
            loginsSub.setText(plural(logins.size(), "prijava", "prijave", "prijava") + " zajedno · " + names(logins));
        }

        TextView current = findViewById(R.id.current_source);
        String mode = repo.mode();
        if (switching && mode != null) {
            current.setVisibility(View.VISIBLE);
            current.setText("Trenutno: " + (repo.isXtream() ? "Moje prijave" : "Besplatni kanali"));
        } else {
            current.setVisibility(View.GONE);
        }

        if (firstFocus) {
            firstFocus = false;
            findViewById(repo.isXtream() ? R.id.opt_logins : R.id.opt_free).requestFocus();
        }
    }

    private static String names(List<Repo.Source> l) {
        StringBuilder sb = new StringBuilder();
        for (Repo.Source s : l) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.name);
        }
        return sb.toString();
    }

    /** 1 prijava, 2 prijave, 5 prijava (bosanski oblici). */
    static String plural(int n, String one, String few, String many) {
        int m10 = n % 10, m100 = n % 100;
        String w = (m10 == 1 && m100 != 11) ? one : (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) ? few : many;
        return n + " " + w;
    }

    /** Otvori glavni ekran ispocetka (da se ucita izabrani profil) i zatvori sve iznad. */
    private void openMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
