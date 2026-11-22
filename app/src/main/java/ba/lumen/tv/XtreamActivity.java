package ba.lumen.tv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLEncoder;

/**
 * Prijava na Xtream Codes IPTV uslugu. Host, korisnicko ime i lozinka unose se ovdje
 * i cuvaju se SAMO na uredaju (u listi plejlisti), nikad u kodu aplikacije.
 */
public class XtreamActivity extends Activity {

    private Repo repo;
    private EditText inName, inHost, inUser, inPass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_xtream);
        Ui.applyFonts(getWindow().getDecorView());
        repo = Repo.get(this);

        inName = findViewById(R.id.in_name);
        inHost = findViewById(R.id.in_host);
        inUser = findViewById(R.id.in_user);
        inPass = findViewById(R.id.in_pass);

        // Prefill host/naziv koje si ranije unio NA OVOM TV-u (nije u kodu, cuva se lokalno)
        SharedPreferences sp = getSharedPreferences("lumen", Context.MODE_PRIVATE);
        inHost.setText(sp.getString("xtream_host", ""));
        inName.setText(sp.getString("xtream_name", ""));

        findViewById(R.id.btn_login).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { login(); }
        });
    }

    private void login() {
        String host = inHost.getText().toString().trim();
        String user = inUser.getText().toString().trim();
        String pass = inPass.getText().toString().trim();
        String name = inName.getText().toString().trim();

        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "Popuni host, korisničko ime i lozinku.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!host.startsWith("http://") && !host.startsWith("https://")) host = "http://" + host;
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);

        String url;
        try {
            url = host + "/get.php?username=" + URLEncoder.encode(user, "UTF-8")
                    + "&password=" + URLEncoder.encode(pass, "UTF-8")
                    + "&type=m3u_plus&output=ts";
        } catch (Exception e) {
            Toast.makeText(this, "Neispravan unos.", Toast.LENGTH_LONG).show();
            return;
        }

        if (name.isEmpty()) {
            String h = host.replaceFirst("^https?://", "");
            int colon = h.indexOf(':');
            name = "Xtream · " + (colon > 0 ? h.substring(0, colon) : h);
        }
        getSharedPreferences("lumen", Context.MODE_PRIVATE).edit()
                .putString("xtream_host", host).putString("xtream_name", name).apply();
        hideKeyboard();
        Repo.Playlist p = repo.addPlaylist(name, url);
        repo.setActive(p.id);
        Toast.makeText(this, "Prijava sačuvana na ovom TV-u.", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }
}
