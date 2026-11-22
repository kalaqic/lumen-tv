package ba.lumen.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Podesavanje titlova sa interneta (OpenSubtitles.com): API kljuc i, opcionalno, besplatan nalog
 * (sa nalogom je dozvoljeno vise preuzimanja dnevno). Cuva se samo na ovom uredjaju.
 * Koristi isti izgled kao forma za prijavu.
 */
public class SubsSetupActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_xtream);
        Ui.applyFonts(getWindow().getDecorView());
        final Subs subs = new Subs(this);

        ((TextView) findViewById(R.id.form_title)).setText("Titlovi sa interneta");
        ((TextView) findViewById(R.id.form_sub)).setText(
                "Titlovi se traže na OpenSubtitles.com. Napravi besplatan nalog na opensubtitles.com, "
                + "pa u „API consumers“ napravi ključ i upiši ga ovdje. Bez naloga: 5 titlova dnevno, "
                + "sa nalogom više. Podaci ostaju samo na ovom TV-u.");
        final EditText key = findViewById(R.id.in_host);
        final EditText user = findViewById(R.id.in_user);
        final EditText pass = findViewById(R.id.in_pass);
        findViewById(R.id.in_name).setVisibility(View.GONE);
        key.setHint("API ključ");
        user.setHint("Korisničko ime (opcionalno)");
        pass.setHint("Lozinka (opcionalno)");
        user.setText(subs.user());
        TextView btn = findViewById(R.id.btn_login);
        btn.setText("Sačuvaj");
        key.requestFocus();

        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String k = key.getText().toString().trim();
                if (k.length() < 10) {
                    Toast.makeText(SubsSetupActivity.this, "Upiši API ključ sa opensubtitles.com.", Toast.LENGTH_LONG).show();
                    return;
                }
                String u = user.getText().toString().trim(), p = pass.getText().toString();
                if (u.isEmpty() != p.isEmpty()) {
                    Toast.makeText(SubsSetupActivity.this, "Upiši i korisničko ime i lozinku (ili ostavi oboje prazno).", Toast.LENGTH_LONG).show();
                    return;
                }
                subs.save(k, u, p);
                Toast.makeText(SubsSetupActivity.this, "Sačuvano. Sad možeš tražiti titlove.", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });
    }
}
