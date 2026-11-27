package ba.lumen.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Centralno mjesto za izvor kanala (besplatno / Xtream prijava), kes, favorite i historiju. */
public final class Repo {

    /** Besplatne Lumen TV liste po drzavama (folder playlists/ u GitHub repozitoriju aplikacije).
     *  Redoslijed je i redoslijed u aplikaciji: BiH, Srbija, Hrvatska, pa ostale. */
    public static final String[][] COUNTRY_LISTS = {
            {"ba", "\uD83C\uDDE7\uD83C\uDDE6", "Bosna i Hercegovina"},
            {"rs", "\uD83C\uDDF7\uD83C\uDDF8", "Srbija"},
            {"hr", "\uD83C\uDDED\uD83C\uDDF7", "Hrvatska"},
            {"me", "\uD83C\uDDF2\uD83C\uDDEA", "Crna Gora"},
            {"si", "\uD83C\uDDF8\uD83C\uDDEE", "Slovenija"},
            {"mk", "\uD83C\uDDF2\uD83C\uDDF0", "Sjeverna Makedonija"},
            {"xk", "\uD83C\uDDFD\uD83C\uDDF0", "Kosovo"},
    };

    public static final String MODE_FREE = "free";
    public static final String MODE_XTREAM = "xtream";

    static final String UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final long STALE_MS = 12L * 60 * 60 * 1000; // 12h

    public static final class Playlist {
        public final String id, name, url;
        /** ID liste za svaki link u url-u (istim redom); null = jedna obicna lista. */
        public List<String> partIds;
        /** Spajaj iste kanale i kad su u razlicitim listama/kategorijama (profil prijava). */
        public boolean smartMerge;
        Playlist(String id, String name, String url) { this.id = id; this.name = name; this.url = url; }
    }

    /** Jedna sacuvana lista: Xtream prijava ili vlastiti M3U link. */
    public static final class Source {
        public final String id, name, url;
        Source(String id, String name, String url) { this.id = id; this.name = name; this.url = url; }
    }

    public interface LoadCallback {
        void onLoaded(List<Channel> channels, boolean fromCache);
        void onError(String message);
    }

    private static Repo instance;

