package ba.lumen.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Povezivanje TV-a sa sajtom: prikazuje kod i QR kod. Na telefonu se skenira QR (ili otvori
 * sajt i upise kod), prijavi Google nalogom i TV se sam poveze. Na TV-u se nista ne kuca.
 * Rezultat: RESULT_OK kad je povezan.
 */
public class PairActivity extends Activity {

    private static final long POLL_MS = 3000;

    private Cloud cloud;
    private TextView codeView, statusView, stepsView;
    private ImageView qrView;
    private View loading;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String code;
    private long codeTime;
    private boolean done, busy;

    private final Runnable poll = new Runnable() {
        @Override public void run() { check(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_pair);
        Ui.applyFonts(getWindow().getDecorView());
        cloud = Cloud.get(this);
        codeView = findViewById(R.id.pair_code);
        statusView = findViewById(R.id.pair_status);
        stepsView = findViewById(R.id.pair_steps);
        qrView = findViewById(R.id.pair_qr);
        loading = findViewById(R.id.pair_loading);
        stepsView.setText("1.  Skeniraj QR kod telefonom\n     ili otvori  " + cloud.siteLabel() + "\n"
                + "2.  Prijavi se Google nalogom\n"
                + "3.  Upiši ovaj kod u „Poveži TV“:");
        View back = findViewById(R.id.pair_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        back.requestFocus();
        if (!cloud.configured()) {
            statusView.setText("Sajt za liste još nije podešen u aplikaciji (firebase.xml).");
            loading.setVisibility(View.GONE);
            return;
        }
        newCode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (code != null && !done) {
            handler.removeCallbacks(poll);
            handler.post(poll);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (!done) cloud.cancelCode(code);
        io.shutdown();
    }

    private void newCode() {
        statusView.setText("Pravim kod…");
        loading.setVisibility(View.VISIBLE);
        final String old = code;
        code = null;
        io.execute(new Runnable() {
            @Override public void run() {
                if (old != null) {
                    try { cloud.db("DELETE", "pairing/" + old, null); } catch (Exception ignored) { }
                }
                String c = null, err = null;
                try {
                    c = cloud.newCode();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fc = c, fe = err;
                handler.post(new Runnable() {
                    @Override public void run() { showCode(fc, fe); }
                });
            }
        });
    }

    private void showCode(String c, String err) {
        if (isFinishing()) return;
        loading.setVisibility(View.GONE);
        if (c == null) {
            statusView.setText((err == null ? "Greška." : err) + "  Pokušavam ponovo…");
            handler.postDelayed(new Runnable() {
                @Override public void run() { if (!isFinishing()) newCode(); }
            }, 8000);
            return;
        }
        code = c;
        codeTime = System.currentTimeMillis();
        codeView.setText(c);
        qrView.setImageBitmap(qr(cloud.siteUrl() + "/?kod=" + c));
        statusView.setText("Čekam da upišeš kod na sajtu…");
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, POLL_MS);
    }

    private void check() {
        if (code == null || done || busy) return;
        if (System.currentTimeMillis() - codeTime > Cloud.CODE_TTL_MS - 30000) {
            // kod istice: novi kod
            newCode();
            return;
        }
        busy = true;
        final String c = code;
        io.execute(new Runnable() {
            @Override public void run() {
                Cloud.Claim claim = null;
                int sync = -1;
                try {
                    claim = cloud.checkCode(c);
                    if (claim != null) sync = cloud.finishPairing(c, claim);
                } catch (Exception ignored) {
                    // nema interneta trenutno: probaj opet za par sekundi
                }
                final Cloud.Claim fc = claim;
                handler.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (isFinishing()) return;
                        if (fc == null) {
                            handler.postDelayed(poll, POLL_MS);
                            return;
                        }
                        done = true;
                        statusView.setText("Povezano" + (fc.name.isEmpty() ? "" : " sa " + fc.name) + ".");
                        setResult(RESULT_OK);
                        handler.postDelayed(new Runnable() {
                            @Override public void run() { finish(); }
                        }, 1200);
                    }
                });
            }
        });
    }

    /** QR kod kao slika: crni kvadratici na bijelom, ostri (bez zamucivanja pri uvecanju). */
    static Bitmap qr(String text) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            int n = m.getWidth(), scale = Math.max(1, 496 / n), size = n * scale;
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    px[y * size + x] = m.get(x / scale, y / scale) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565);
        } catch (Exception e) {
            return null;
        }
    }
}
