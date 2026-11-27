package ba.lumen.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Liste sa sajta (Firebase Hosting + Realtime Database).
 *
 * Na sajtu se vlasnik prijavi Google nalogom i dodaje liste. TV se jednom poveze kodom
 * (ili QR kodom) i onda samo cita liste iz baze, nista se ne kuca na TV-u.
 *
 * Baza:
 *   pairing/{KOD}              = {tv, t, name}             TV upise kod, sajt doda claim
 *   pairing/{KOD}/claim        = {owner, name}             vlasnik koji je upisao kod
 *   owners/{uid}/lists/{id}    = {type: login|link, name, url, t}
 *   owners/{uid}/devices/{tv}  = {name, t}                 povezani televizori (samo oni smiju citati)
 *   owners/{uid}/subs          = {key, user, pass}         OpenSubtitles
 *
 * TV koristi anonimnu Firebase prijavu (REST, bez dodatnih biblioteka). Sve ostaje u
 * kesu na TV-u, pa radi i kad internet ili Firebase privremeno ne rade.
 */
public final class Cloud {

    // adrese (testovi ih mijenjaju)
    static String authBase = "https://identitytoolkit.googleapis.com/v1";
    static String tokenBase = "https://securetoken.googleapis.com/v1";
    static String siteOverride = null;

    static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LEN = 6;
    /** Kod vazi 15 minuta (isto pise u pravilima baze). */
    public static final long CODE_TTL_MS = 15 * 60 * 1000;

    public static final int SYNC_OK = 0, SYNC_CHANGED = 1, SYNC_UNPAIRED = 2, SYNC_OFFLINE = 3, SYNC_NOT_PAIRED = 4;

    /** Odgovor sa greskom 401/403: nema dozvole (TV odspojen ili prijava istekla). */
    static final class Denied extends Exception {
        Denied(String m) { super(m); }
    }

    public interface Callback {
        void onSync(int result);
    }

    public static final class Claim {
        public final String owner, name;
        Claim(String owner, String name) { this.owner = owner; this.name = name; }
    }

    private static Cloud instance;
    private final Context app;
    private final SharedPreferences prefs;
    private final String project;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();

    public static synchronized Cloud get(Context c) {
        if (instance == null) instance = new Cloud(c.getApplicationContext());
        return instance;
    }

    /** Za testove. */
    static synchronized Cloud fresh(Context c) {
        instance = new Cloud(c.getApplicationContext());
        return instance;
    }

    private Cloud(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("lumen_cloud", Context.MODE_PRIVATE);
        String p = app.getString(R.string.firebase_project);
        p = p == null ? "" : p.trim();
        // nije upisan ID projekta (stoji primjer iz firebase.xml)
        this.project = p.startsWith("LUMEN_") ? "" : p;
    }

    // ------------------------------------------------------------------ stanje

    /** U aplikaciju je upisan Firebase projekat (res/values/firebase.xml). */
    public boolean configured() { return siteOverride != null || !project.isEmpty(); }

    public String siteUrl() { return siteOverride != null ? siteOverride : "https://" + project + ".web.app"; }

    /** Adresa sajta za prikaz na TV-u, bez "https://". */
    public String siteLabel() { return siteUrl().replaceFirst("^https?://", ""); }

    public boolean paired() { return prefs.getString("owner", null) != null; }

    /** Google nalog sa kojim je TV povezan (za prikaz). */
    public String ownerName() { return prefs.getString("owner_name", ""); }

    String owner() { return prefs.getString("owner", null); }

    String uid() { return prefs.getString("uid", null); }

    // ------------------------------------------------------------------ podesavanje i prijava

