package ba.lumen.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Titlovi sa OpenSubtitles.com (REST API).
 *
 * Treba besplatan API kljuc (opensubtitles.com -> API consumers). Bez prijave dozvoljeno je
 * 5 preuzimanja dnevno; sa besplatnim nalogom vise. Kljuc i nalog se cuvaju SAMO na ovom uredjaju.
 */
public final class Subs {

    static String api = "https://api.opensubtitles.com/api/v1";
    static final String UA = "LumenTV v1.0";
    /** Jezici redom kojim se nude (bosanski, hrvatski, srpski, engleski). */
    static final String[] LANGS = {"bs", "hr", "sr", "en"};

    public static final class Result {
        public String fileId, language, release;
        public int downloads;
        public boolean hearingImpaired;

        public String label() {
            return languageName(language) + (hearingImpaired ? " (za gluhe)" : "");
        }
    }

    private final SharedPreferences prefs;

    public Subs(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("lumen_subs", Context.MODE_PRIVATE);
    }

    public boolean configured() { return apiKey() != null; }

    String apiKey() {
        String k = prefs.getString("api_key", null);
        return k == null || k.trim().isEmpty() ? null : k.trim();
    }

    public String user() { return prefs.getString("user", ""); }

    public void save(String apiKey, String user, String pass) {
        prefs.edit()
                .putString("api_key", apiKey.trim())
                .putString("user", user.trim())
                .putString("pass", pass)
                .remove("token").remove("token_time")
                .apply();
    }

    public void clear() { prefs.edit().clear().apply(); }

    /** Podesavanje sa sajta (null kljuc = obrisano). Vraca true ako se promijenilo. */
    public boolean replaceFromCloud(String key, String user, String pass) {
        String k = key == null ? "" : key.trim(), u = user == null ? "" : user.trim(), p = pass == null ? "" : pass;
        if (k.equals(prefs.getString("api_key", "")) && u.equals(prefs.getString("user", ""))
                && p.equals(prefs.getString("pass", ""))) return false;
        if (k.isEmpty()) {
            prefs.edit().remove("api_key").remove("user").remove("pass").remove("token").remove("token_time").apply();
        } else {
            save(k, u, p);
        }
        return true;
    }

    // ------------------------------------------------------------------ pretraga

