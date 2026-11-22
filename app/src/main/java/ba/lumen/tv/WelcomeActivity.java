package ba.lumen.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/**
 * Prvi ekran pri prvom pokretanju: izbor izmedu Xtream prijave i besplatnih kanala.
 * Nista se ne upisuje u kod; korisnik bira i (eventualno) unosi svoje podatke.
 */
public class WelcomeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_welcome);
        Ui.applyFonts(getWindow().getDecorView());

        findViewById(R.id.opt_xtream).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(WelcomeActivity.this, XtreamActivity.class));
                finish();
            }
        });
        findViewById(R.id.opt_free).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // besplatne liste po drzavama su vec dodane; samo nastavi
                finish();
            }
        });
        findViewById(R.id.opt_skip).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        findViewById(R.id.opt_xtream).requestFocus();
    }
}