    /** apiKey i adresa baze sa sajta (/__/firebase/init.json, Firebase Hosting ga sam pravi). */
    private synchronized void ensureConfig() throws Exception {
        if (prefs.getString("api_key", null) != null && prefs.getString("db_url", null) != null) return;
        if (!configured()) throw new Exception("Sajt za liste još nije podešen u aplikaciji.");
        Resp r;
        try {
            r = http("GET", siteUrl() + "/__/firebase/init.json", null, null);
        } catch (Exception e) {
            throw new Exception("Nema interneta ili sajt ne radi.");
        }
        if (r.code != 200) throw new Exception("Sajt " + siteLabel() + " još nije objavljen.");
        Map<String, Object> cfg = MiniJson.obj(MiniJson.parse(new StringReader(r.body)));
        String key = MiniJson.str(cfg, "apiKey");
        String db = MiniJson.str(cfg, "databaseURL");
        String pid = MiniJson.str(cfg, "projectId");
        if (key == null) throw new Exception("Sajt ne daje Firebase podešavanja.");
        if (db == null) db = "https://" + (pid != null ? pid : project) + "-default-rtdb.firebaseio.com";
        while (db.endsWith("/")) db = db.substring(0, db.length() - 1);
        prefs.edit().putString("api_key", key).putString("db_url", db).apply();
    }

    /** Vazeci token anonimne prijave ovog TV-a (obnavlja se sam). */
    synchronized String token() throws Exception {
        ensureConfig();
        String id = prefs.getString("id_token", null);
        long exp = prefs.getLong("token_exp", 0);
        if (id != null && System.currentTimeMillis() < exp - 60000) return id;
        String key = prefs.getString("api_key", null);
        String refresh = prefs.getString("refresh", null);
        if (refresh != null) {
            Resp r = http("POST", tokenBase + "/token?key=" + enc(key),
                    "grant_type=refresh_token&refresh_token=" + enc(refresh), "application/x-www-form-urlencoded");
            if (r.code == 200) {
                Map<String, Object> m = MiniJson.obj(MiniJson.parse(new StringReader(r.body)));
                saveToken(MiniJson.str(m, "user_id"), MiniJson.str(m, "id_token"),
                        MiniJson.str(m, "refresh_token"), MiniJson.str(m, "expires_in"));
                return prefs.getString("id_token", null);
            }
            if (r.code >= 500) throw new Exception("Firebase trenutno ne odgovara.");
            // prijava ovog TV-a vise ne postoji: nova prijava, TV treba ponovo povezati
            prefs.edit().remove("uid").remove("refresh").remove("id_token").remove("owner").remove("owner_name").apply();
        }
        Resp r = http("POST", authBase + "/accounts:signUp?key=" + enc(key), "{\"returnSecureToken\":true}", "application/json");
        if (r.code != 200) {
            if (r.body != null && (r.body.contains("ADMIN_ONLY_OPERATION") || r.body.contains("OPERATION_NOT_ALLOWED"))) {
                throw new Exception("U Firebase konzoli uključi anonimnu prijavu (Authentication → Anonymous).");
            }
            throw new Exception("Firebase prijava nije uspjela (HTTP " + r.code + ").");
        }
        Map<String, Object> m = MiniJson.obj(MiniJson.parse(new StringReader(r.body)));
        saveToken(MiniJson.str(m, "localId"), MiniJson.str(m, "idToken"),
                MiniJson.str(m, "refreshToken"), MiniJson.str(m, "expiresIn"));
        return prefs.getString("id_token", null);
    }

    private void saveToken(String uid, String id, String refresh, String expiresIn) throws Exception {
        if (uid == null || id == null) throw new Exception("Firebase prijava nije uspjela.");
        long secs = Vod.parseLong(expiresIn);
        if (secs <= 0) secs = 3600;
        prefs.edit()
                .putString("uid", uid)
                .putString("id_token", id)
                .putString("refresh", refresh)
                .putLong("token_exp", System.currentTimeMillis() + secs * 1000)
                .apply();
    }

    /** Zahtjev prema bazi; 401/403 -> Denied. */
    String db(String method, String path, String json) throws Exception {
        String t = token();
        Resp r = http(method, prefs.getString("db_url", "") + "/" + path + ".json?auth=" + enc(t), json, "application/json");
        if (r.code == 401 || r.code == 403) throw new Denied("Nema dozvole");
        if (r.code < 200 || r.code >= 300) throw new Exception("Baza ne odgovara (HTTP " + r.code + ").");
        return r.body;
    }

