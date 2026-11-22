package ba.lumen.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * Pocetni ekran i izbor izvora: Besplatni kanali ili Xtream prijava.
 *
 * - Pri pokretanju aplikacije: ako je izvor vec izabran, odmah otvara glavni ekran.
 * - Iz bocnog menija ("Promijeni izvor"): uvijek prikazuje izbor.
 */
public class WelcomeActivity extends Activity {

    /** Extra: otvoreno iz glavnog ekrana radi promjene izvora. */
    public static final String EXTRA_SWITCH = "switch";

    private Repo repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = Repo.get(this);
        boolean switching = getIntent().getBooleanExtra(EXTRA_SWITCH, false);

        if (!switching && repo.mode() != null) {
            openMain();
            return;
        }

        Ui.init(this);
        setContentView(R.layout.activity_welcome);
        Ui.applyFonts(getWindow().getDecorView());

        TextView current = findViewById(R.id.current_source);
        View saved = findViewById(R.id.opt_xtream_saved);
        TextView savedLabel = findViewById(R.id.opt_xtream_saved_label);
        TextView newLabel = findViewById(R.id.opt_xtream_label);
        View back = findViewById(R.id.opt_back);

        String mode = repo.mode();
        if (mode == null) {
            current.setVisibility(View.GONE);
        } else {
            current.setVisibility(View.VISIBLE);
            current.setText("Trenutno: " + (repo.isXtream() ? repo.xtreamName() : "Besplatni kanali"));
        }

        if (repo.hasXtream()) {
            saved.setVisibility(View.VISIBLE);
            savedLabel.setText("Moja prijava · " + repo.xtreamName());
            newLabel.setText("Nova Xtream prijava");
        } else {
            saved.setVisibility(View.GONE);
            newLabel.setText("Xtream prijava");
        }
        back.setVisibility(switching ? View.VISIBLE : View.GONE);

        findViewById(R.id.opt_free).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                repo.setFreeMode();
                openMain();
            }
        });
        saved.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                repo.useSavedXtream();
                openMain();
            }
        });
        findViewById(R.id.opt_xtream).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Welcome ostaje ispod; "Nazad" iz prijave vraca na ovaj izbor
                startActivity(new Intent(WelcomeActivity.this, XtreamActivity.class));
            }
        });
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        // fokus na trenutno izabrani izvor (ili na Besplatno kad se bira prvi put)
        if (repo.isXtream() && repo.hasXtream()) saved.requestFocus();
        else findViewById(R.id.opt_free).requestFocus();
    }

    /** Otvori glavni ekran ispocetka (da se ucita izabrani izvor) i zatvori sve iznad. */
    private void openMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