    public static synchronized Repo get(Context c) {
        if (instance == null) instance = new Repo(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation = 0;

    /** Svi kanali aktivnog izvora. */
    public List<Channel> all = new ArrayList<>();
    /** Lista koja je trenutno prikazana (koristi je plejer za ▲▼). */
    public List<Channel> current = new ArrayList<>();
    public String currentTitle = "";
    public String loadedPlaylistId = null;
    /** Nazivi lista koje se pri zadnjem ucitavanju nisu mogle skinuti. */
    public volatile List<String> lastFailed = new ArrayList<>();

    private final Set<String> favorites = new LinkedHashSet<>();
    private final List<String> recents = new ArrayList<>();

    private Repo(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("lumen", Context.MODE_PRIVATE);
        migrateOldSettings();
        readList("favorites", favorites);
        List<String> r = new ArrayList<>();
        readList("recents", r);
        recents.addAll(r);
    }

    // ------------------------------------------------------------------ izvor: besplatno ili prijava

    public String countryListUrl(String code) {
        return "https://raw.githubusercontent.com/" + app.getString(R.string.update_repo)
                + "/main/playlists/" + code + ".m3u";
    }

    // ---- dva profila: besplatno (javne liste + vlastiti linkovi) i prijave (sve Xtream prijave zajedno)

    /** MODE_FREE, MODE_XTREAM (profil prijava), ili null ako korisnik jos nije izabrao. */
    public String mode() {
        String m = prefs.getString("mode", null);
        if (MODE_XTREAM.equals(m) && logins().isEmpty()) return MODE_FREE;
        if (MODE_XTREAM.equals(m) || MODE_FREE.equals(m)) return m;
        return null;
    }

    /** Aktivan je profil "Moje prijave". */
    public boolean isXtream() { return MODE_XTREAM.equals(mode()); }

    public void setFreeMode() {
        prefs.edit().putString("mode", MODE_FREE).apply();
        cleanOldCache();
    }

    public void setLoginsMode() {
        if (!logins().isEmpty()) prefs.edit().putString("mode", MODE_XTREAM).apply();
        cleanOldCache();
    }

    /** Sve Xtream prijave (profil "Moje prijave"). */
    public List<Source> logins() { return readSources("logins"); }

    /** Vlastiti M3U linkovi bez prijave (profil "Besplatni kanali"). */
    public List<Source> links() { return readSources("links"); }

    /** Dodaj Xtream prijavu; ista prijava (isti server+korisnik) se ne dodaje dvaput. */
    public Source addLogin(String name, String url) {
        List<Source> l = logins();
        Xtream nx = Xtream.from(url);
        for (Source s : l) {
            Xtream ox = Xtream.from(s.url);
            if (s.url.equals(url) || (nx != null && ox != null && nx.host.equalsIgnoreCase(ox.host) && nx.user.equals(ox.user))) {
                // ista prijava: zamijeni (npr. nova lozinka)
                Source upd = new Source(s.id, name, url);
                l.set(l.indexOf(s), upd);
                writeSources("logins", l);
                return upd;
            }
        }
        Source src = new Source("x" + System.currentTimeMillis(), name, url);
        l.add(src);
        writeSources("logins", l);
        return src;
    }

    public Source addLink(String name, String url) {
        List<Source> l = links();
        for (Source s : l) if (s.url.equals(url)) return s;
        Source src = new Source("m" + System.currentTimeMillis(), name, url);
        l.add(src);
        writeSources("links", l);
        return src;
    }

    /**
     * Zamijeni sve liste (preuzete sa sajta). Vraca true ako se nesto promijenilo.
     * Ako vise nema prijava, profil "Moje prijave" se sam prebaci na besplatne kanale.
     */
    public synchronized boolean replaceSources(List<Source> logins, List<Source> links) {
        String before = prefs.getString("logins", "[]") + "|" + prefs.getString("links", "[]");
        writeSources("logins", logins);
        writeSources("links", links);
        String after = prefs.getString("logins", "[]") + "|" + prefs.getString("links", "[]");
        return !before.equals(after);
    }

    public void removeSource(String id) {
        for (String key : new String[]{"logins", "links"}) {
            List<Source> l = readSources(key);
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i).id.equals(id)) {
                    l.remove(i);
                    writeSources(key, l);
                    return;
                }
            }
        }
    }

    /** Xtream prijava iz koje dolazi ovaj link kanala, ili null. */
    public Xtream xtreamFor(Channel source) {
        if (source == null || source.listId == null) return null;
        for (Source s : logins()) if (s.id.equals(source.listId)) return Xtream.from(s.url);
        return null;
    }

    /** id liste -> naziv (za filter po listi u meniju). */
    public Map<String, String> sourceNames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("free", "Balkanske liste");
        for (Source s : links()) m.put(s.id, s.name);
        for (Source s : logins()) m.put(s.id, s.name);
        return m;
    }

    public Playlist active() { return profile(isXtream()); }

    /** Plejlista profila: logins=true -> sve prijave, false -> besplatne liste + vlastiti linkovi. */
    Playlist profile(boolean loginsProfile) {
        List<String> ids = new ArrayList<>();
        StringBuilder url = new StringBuilder();
        Playlist p;
        if (loginsProfile) {
            for (Source s : logins()) { append(url, s.url); ids.add(s.id); }
            String u = url.toString();
            p = new Playlist("xtream_" + Integer.toHexString((u + "|" + ids).hashCode()), "Moje prijave", u);
            p.smartMerge = true;
        } else {
            for (String[] c : COUNTRY_LISTS) { append(url, countryListUrl(c[0])); ids.add("free:" + c[0]); }
            for (Source s : links()) { append(url, s.url); ids.add(s.id); }
            String u = url.toString();
            p = new Playlist("free_" + Integer.toHexString((u + "|" + ids).hashCode()), "Besplatni kanali", u);
        }
        p.partIds = ids;
        return p;
    }

    private static void append(StringBuilder sb, String u) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(u);
    }

    public String activeId() { return active().id; }

    private List<Source> readSources(String key) {
        List<Source> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Source(o.getString("id"), o.getString("name"), o.getString("url")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void writeSources(String key, List<Source> l) {
        JSONArray arr = new JSONArray();
        try {
            for (Source s : l) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("url", s.url);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        prefs.edit().putString(key, arr.toString()).apply();
    }

    private File cacheFile(Playlist p) {
        String key = p.url + "|" + (p.partIds == null ? "" : p.partIds.toString());
        return new File(app.getFilesDir(), "pl2_" + Integer.toHexString(key.hashCode()) + ".m3u");
    }

    /** Obrisi kes starih plejlisti koje se vise ne koriste. */
    private void cleanOldCache() {
        try {
            Set<String> keep = new HashSet<>();
            keep.add(cacheFile(profile(false)).getName());
            if (!logins().isEmpty()) keep.add(cacheFile(profile(true)).getName());
            File[] files = app.getFilesDir().listFiles();
            if (files == null) return;
            for (File f : files) {
                String n = f.getName();
                // samo zavrseni kes fajlovi (.m3u), nikad .part/.combined koji se upravo skidaju
                if ((n.startsWith("pl_") || n.startsWith("pl2_")) && n.endsWith(".m3u") && !keep.contains(n)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignored) { }
    }

    /** Prebacivanje sa starog sistema (lista plejlisti) na novi (besplatno / prijava). */
    private void migrateOldSettings() {
        // jedna sacuvana prijava (verzija prije profila) -> prva prijava u "Moje prijave"
        String oldX = prefs.getString("xtream_url", null);
        if (oldX != null && !oldX.isEmpty() && !prefs.contains("logins")) {
            String n = prefs.getString("xtream_name", null);
            List<Source> l = new ArrayList<>();
            l.add(new Source("x1", n == null || n.isEmpty() ? "Moja prijava" : n, oldX));
            writeSources("logins", l);
            prefs.edit().remove("xtream_url").remove("xtream_name").apply();
        }
        if (prefs.contains("mode") || !prefs.contains("playlists")) return;
        String activeId = prefs.getString("active", null);
        String xUrl = null, xName = null;
        boolean activeIsXtream = false;
        try {
            JSONArray arr = new JSONArray(prefs.getString("playlists", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String url = o.getString("url");
                if (!url.contains("get.php")) continue;
                boolean isActive = o.getString("id").equals(activeId);
                if (xUrl == null || isActive) {
                    xUrl = url;
                    xName = o.getString("name");
                }
                if (isActive) activeIsXtream = true;
            }
        } catch (Exception ignored) { }
        SharedPreferences.Editor ed = prefs.edit();
        if (xUrl != null && !prefs.contains("logins")) {
            List<Source> l = new ArrayList<>();
            l.add(new Source("x1", xName, xUrl));
            writeSources("logins", l);
        }
        if (activeIsXtream) ed.putString("mode", MODE_XTREAM);
        // inace "mode" ostaje prazan pa ce aplikacija jednom ponuditi izbor
        ed.remove("playlists").remove("active").remove("seeded_countries_v1").remove("seeded_v2");
        ed.apply();
    }

    // ------------------------------------------------------------------ ucitavanje

    public void load(final boolean forceRefresh, final LoadCallback cb) {
        final Playlist p = active();
        final int gen = ++generation;
        io.execute(new Runnable() {
            @Override public void run() {
                File cache = cacheFile(p);
                boolean haveCache = cache.exists() && cache.length() > 0;
                List<Channel> result = null;
                String error = null;
                boolean fromCache = false;

                if (!forceRefresh && haveCache) {
                    try {
                        result = parseFile(cache, p.smartMerge);
                        fromCache = true;
                    } catch (Exception e) { result = null; }
                }
                if (result == null || result.isEmpty()) {
                    try {
                        fetchPlaylist(p, cache);
                        result = parseFile(cache, p.smartMerge);
                        fromCache = false;
                    } catch (Exception e) {
                        error = friendlyError(e);
                        if (haveCache) {
                            try { result = parseFile(cache, p.smartMerge); fromCache = true; } catch (Exception ignored) { }
                        }
                    }
                }
                final List<Channel> res = result;
                final String err = error;
                final boolean cached = fromCache;
                final boolean stale = cached && System.currentTimeMillis() - cache.lastModified() > STALE_MS;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (gen != generation) return;
                        if (res != null && !res.isEmpty()) {
                            all = res;
                            loadedPlaylistId = p.id;
                            cb.onLoaded(res, cached);
                            if (stale && err == null) backgroundRefresh(p, cb);
                        } else {
                            cb.onError(err != null ? err : "Plejlista je prazna ili nije ispravna.");
                        }
                    }
                });
            }
        });
    }

    private void backgroundRefresh(final Playlist p, final LoadCallback cb) {
        final int gen = generation;
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    File cache = cacheFile(p);
                    File tmp = new File(app.getFilesDir(), "refresh.tmp");
                    fetchPlaylist(p, tmp);
                    final List<Channel> res = parseFile(tmp, p.smartMerge);
                    if (res.isEmpty()) return;
                    //noinspection ResultOfMethodCallIgnored
                    cache.delete();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(cache);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (gen != generation) return;
                            all = res;
                            cb.onLoaded(res, false);
                        }
                    });
                } catch (Exception ignored) { }
            }
        });
    }

    private static List<Channel> parseFile(File f, boolean smartMerge) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 64 * 1024)) {
            return M3uParser.parse(r, smartMerge);
        }
    }

    /** Lista linkova plejliste (jedna plejlista moze spajati vise M3U linkova, jedan po redu). */
    public static List<String> urlsOf(Playlist p) {
        List<String> out = new ArrayList<>();
        for (String u : p.url.split("\\s*\\n\\s*")) {
            String t = u.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Skida jednu ili vise M3U plejlisti i spaja ih u jedan fajl. */
    private void fetchPlaylist(Playlist p, File target) throws Exception {
        fetchPlaylist(p, target, cacheFile(p));
    }

    /**
     * @param previous zadnji uspjesno ucitan fajl ovog profila: ako se neka lista sada ne moze
     *                 skinuti (server privremeno ne radi), njeni kanali se uzmu odatle.
     */
    void fetchPlaylist(Playlist p, File target, File previous) throws Exception {
        List<String> urls = urlsOf(p);
        boolean marked = p.partIds != null && p.partIds.size() == urls.size();
        if (urls.size() <= 1 && !marked) {
            download(urls.isEmpty() ? p.url : urls.get(0), target);
            return;
        }
        Map<String, String> names = sourceNames();
        List<String> failed = new ArrayList<>();
        File part = new File(app.getFilesDir(), "part.tmp");
        File combined = new File(target.getPath() + ".combined");
        int ok = 0;
        Exception last = null;
        try (OutputStream out = new FileOutputStream(combined)) {
            out.write("#EXTM3U\n".getBytes("UTF-8"));
            for (int i = 0; i < urls.size(); i++) {
                String u = urls.get(i);
                try {
                    download(u, part);
                    if (marked) out.write(("\n#LUMEN-LIST:" + p.partIds.get(i) + "\n").getBytes("UTF-8"));
                    try (InputStream in = new FileInputStream(part)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    out.write('\n');
                    ok++;
                } catch (Exception e) {
                    last = e;
                    if (marked) {
                        String id = p.partIds.get(i);
                        if (copySection(previous, id, out)) ok++;
                        String n = names.get(id);
                        if (n != null && !failed.contains(n)) failed.add(n);
                    }
                }
            }
        }
        lastFailed = failed;
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        if (ok == 0) {
            //noinspection ResultOfMethodCallIgnored
            combined.delete();
            throw last != null ? last : new Exception("Nijedna lista se ne može učitati");
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!combined.renameTo(target)) throw new Exception("Ne mogu sačuvati plejlistu");
    }

    /** Prepisi dio jedne liste (od njene oznake do sljedece) iz starog fajla. */
    static boolean copySection(File old, String id, OutputStream out) {
        if (old == null || !old.exists()) return false;
        String mark = M3uParser.LIST_MARK + id;
        boolean in = false, any = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(old), "UTF-8"), 64 * 1024)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(M3uParser.LIST_MARK)) {
                    in = line.trim().equals(mark);
                    if (in && !any) { out.write(("\n" + mark + "\n").getBytes("UTF-8")); any = true; }
                    continue;
                }
                if (in) {
                    out.write(line.getBytes("UTF-8"));
                    out.write('\n');
                }
            }
        } catch (Exception e) {
            return false;
        }
        return any;
    }

    private void download(String url, File target) throws Exception {
        HttpURLConnection conn = null;
        String current = url;
        boolean ok = false;
        for (int redirects = 0; redirects < 6; redirects++) {
            conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) throw new Exception("HTTP " + code);
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code != 200) {
                conn.disconnect();
                throw new Exception("HTTP " + code);
            }
            ok = true;
            break;
        }
        if (!ok || conn == null) throw new Exception("Previše preusmjeravanja");
        File tmp = new File(target.getPath() + ".part");
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!tmp.renameTo(target)) throw new Exception("Ne mogu sačuvati plejlistu");
    }

    private static String friendlyError(Exception e) {
        String m = e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "Nema internet konekcije ili je adresa pogrešna.";
        if (e instanceof java.net.SocketTimeoutException) return "Server ne odgovara (isteklo vrijeme).";
        if (m != null && m.startsWith("HTTP")) return "Server je vratio grešku (" + m + ").";
        return "Ne mogu učitati plejlistu" + (m != null ? ": " + m : ".");
    }

    // ------------------------------------------------------------------ favoriti i historija

    /** Kanal je favorit ako je bilo koji njegov izvor sacuvan kao favorit. */
    public boolean isFavorite(Channel c) {
        for (Channel s : c.allSources()) if (favorites.contains(s.url)) return true;
        return false;
    }

    /** @return true ako je kanal sada favorit */
    public boolean toggleFavorite(Channel c) {
        boolean now;
        if (isFavorite(c)) {
            for (Channel s : c.allSources()) favorites.remove(s.url);
            now = false;
        } else {
            favorites.add(c.url);
            now = true;
        }
        writeList("favorites", favorites);
        return now;
    }

    public int favoriteCount() { return favorites.size(); }

    public void addRecent(Channel c) {
        for (Channel s : c.allSources()) recents.remove(s.url);
        recents.add(0, c.url);
        while (recents.size() > 40) recents.remove(recents.size() - 1);
        writeList("recents", recents);
    }

    public List<Channel> favoriteChannels() {
        List<Channel> out = new ArrayList<>();
        for (Channel c : all) if (isFavorite(c)) out.add(c);
        return out;
    }

    public List<Channel> recentChannels() {
        // svaki izvor vodi na svoj kanal
        Map<String, Channel> byUrl = new HashMap<>();
        for (Channel c : all) for (Channel s : c.allSources()) byUrl.put(s.url, c);
        List<Channel> out = new ArrayList<>();
        Set<Channel> seen = new HashSet<>();
        for (String u : recents) {
            Channel c = byUrl.get(u);
            if (c != null && seen.add(c)) out.add(c);
        }
        return out;
    }

    public int recentCount() { return recentChannels().size(); }

    private void readList(String key, java.util.Collection<String> into) {
        try {
            JSONArray arr = new JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < arr.length(); i++) into.add(arr.getString(i));
        } catch (Exception ignored) { }
    }

    private void writeList(String key, java.util.Collection<String> items) {
        JSONArray arr = new JSONArray();
        for (String s : items) arr.put(s);
        prefs.edit().putString(key, arr.toString()).apply();
    }

    public List<Channel> unmodifiableAll() { return Collections.unmodifiableList(all); }
}