    // ------------------------------------------------------------------ povezivanje

    /** Napravi novi kod za povezivanje i upisi ga u bazu (blokira). */
    public String newCode() throws Exception {
        String uid0 = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            token();
            uid0 = uid();
            String code = randomCode();
            JSONObject o = new JSONObject();
            o.put("tv", uid0);
            o.put("t", new JSONObject().put(".sv", "timestamp"));
            o.put("name", deviceName());
            try {
                db("PUT", "pairing/" + code, o.toString());
                prefs.edit().putString("pending_code", code).apply();
                return code;
            } catch (Denied d) {
                // kod je vec zauzet (pravila ne daju prepisati tudji) -> drugi kod
            }
        }
        throw new Exception("Ne mogu napraviti kod za povezivanje.");
    }

    String randomCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LEN; i++) sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        return sb.toString();
    }

    static String deviceName() {
        String m = Build.MODEL;
        return m == null || m.trim().isEmpty() ? "Android TV" : m.trim();
    }

    /** Da li je neko na sajtu upisao ovaj kod (null = jos ne). */
    public Claim checkCode(String code) throws Exception {
        String body = db("GET", "pairing/" + code, null);
        Map<String, Object> m = MiniJson.obj(MiniJson.parse(new StringReader(body)));
        Map<String, Object> c = m == null ? null : MiniJson.obj(m.get("claim"));
        String owner = MiniJson.str(c, "owner");
        if (owner == null) return null;
        String name = MiniJson.str(c, "name");
        return new Claim(owner, name == null ? "" : name);
    }

    /**
     * Kod je upisan na sajtu: zapamti vlasnika, obrisi kod, posalji na sajt liste koje su
     * ranije bile upisane na TV-u (da se ne izgube) i preuzmi sve liste.
     */
    public int finishPairing(String code, Claim c) throws Exception {
        prefs.edit().putString("owner", c.owner).putString("owner_name", c.name).remove("pending_code").apply();
        try { db("DELETE", "pairing/" + code, null); } catch (Exception ignored) { }
        uploadLocal();
        return syncNow();
    }

    /** Obrisi kod koji nije iskoristen (izlazak sa ekrana za povezivanje). */
    public void cancelCode(final String code) {
        if (code == null) return;
        prefs.edit().remove("pending_code").apply();
        io.execute(new Runnable() {
            @Override public void run() {
                try { db("DELETE", "pairing/" + code, null); } catch (Exception ignored) { }
            }
        });
    }

    /** Liste upisane na TV-u prije sajta idu na sajt (samo jednom). */
    private void uploadLocal() {
        if (prefs.getBoolean("migrated", false)) return;
        Repo repo = Repo.get(app);
        List<Repo.Source> logins = repo.logins(), links = repo.links();
        boolean ok = true;
        for (int pass = 0; pass < 2; pass++) {
            for (Repo.Source s : pass == 0 ? logins : links) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", pass == 0 ? "login" : "link");
                    o.put("name", s.name.length() > 100 ? s.name.substring(0, 100) : s.name);
                    o.put("url", s.url);
                    o.put("t", new JSONObject().put(".sv", "timestamp"));
                    db("POST", "owners/" + owner() + "/lists", o.toString());
                } catch (Exception e) {
                    ok = false;
                }
            }
        }
        if (ok) prefs.edit().putBoolean("migrated", true).apply();
    }

    /** Odspoji ovaj TV (brise se i sa sajta) i ukloni liste sa TV-a. */
    public void unpair() {
        final String o = owner(), u = uid();
        clearPairing();
        io.execute(new Runnable() {
            @Override public void run() {
                if (o == null || u == null) return;
                try { db("DELETE", "owners/" + o + "/devices/" + u, null); } catch (Exception ignored) { }
            }
        });
    }

    private void clearPairing() {
        prefs.edit().remove("owner").remove("owner_name").apply();
        Repo.get(app).replaceSources(new ArrayList<Repo.Source>(), new ArrayList<Repo.Source>());
        new Subs(app).replaceFromCloud(null, null, null);
    }

    // ------------------------------------------------------------------ preuzimanje lista

    public void syncAsync(final Callback cb) {
        io.execute(new Runnable() {
            @Override public void run() {
                final int r = syncNow();
                if (cb != null) main.post(new Runnable() { @Override public void run() { cb.onSync(r); } });
            }
        });
    }

    /** Preuzmi liste i podesavanje titlova sa sajta (blokira). */
    public synchronized int syncNow() {
        String o = owner();
        if (o == null) return SYNC_NOT_PAIRED;
        String body;
        try {
            body = db("GET", "owners/" + o, null);
        } catch (Denied d) {
            // vlasnik je na sajtu uklonio ovaj TV
            clearPairing();
            return SYNC_UNPAIRED;
        } catch (Exception e) {
            return SYNC_OFFLINE;
        }
        if (!o.equals(owner())) return SYNC_OK;   // odspojen u medjuvremenu
        Map<String, Object> root;
        try {
            root = MiniJson.obj(MiniJson.parse(new StringReader(body)));
        } catch (Exception e) {
            return SYNC_OFFLINE;
        }
        List<Repo.Source> logins = new ArrayList<>(), links = new ArrayList<>();
        parseLists(root == null ? null : MiniJson.obj(root.get("lists")), logins, links);
        Map<String, Object> subs = root == null ? null : MiniJson.obj(root.get("subs"));
        boolean changed = Repo.get(app).replaceSources(logins, links);
        changed |= new Subs(app).replaceFromCloud(MiniJson.str(subs, "key"), MiniJson.str(subs, "user"), MiniJson.str(subs, "pass"));
        prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply();
        return changed ? SYNC_CHANGED : SYNC_OK;
    }

    /** Liste redom kojim su dodane na sajtu; prijava bez korisnika/lozinke se tretira kao link. */
    static void parseLists(Map<String, Object> lists, List<Repo.Source> logins, List<Repo.Source> links) {
        if (lists == null) return;
        List<Object[]> all = new ArrayList<>();
        for (Map.Entry<String, Object> e : lists.entrySet()) {
            Map<String, Object> m = MiniJson.obj(e.getValue());
            String url = MiniJson.str(m, "url");
            if (url == null || !url.matches("(?i)^https?://.+")) continue;
            String name = MiniJson.str(m, "name");
            if (name == null) name = url.replaceFirst("^https?://", "");
            all.add(new Object[]{Vod.parseLong(MiniJson.str(m, "t")), e.getKey(), name, url, MiniJson.str(m, "type")});
        }
        java.util.Collections.sort(all, new java.util.Comparator<Object[]>() {
            @Override public int compare(Object[] a, Object[] b) {
                int c = Long.compare((Long) a[0], (Long) b[0]);
                return c != 0 ? c : ((String) a[1]).compareTo((String) b[1]);
            }
        });
        for (Object[] x : all) {
            String id = "c" + ((String) x[1]).replaceAll("[^A-Za-z0-9_-]", "");
            Repo.Source s = new Repo.Source(id, (String) x[2], (String) x[3]);
            boolean login = "login".equals(x[4]) && Xtream.from(s.url) != null;
            (login ? logins : links).add(s);
        }
    }

    // ------------------------------------------------------------------ HTTP

    static final class Resp {
        final int code;
        final String body;
        Resp(int code, String body) { this.code = code; this.body = body; }
    }

    static Resp http(String method, String url, String body, String type) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", type == null ? "application/json" : type);
                try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                try {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
                    text = new String(bo.toByteArray(), "UTF-8");
                } finally {
                    in.close();
                }
            }
            return new Resp(code, text);
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }
}