    /**
     * @param title   naziv filma ili serije
     * @param year    godina (moze null)
     * @param tmdbId  TMDB id filma ili serije (moze null) - najtacnija pretraga
     * @param season  za epizode (0 = film)
     */
    public List<Result> search(String title, String year, String tmdbId, int season, int episode) throws Exception {
        Map<String, String> q = new TreeMap<>();   // parametri abecedno (API to trazi)
        q.put("languages", "bs,en,hr,sr");
        q.put("order_by", "download_count");
        if (season > 0) {
            q.put("type", "episode");
            q.put("season_number", String.valueOf(season));
            q.put("episode_number", String.valueOf(episode));
            if (tmdbId != null) q.put("parent_tmdb_id", tmdbId);
            else q.put("query", title.toLowerCase(java.util.Locale.ROOT));
        } else {
            q.put("type", "movie");
            if (tmdbId != null) q.put("tmdb_id", tmdbId);
            else {
                q.put("query", title.toLowerCase(java.util.Locale.ROOT));
                if (year != null) q.put("year", year);
            }
        }
        StringBuilder url = new StringBuilder(api).append("/subtitles?");
        boolean first = true;
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (!first) url.append('&');
            first = false;
            url.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8").replace("+", "%20").replace("%2C", ","));
        }
        String body = request("GET", url.toString(), null, false);
        return parseResults(body);
    }

    static List<Result> parseResults(String body) throws Exception {
        List<Result> out = new ArrayList<>();
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(new StringReader(body)));
        List<Object> data = MiniJson.arr(root == null ? null : root.get("data"));
        if (data == null) return out;
        for (Object o : data) {
            Map<String, Object> a = MiniJson.obj(MiniJson.obj(o) == null ? null : MiniJson.obj(o).get("attributes"));
            if (a == null) continue;
            List<Object> files = MiniJson.arr(a.get("files"));
            if (files == null || files.isEmpty()) continue;
            Result r = new Result();
            r.fileId = MiniJson.str(MiniJson.obj(files.get(0)), "file_id");
            if (r.fileId == null) continue;
            r.language = MiniJson.str(a, "language");
            r.release = MiniJson.str(a, "release");
            r.downloads = (int) Vod.parseLong(MiniJson.str(a, "download_count"));
            r.hearingImpaired = "true".equals(MiniJson.str(a, "hearing_impaired"));
            out.add(r);
        }
        // bosanski, hrvatski, srpski, engleski; unutar jezika najpopularniji
        Collections.sort(out, new Comparator<Result>() {
            @Override public int compare(Result x, Result y) {
                int lx = langRank(x.language), ly = langRank(y.language);
                if (lx != ly) return lx - ly;
                return y.downloads - x.downloads;
            }
        });
        return out;
    }

    static int langRank(String l) {
        if (l == null) return 99;
        String n = l.toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("sr")) n = "sr";
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i].equals(n)) return i;
        return 50;
    }

    public static String languageName(String l) {
        if (l == null) return "Titl";
        String n = l.toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("bs")) return "Bosanski";
        if (n.startsWith("hr")) return "Hrvatski";
        if (n.startsWith("sr")) return "Srpski";
        if (n.startsWith("en")) return "Engleski";
        if (n.startsWith("sl")) return "Slovenački";
        if (n.startsWith("mk")) return "Makedonski";
        if (n.startsWith("de")) return "Njemački";
        return l;
    }

    // ------------------------------------------------------------------ preuzimanje

    /** Skini titl kao .srt (UTF-8) u dati folder. */
    public File download(Result r, File dir) throws Exception {
        File f = new File(dir, "sub_" + r.fileId.replaceAll("[^0-9A-Za-z]", "") + ".srt");
        if (f.exists() && f.length() > 0) return f;
        boolean auth = ensureLogin();
        JSONObject req = new JSONObject();
        req.put("file_id", Long.parseLong(r.fileId));
        req.put("sub_format", "srt");
        String body = request("POST", api + "/download", req.toString(), auth);
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(new StringReader(body)));
        String link = MiniJson.str(root, "link");
        if (link == null) {
            String msg = MiniJson.str(root, "message");
            throw new Exception(msg != null ? msg : "Titl se ne može preuzeti.");
        }
        byte[] raw = fetch(link);
        String text = decode(raw);
        dir.mkdirs();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        }
        return f;
    }

    /** Prijava (ako je nalog upisan); token vazi 24 h. Vraca true ako postoji token. */
    private boolean ensureLogin() throws Exception {
        String user = prefs.getString("user", ""), pass = prefs.getString("pass", "");
        if (user.isEmpty() || pass.isEmpty()) return false;
        String token = prefs.getString("token", null);
        long t = prefs.getLong("token_time", 0);
        if (token != null && System.currentTimeMillis() - t < 23L * 3600 * 1000) return true;
        JSONObject req = new JSONObject();
        req.put("username", user);
        req.put("password", pass);
        String body = request("POST", api + "/login", req.toString(), false);
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(new StringReader(body)));
        token = MiniJson.str(root, "token");
        if (token == null) throw new Exception("Prijava na OpenSubtitles nije uspjela.");
        prefs.edit().putString("token", token).putLong("token_time", System.currentTimeMillis()).apply();
        return true;
    }

    private String request(String method, String url, String json, boolean auth) throws Exception {
        String key = apiKey();
        if (key == null) throw new Exception("Nije upisan OpenSubtitles ključ.");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Api-Key", key);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            if (auth) c.setRequestProperty("Authorization", "Bearer " + prefs.getString("token", ""));
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes("UTF-8")); }
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403) {
                prefs.edit().remove("token").apply();
                throw new Exception("OpenSubtitles ne prihvata ključ ili prijavu. Provjeri ih u podešavanju titlova.");
            }
            if (code == 406 || code == 429) {
                throw new Exception("Dnevni limit preuzimanja titlova je potrošen. Sa besplatnim nalogom limit je veći.");
            }
            if (code < 200 || code >= 300) throw new Exception("OpenSubtitles greška (HTTP " + code + ").");
            try (InputStream in = c.getInputStream()) {
                return new String(readAll(in), "UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("Titl se ne može preuzeti (HTTP " + code + ").");
            try (InputStream in = c.getInputStream()) { return readAll(in); }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /**
     * Titlovi sa Balkana su cesto u starom kodiranju (Windows-1250 latinica ili Windows-1251
     * cirilica), a plejer ocekuje UTF-8. Prepoznaj i pretvori, da se vide č, ć, š, ž, đ.
     */
    static String decode(byte[] raw) {
        int start = 0;
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) start = 3;
        ByteBuffer bb = ByteBuffer.wrap(raw, start, raw.length - start);
        try {
            return Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bb).toString();
        } catch (CharacterCodingException e) {
            // nije UTF-8: cirilica ako je vecina slova u gornjem dijelu (0xC0-0xFF)
            int high = 0, letters = 0;
            for (int i = start; i < raw.length; i++) {
                int b = raw[i] & 0xFF;
                if (b >= 0xC0) { high++; letters++; }
                else if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')) letters++;
            }
            String cs = letters > 0 && high * 100 / letters > 30 ? "windows-1251" : "windows-1250";
            try {
                return new String(raw, start, raw.length - start, cs);
            } catch (Exception ex) {
                return new String(raw, start, raw.length - start, Charset.forName("ISO-8859-1"));
            }
        }
    }
}
