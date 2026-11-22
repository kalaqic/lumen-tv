package ba.lumen.tv;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Xtream Codes: TV vodic (EPG) i arhiva (catch-up) za prijavljeni nalog.
 *
 *  - TV vodic za kanal:  /player_api.php?username=U&password=P&action=get_simple_data_table&stream_id=ID
 *  - Arhiva emisije:     /timeshift/U/P/{trajanje u min}/{YYYY-MM-DD:HH-MM}/ID.m3u8 (ili .ts)
 */
public final class Xtream {

    /** Jedna emisija iz TV vodica. Vremena su u milisekundama (UTC). */
    public static final class Program {
        public long start, stop;
        public String title = "", desc = "";
        /** Pocetak kako ga server pise ("2026-11-22 20:00:00", vrijeme servera) – treba za adresu arhive. */
        public String serverStart;
        public boolean archive;

        public boolean isNow(long t) { return t >= start && t < stop; }
        public boolean isPast(long t) { return stop <= t; }
        public boolean isFuture(long t) { return start > t; }
        public int minutes() { return (int) Math.max(1, Math.ceil((stop - start) / 60000.0)); }
    }

    public final String host;   // npr. http://server.com:2052
    public final String user, pass;

    private Xtream(String host, String user, String pass) {
        this.host = host;
        this.user = user;
        this.pass = pass;
    }

    /** Iz sacuvanog get.php linka; null ako link nije Xtream. */
    public static Xtream from(String getPhpUrl) {
        if (getPhpUrl == null || !getPhpUrl.contains("get.php")) return null;
        try {
            URL u = new URL(getPhpUrl);
            String host = u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            String user = null, pass = null;
            String q = u.getQuery();
            if (q == null) return null;
            for (String kv : q.split("&")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String k = kv.substring(0, eq);
                String v = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                if (k.equals("username")) user = v;
                else if (k.equals("password")) pass = v;
            }
            if (user == null || pass == null || user.isEmpty() || pass.isEmpty()) return null;
            return new Xtream(host, user, pass);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ID kanala iz linka kanala: ".../user/pass/12345", ".../live/user/pass/12345.ts" ili ".m3u8".
     * Null ako link nije u tom obliku.
     */
    public static String streamId(String channelUrl) {
        if (channelUrl == null) return null;
        String path = channelUrl;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) return null;
        String last = path.substring(slash + 1);
        int dot = last.indexOf('.');
        if (dot >= 0) last = last.substring(0, dot);
        if (last.isEmpty()) return null;
        for (int i = 0; i < last.length(); i++) if (!Character.isDigit(last.charAt(i))) return null;
        return last;
    }

    // ------------------------------------------------------------------ TV vodic

    private static final long CACHE_MS = 10L * 60 * 1000;
    private static final Map<String, Object[]> CACHE = new HashMap<>();   // key -> {vrijeme, lista}

    /** TV vodic iz kesa (ako je svjez), inace null. */
    public List<Program> cachedEpg(String streamId) {
        synchronized (CACHE) {
            Object[] v = CACHE.get(host + "|" + user + "|" + streamId);
            if (v == null || System.currentTimeMillis() - (Long) v[0] > CACHE_MS) return null;
            @SuppressWarnings("unchecked") List<Program> l = (List<Program>) v[1];
            return l;
        }
    }

    /** Skini TV vodic za kanal (blokira – zvati van glavne niti). */
    public List<Program> fetchEpg(String streamId) throws Exception {
        String url = host + "/player_api.php?username=" + enc(user) + "&password=" + enc(pass)
                + "&action=get_simple_data_table&stream_id=" + streamId;
        String body = httpGet(url);
        List<Program> out = parseEpg(body);
        synchronized (CACHE) {
            CACHE.put(host + "|" + user + "|" + streamId, new Object[]{System.currentTimeMillis(), out});
        }
        return out;
    }

    /** Parsira odgovor get_simple_data_table (ili get_short_epg). */
    static List<Program> parseEpg(String body) throws Exception {
        List<Program> out = new ArrayList<>();
        String t = body.trim();
        JSONArray arr;
        if (t.startsWith("[")) {
            arr = new JSONArray(t);
        } else {
            JSONObject o = new JSONObject(t);
            arr = o.optJSONArray("epg_listings");
            if (arr == null) return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            Program p = new Program();
            p.start = seconds(e.optString("start_timestamp", "")) * 1000L;
            p.stop = seconds(e.optString("stop_timestamp", "")) * 1000L;
            if (p.start <= 0 || p.stop <= p.start) continue;
            p.title = decodeMaybe(e.optString("title", "")).trim();
            p.desc = decodeMaybe(e.optString("description", "")).trim();
            if (p.title.isEmpty()) p.title = "Bez naziva";
            String s = e.optString("start", "");
            p.serverStart = s.length() >= 16 ? s : null;
            p.archive = "1".equals(e.optString("has_archive", "0"));
            out.add(p);
        }
        Collections.sort(out, new Comparator<Program>() {
            @Override public int compare(Program a, Program b) { return Long.compare(a.start, b.start); }
        });
        return out;
    }

    private static long seconds(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    /** Naslovi i opisi su obicno u base64; ako nisu, vrati tekst kakav jeste. */
    static String decodeMaybe(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = s.trim();
        if (t.length() % 4 != 0 || !t.matches("[A-Za-z0-9+/=\\s]+")) return s;
        try {
            String d = new String(Base64.decode(t, Base64.DEFAULT), "UTF-8");
            for (int i = 0; i < d.length(); i++) {
                char ch = d.charAt(i);
                if (ch == '�' || (ch < 32 && ch != '\n' && ch != '\r' && ch != '\t')) return s;
            }
            return d;
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------------ arhiva

    /**
     * Adrese arhive za emisiju, od trenutka offsetMin minuta nakon pocetka emisije.
     * Vraca [HLS (.m3u8), TS (.ts)] – plejer proba redom.
     */
    public String[] archiveUrls(Program p, String streamId, int offsetMin) {
        String start = archiveStart(p, offsetMin);
        int dur = Math.max(1, p.minutes() - offsetMin);
        String base = host + "/timeshift/" + encPath(user) + "/" + encPath(pass) + "/" + dur + "/" + start + "/" + streamId;
        return new String[]{base + ".m3u8", base + ".ts"};
    }

    /** "YYYY-MM-DD:HH-MM" u vremenu servera (iz polja "start"), pomjereno za offsetMin minuta. */
    static String archiveStart(Program p, int offsetMin) {
        SimpleDateFormat out = new SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US);
        try {
            if (p.serverStart != null) {
                // vrijeme servera bez zone: racunaj kao "UTC" da se nista ne pomjeri
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                in.setTimeZone(TimeZone.getTimeZone("UTC"));
                out.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = in.parse(p.serverStart.substring(0, 16));
                return out.format(new Date(d.getTime() + offsetMin * 60000L));
            }
        } catch (Exception ignored) { }
        // rezerva: lokalno vrijeme uredaja (vecina servera na Balkanu je u istoj zoni)
        out.setTimeZone(TimeZone.getDefault());
        return out.format(new Date(p.start + offsetMin * 60000L));
    }

    // ------------------------------------------------------------------ pomocno

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String encPath(String s) {
        return enc(s).replace("+", "%20");
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", Repo.UA);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
            }
            return bo.toString("UTF-8");
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
