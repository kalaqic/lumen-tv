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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Centralno mjesto za plejliste, kes, favorite i nedavno gledane kanale. */
public final class Repo {

    public static final String DEFAULT_URL = "https://iptv-org.github.io/iptv/index.m3u";
    static final String UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final long STALE_MS = 12L * 60 * 60 * 1000; // 12h

    public static final class Playlist {
        public String id, name, url;
        Playlist(String id, String name, String url) { this.id = id; this.name = name; this.url = url; }
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

    /** Svi kanali aktivne plejliste. */
    public List<Channel> all = new ArrayList<>();
    /** Lista koja je trenutno prikazana (koristi je plejer za ▲▼). */
    public List<Channel> current = new ArrayList<>();
    public String currentTitle = "";
    public String loadedPlaylistId = null;

    private final Set<String> favorites = new LinkedHashSet<>();
    private final List<String> recents = new ArrayList<>();

    private Repo(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("lumen", Context.MODE_PRIVATE);
        readList("favorites", favorites);
        List<String> r = new ArrayList<>();
        readList("recents", r);
        recents.addAll(r);
    }

    // ------------------------------------------------------------------ plejliste

    public List<Playlist> playlists() {
        List<Playlist> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("playlists", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Playlist(o.getString("id"), o.getString("name"), o.getString("url")));
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) {
            out.add(new Playlist("default", "IPTV-org · Svi kanali", DEFAULT_URL));
            savePlaylists(out);
        }
        return out;
    }

    public void savePlaylists(List<Playlist> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Playlist p : list) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("url", p.url);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        prefs.edit().putString("playlists", arr.toString()).apply();
    }

    public Playlist addPlaylist(String name, String url) {
        List<Playlist> list = playlists();
        for (Playlist p : list) if (p.url.equals(url)) return p;
        Playlist p = new Playlist("p" + System.currentTimeMillis(), name, url);
        list.add(p);
        savePlaylists(list);
        return p;
    }

    public void removePlaylist(String id) {
        List<Playlist> list = playlists();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                File f = cacheFile(list.get(i));
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                list.remove(i);
                break;
            }
        }
        if (list.isEmpty()) list.add(new Playlist("default", "IPTV-org · Svi kanali", DEFAULT_URL));
        savePlaylists(list);
        if (id.equals(activeId())) setActive(list.get(0).id);
    }

    public String activeId() {
        List<Playlist> list = playlists();
        String id = prefs.getString("active", null);
        for (Playlist p : list) if (p.id.equals(id)) return id;
        return list.get(0).id;
    }

    public void setActive(String id) {
        prefs.edit().putString("active", id).apply();
    }

    public Playlist active() {
        String id = activeId();
        for (Playlist p : playlists()) if (p.id.equals(id)) return p;
        return playlists().get(0);
    }

    private File cacheFile(Playlist p) {
        return new File(app.getFilesDir(), "pl_" + Integer.toHexString(p.url.hashCode()) + ".m3u");
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
                        result = parseFile(cache);
                        fromCache = true;
                    } catch (Exception e) { result = null; }
                }
                if (result == null || result.isEmpty()) {
                    try {
                        fetchPlaylist(p, cache);
                        result = parseFile(cache);
                        fromCache = false;
                    } catch (Exception e) {
                        error = friendlyError(e);
                        if (haveCache) {
                            try { result = parseFile(cache); fromCache = true; } catch (Exception ignored) { }
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
                    final List<Channel> res = parseFile(tmp);
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

    private static List<Channel> parseFile(File f) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 64 * 1024)) {
            return M3uParser.parse(r);
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
        List<String> urls = urlsOf(p);
        if (urls.size() <= 1) {
            download(urls.isEmpty() ? p.url : urls.get(0), target);
            return;
        }
        File part = new File(app.getFilesDir(), "part.tmp");
        File combined = new File(target.getPath() + ".combined");
        int ok = 0;
        Exception last = null;
        try (OutputStream out = new FileOutputStream(combined)) {
            out.write("#EXTM3U\n".getBytes("UTF-8"));
            for (String u : urls) {
                try {
                    download(u, part);
                    try (InputStream in = new FileInputStream(part)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    out.write('\n');
                    ok++;
                } catch (Exception e) {
                    last = e;
                }
            }
        }
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

    public boolean isFavorite(Channel c) { return favorites.contains(c.url); }

    /** @return true ako je kanal sada favorit */
    public boolean toggleFavorite(Channel c) {
        boolean now;
        if (favorites.contains(c.url)) { favorites.remove(c.url); now = false; }
        else { favorites.add(c.url); now = true; }
        writeList("favorites", favorites);
        return now;
    }

    public int favoriteCount() { return favorites.size(); }

    public boolean hasPlaylistUrl(String url) {
        for (Playlist p : playlists()) if (p.url.equals(url)) return true;
        return false;
    }

    public void addRecent(Channel c) {
        recents.remove(c.url);
        recents.add(0, c.url);
        while (recents.size() > 40) recents.remove(recents.size() - 1);
        writeList("recents", recents);
    }

    public List<Channel> favoriteChannels() {
        List<Channel> out = new ArrayList<>();
        for (Channel c : all) if (favorites.contains(c.url)) out.add(c);
        return out;
    }

    public List<Channel> recentChannels() {
        Map<String, Channel> byUrl = new HashMap<>();
        for (Channel c : all) byUrl.put(c.url, c);
        List<Channel> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String u : recents) {
            Channel c = byUrl.get(u);
            if (c != null && seen.add(u)) out.add(c);
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
