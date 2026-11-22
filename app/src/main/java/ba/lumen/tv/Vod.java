package ba.lumen.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filmovi i serije iz Xtream prijava (player_api.php).
 *
 *  - lista:   get_vod_categories / get_vod_streams, get_series_categories / get_series
 *  - detalji: get_vod_info&vod_id=ID, get_series_info&series_id=ID (opis, slike, trailer, epizode)
 *  - gledanje: /movie/U/P/ID.ext i /series/U/P/ID.ext
 *
 * Isti film iz vise prijava postaje jedna stavka sa vise izvora (ako jedan ne radi, pusti se drugi).
 * Lista se cuva na uredjaju 12 sati, pa se sljedeci put otvara odmah.
 */
public final class Vod {

    public static final int MOVIE = 1, SERIES = 2;
    static final long CACHE_MS = 12L * 60 * 60 * 1000;

    /** Jedan izvor filma/serije: koja prijava i koji ID na tom serveru. */
    public static final class Src {
        public final String loginId, id, ext;
        Src(String loginId, String id, String ext) { this.loginId = loginId; this.id = id; this.ext = ext; }
    }

    public static final class Item {
        public int kind;
        public String key;          // stabilni kljuc (za "nastavi gledati")
        public String title;        // ociscen naziv
        public String year;         // "2018" ili null
        public String poster;
        public float rating;        // 0-10, 0 = nepoznato
        public long added;          // sekunde
        public final List<String> cats = new ArrayList<>();   // kljucevi kategorija
        public final List<Src> sources = new ArrayList<>();
        public String searchKey;

        public String meta() {
            StringBuilder sb = new StringBuilder();
            if (year != null) sb.append(year);
            if (rating > 0) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append("★ ").append(String.format(Locale.ROOT, "%.1f", rating));
            }
            return sb.toString();
        }
    }

    public static final class Category {
        public final String key, name;
        public int count;
        int rank, order;
        Category(String key, String name) { this.key = key; this.name = name; }
    }

    public static final class Library {
        public final int kind;
        public final List<Item> items;
        public final List<Category> categories;
        public final List<String> failed;
        Map<String, Item> index;
        Library(int kind, List<Item> items, List<Category> categories, List<String> failed) {
            this.kind = kind; this.items = items; this.categories = categories; this.failed = failed;
        }
    }

    public static final class Episode {
        public String id, ext, title, plot, image, loginId;
        public int season, num, durationSecs;
    }

    public static final class Season {
        public int number;
        public String name;
        public final List<Episode> episodes = new ArrayList<>();
    }

    public static final class Details {
        public String plot, genre, director, cast, country, release, poster, backdrop, trailer, tmdbId, imdbId;
        public int durationSecs;
        public float rating;
        public final List<Season> seasons = new ArrayList<>();
        /** Prijava iz koje su detalji (za epizode serije). */
        public String loginId;

        public String durationText() {
            if (durationSecs <= 0) return null;
            int m = Math.round(durationSecs / 60f);
            return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
        }
    }

    public interface Callback<T> {
        void onResult(T result);
        void onError(String message);
    }

    // ------------------------------------------------------------------

    private static Vod instance;
    private final Context app;
    private final Repo repo;
    private final SharedPreferences progress;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService infoIo = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Library> libs = new HashMap<>();
    private final Map<String, Details> details = new HashMap<>();

    public static synchronized Vod get(Context c) {
        if (instance == null) instance = new Vod(c.getApplicationContext());
        return instance;
    }

    /** Za testove: novi objekat (bez dijeljenog stanja). */
    static synchronized Vod fresh(Context c) {
        instance = new Vod(c.getApplicationContext());
        return instance;
    }

    private Vod(Context app) {
        this.app = app;
        this.repo = Repo.get(app);
        this.progress = app.getSharedPreferences("lumen_vod", Context.MODE_PRIVATE);
    }

    /** Biblioteka iz memorije (ako je vec ucitana za trenutne prijave), inace null. */
    public synchronized Library cached(int kind) {
        return libs.get(libKey(kind));
    }

    private String libKey(int kind) {
        StringBuilder sb = new StringBuilder().append(kind);
        for (Repo.Source s : repo.logins()) sb.append('|').append(s.id).append('=').append(s.url.hashCode());
        return sb.toString();
    }

    /** Stavka iz ucitane biblioteke po kljucu (null ako biblioteka nije u memoriji). */
    public synchronized Item find(int kind, String key) {
        Library lib = libs.get(libKey(kind));
        if (lib == null || key == null) return null;
        if (lib.index == null) {
            lib.index = new HashMap<>();
            for (Item it : lib.items) lib.index.put(it.key, it);
        }
        return lib.index.get(key);
    }

    public void load(final int kind, final boolean force, final Callback<Library> cb) {
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final Library lib = loadNow(kind, force);
                    synchronized (Vod.this) { libs.put(libKey(kind), lib); }
                    main.post(new Runnable() { @Override public void run() { cb.onResult(lib); } });
                } catch (final Exception e) {
                    main.post(new Runnable() { @Override public void run() { cb.onError(e.getMessage()); } });
                }
            }
        });
    }

    Library loadNow(int kind, boolean force) throws Exception {
        List<Repo.Source> logins = repo.logins();
        if (logins.isEmpty()) throw new Exception("Nema Xtream prijave.");
        Merger m = new Merger(kind);
        List<String> failed = new ArrayList<>();
        int ok = 0;
        for (Repo.Source s : logins) {
            Xtream x = Xtream.from(s.url);
            if (x == null) continue;
            String part = kind == MOVIE ? "vod" : "series";
            File cats = cacheFile(s.id, part + "_cats");
            File items = cacheFile(s.id, part + "_items");
            boolean fresh = !force && cats.exists() && items.exists()
                    && System.currentTimeMillis() - items.lastModified() < CACHE_MS;
            if (!fresh) {
                try {
                    download(x.api(kind == MOVIE ? "get_vod_categories" : "get_series_categories", null), cats);
                    download(x.api(kind == MOVIE ? "get_vod_streams" : "get_series", null), items);
                } catch (Exception e) {
                    if (!(cats.exists() && items.exists())) {
                        failed.add(s.name);
                        continue;
                    }
                    failed.add(s.name);   // stari spisak ostaje, ali javi da server ne odgovara
                }
            }
            try {
                Map<String, String> catNames = readCategories(cats);
                try (BufferedReader r = reader(items)) {
                    m.addAll(r, s.id, catNames);
                }
                ok++;
            } catch (Exception e) {
                if (!failed.contains(s.name)) failed.add(s.name);
            }
        }
        if (ok == 0) {
            throw new Exception(kind == MOVIE
                    ? "Server ne daje listu filmova (ili ne odgovara)."
                    : "Server ne daje listu serija (ili ne odgovara).");
        }
        return m.build(failed);
    }

    private File cacheFile(String loginId, String part) {
        return new File(app.getFilesDir(), "vod_" + loginId.replaceAll("[^A-Za-z0-9]", "") + "_" + part + ".json");
    }

    private static BufferedReader reader(File f) throws Exception {
        return new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 64 * 1024);
    }

    static Map<String, String> readCategories(File f) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (BufferedReader r = reader(f)) {
            Object root = MiniJson.parse(r);
            List<Object> arr = MiniJson.arr(root);
            if (arr == null) return out;
            for (Object o : arr) {
                Map<String, Object> c = MiniJson.obj(o);
                String id = MiniJson.str(c, "category_id"), name = MiniJson.str(c, "category_name");
                if (id != null && name != null) out.put(id, name);
            }
        }
        return out;
    }

    /** Skini u fajl (preko privremenog, da prekid ne pokvari stari spisak). */
    static void download(String url, File target) throws Exception {
        File tmp = new File(target.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", Repo.UA);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n, total = 0, first = -1;
                while ((n = in.read(buf)) != -1) {
                    if (first == -1) {
                        for (int i = 0; i < n; i++) {
                            byte b = buf[i];
                            if (b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == (byte) 0xEF || b == (byte) 0xBB || b == (byte) 0xBF) continue;
                            first = b;
                            break;
                        }
                    }
                    out.write(buf, 0, n);
                    total += n;
                }
                if (first != '[' && first != '{') throw new Exception("Odgovor nije JSON");
            }
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) throw new Exception("Ne mogu sacuvati");
        }
    }

    // ------------------------------------------------------------------ spajanje

    /** Skuplja stavke iz svih prijava; isti naslov + godina = jedna stavka sa vise izvora. */
    static final class Merger {
        final int kind;
        final Map<String, Item> byKey = new LinkedHashMap<>();
        final Map<String, Category> cats = new LinkedHashMap<>();
        int catOrder;

        Merger(int kind) { this.kind = kind; }

        void addAll(BufferedReader r, String loginId, Map<String, String> catNames) throws Exception {
            // redoslijed kategorija kao na serveru
            for (Map.Entry<String, String> e : catNames.entrySet()) category(e.getValue());
            MiniJson j = new MiniJson(r);
            if (j.peek() != MiniJson.BEGIN_ARRAY) {
                // npr. {"user_info":{"auth":0}} kad je prijava istekla
                throw new Exception("Server nije vratio spisak");
            }
            j.beginArray();
            while (j.hasNext()) {
                if (j.peek() != MiniJson.BEGIN_OBJECT) { j.skipValue(); continue; }
                Map<String, String> f = new HashMap<>();
                j.beginObject();
                while (j.hasNext()) {
                    String k = j.nextName();
                    int t = j.peek();
                    if (t == MiniJson.BEGIN_ARRAY || t == MiniJson.BEGIN_OBJECT) {
                        if (k.equals("backdrop_path") && t == MiniJson.BEGIN_ARRAY) {
                            j.beginArray();
                            if (j.hasNext() && j.peek() == MiniJson.STRING) f.put(k, j.nextString());
                            while (j.hasNext()) j.skipValue();
                            j.endArray();
                        } else {
                            j.skipValue();
                        }
                    } else {
                        String v = j.nextString();
                        if (v != null) f.put(k, v);
                    }
                }
                j.endObject();
                add(f, loginId, catNames);
            }
            j.endArray();
        }

        void add(Map<String, String> f, String loginId, Map<String, String> catNames) {
            String id = f.get(kind == MOVIE ? "stream_id" : "series_id");
            String raw = f.get("name");
            if (id == null || raw == null || raw.trim().isEmpty()) return;
            String[] ty = cleanTitle(raw);
            String title = ty[0], year = ty[1];
            if (year == null) year = yearOf(f.get(kind == MOVIE ? "releasedate" : "releaseDate"));
            if (title.isEmpty()) return;
            String key = (kind == MOVIE ? "m:" : "s:") + titleKey(title) + "|" + (year == null ? "" : year);
            Item it = byKey.get(key);
            if (it == null) {
                it = new Item();
                it.kind = kind;
                it.key = key;
                it.title = title;
                it.year = year;
                it.searchKey = M3uParser.normalize(title + (year == null ? "" : " " + year));
                byKey.put(key, it);
            }
            for (Src s : it.sources) if (s.loginId.equals(loginId) && s.id.equals(id)) return;
            String ext = f.get("container_extension");
            it.sources.add(new Src(loginId, id, ext == null || ext.isEmpty() ? "mp4" : ext));
            if (it.poster == null) it.poster = url(f.get(kind == MOVIE ? "stream_icon" : "cover"));
            float rt = parseFloat(f.get("rating"));
            if (rt <= 0) rt = parseFloat(f.get("rating_5based")) * 2;
            if (rt > it.rating && rt <= 10) it.rating = rt;
            long added = parseLong(f.get(kind == MOVIE ? "added" : "last_modified"));
            if (added > it.added) it.added = added;
            String catName = catNames.get(f.get("category_id"));
            if (catName != null) {
                Category c = category(catName);
                if (!it.cats.contains(c.key)) { it.cats.add(c.key); c.count++; }
            }
        }

        Category category(String name) {
            String k = M3uParser.normalize(name).replaceAll("[^a-z0-9]+", " ").trim();
            Category c = cats.get(k);
            if (c == null) {
                c = new Category(k, name.trim());
                c.order = catOrder++;
                c.rank = vodCategoryRank(name);
                cats.put(k, c);
            }
            return c;
        }

        Library build(List<String> failed) {
            List<Item> items = new ArrayList<>(byKey.values());
            // najnovije prvo
            Collections.sort(items, new Comparator<Item>() {
                @Override public int compare(Item a, Item b) { return Long.compare(b.added, a.added); }
            });
            List<Category> cl = new ArrayList<>();
            for (Category c : cats.values()) if (c.count > 0) cl.add(c);
            Collections.sort(cl, new Comparator<Category>() {
                @Override public int compare(Category a, Category b) {
                    if (a.rank != b.rank) return a.rank - b.rank;
                    return a.order - b.order;
                }
            });
            return new Library(kind, items, cl, failed);
        }
    }

    /** Domaci/Ex-YU/BiH/Srbija/Hrvatska kategorije prve. */
    static int vodCategoryRank(String name) {
        String n = M3uParser.normalize(name).toUpperCase(Locale.ROOT);
        Set<String> t = new HashSet<>(Arrays.asList(n.split("[^A-Z0-9]+")));
        if (t.contains("DOMACI") || t.contains("DOMACE") || t.contains("DOMACA")) return 0;
        int r = MainActivity.categoryRank(name);
        return r == 9 ? 9 : r + 1;
    }

    private static final Pattern YEAR_PAREN = Pattern.compile("[\\(\\[]\\s*((?:19|20)\\d{2})\\s*[\\)\\]]");
    private static final Pattern YEAR_TAIL = Pattern.compile("(?:\\s|-|–)\\s*((?:19|20)\\d{2})\\s*$");
    private static final Pattern PREFIX = Pattern.compile(
            "^\\s*(?:[\\[(|]\\s*)?(?:EX-?YU|[A-Za-z]{2,3}|4K|UHD|FHD|HD|MULTI(?:-?SUB)?|VIP|NEW|NOVO)\\s*[\\])|:\\-–]+\\s*(?=\\S)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]|\\{[^}]*\\}");
    private static final Pattern TAIL_TAGS = Pattern.compile(
            "(?i)\\s*[-|]?\\s*\\b(4K|UHD|FHD|HD|SD|HEVC|H265|x265|MULTI-?SUB|SUB|SINH|SINHRONIZOVANO|SINKRONIZIRANO|TITLOVANO|NOVO|NEW|DOLBY|ATMOS|HDR)\\b\\s*$");
    private static final Set<String> PREFIX_CODES = new HashSet<>(Arrays.asList(
            "EX-YU", "EXYU", "BA", "BIH", "BH", "RS", "SRB", "HR", "HRV", "CRO", "ME", "MNE", "CG", "SI", "SLO",
            "MK", "MKD", "EN", "ENG", "UK", "US", "USA", "DE", "GER", "FR", "TR", "4K", "UHD",
            "FHD", "HD", "MULTI", "MULTISUB", "MULTI-SUB", "VIP", "NEW", "NOVO", "NF", "AMZ", "HBO", "DSNP"));

    /** "|EX-YU| Južni vetar (2018) [4K]" -> {"Južni vetar", "2018"} */
    static String[] cleanTitle(String raw) {
        String s = raw.trim();
        // prefiksi oznaka (ponovljeno, npr. "4K - EX-YU: ...")
        for (int i = 0; i < 3; i++) {
            Matcher m = PREFIX.matcher(s);
            if (!m.find()) break;
            String tag = m.group().replaceAll("[\\[\\]()|:\\-–\\s]", "").toUpperCase(Locale.ROOT);
            if (!PREFIX_CODES.contains(tag) && !tag.equals("EXYU")) break;
            s = s.substring(m.end());
        }
        String year = null;
        Matcher y = YEAR_PAREN.matcher(s);
        if (y.find()) {
            year = y.group(1);
            s = (s.substring(0, y.start()) + " " + s.substring(y.end())).trim();
        }
        s = BRACKETS.matcher(s).replaceAll(" ").trim();
        for (int i = 0; i < 3; i++) {
            String before = s;
            s = TAIL_TAGS.matcher(s).replaceAll("").trim();
            if (year == null) {
                Matcher t = YEAR_TAIL.matcher(s);
                // "Blade Runner 2049" nije godina: samo godine do 2030
                if (t.find() && t.start() > 0 && Integer.parseInt(t.group(1)) <= 2030) {
                    year = t.group(1);
                    s = s.substring(0, t.start()).trim();
                }
            }
            if (s.equals(before)) break;
        }
        s = s.replaceAll("\\(\\s*\\)", "").replaceAll("\\s{2,}", " ").replaceAll("[\\s\\-–|:]+$", "").trim();
        return new String[]{s.isEmpty() ? raw.trim() : s, year};
    }

    static String titleKey(String title) {
        return M3uParser.normalize(title).replace("&", " and ").replaceAll("[^a-z0-9]+", " ").trim();
    }

    static String yearOf(String date) {
        if (date == null) return null;
        Matcher m = Pattern.compile("\\b((?:19|20)\\d{2})\\b").matcher(date);
        return m.find() ? m.group(1) : null;
    }

    private static String url(String s) {
        if (s == null) return null;
        s = s.trim().replace("\\/", "/");
        return s.startsWith("http") ? s : null;
    }

    static float parseFloat(String s) {
        if (s == null) return 0;
        try { return Float.parseFloat(s.trim().replace(',', '.')); } catch (Exception e) { return 0; }
    }

    static long parseLong(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------------ detalji

    public Details cachedDetails(Item it) {
        synchronized (details) { return details.get(it.key); }
    }

    /** Opis, slike, trailer (i epizode za serije). Proba izvore redom dok jedan ne odgovori. */
    public void details(final Item it, final Callback<Details> cb) {
        Details d = cachedDetails(it);
        if (d != null) { cb.onResult(d); return; }
        infoIo.execute(new Runnable() {
            @Override public void run() {
                Exception last = null;
                for (Src s : it.sources) {
                    Xtream x = xtreamOf(s.loginId);
                    if (x == null) continue;
                    try {
                        String body = Xtream.httpGetPublic(x.api(it.kind == MOVIE ? "get_vod_info" : "get_series_info",
                                (it.kind == MOVIE ? "vod_id=" : "series_id=") + s.id));
                        final Details det = it.kind == MOVIE ? parseMovieInfo(body) : parseSeriesInfo(body, s.loginId);
                        det.loginId = s.loginId;
                        if (it.kind == SERIES && det.seasons.isEmpty()) { last = new Exception("Nema epizoda"); continue; }
                        synchronized (details) { details.put(it.key, det); }
                        main.post(new Runnable() { @Override public void run() { cb.onResult(det); } });
                        return;
                    } catch (Exception e) {
                        last = e;
                    }
                }
                final String msg = it.kind == SERIES ? "Ne mogu učitati epizode." : "Ne mogu učitati opis filma.";
                main.post(new Runnable() { @Override public void run() { cb.onError(msg); } });
            }
        });
    }

    Xtream xtreamOf(String loginId) {
        for (Repo.Source s : repo.logins()) if (s.id.equals(loginId)) return Xtream.from(s.url);
        return null;
    }

    static Details parseMovieInfo(String body) throws Exception {
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(new StringReader(body)));
        if (root == null) throw new Exception("Los odgovor");
        Details d = new Details();
        fillInfo(d, MiniJson.obj(root.get("info")));
        return d;
    }

    private static void fillInfo(Details d, Map<String, Object> info) {
        if (info == null) return;
        d.plot = first(info, "plot", "description");
        d.genre = MiniJson.str(info, "genre");
        d.director = MiniJson.str(info, "director");
        d.cast = first(info, "cast", "actors");
        d.country = MiniJson.str(info, "country");
        d.release = first(info, "releasedate", "releaseDate", "release_date");
        d.poster = url(first(info, "movie_image", "cover_big", "cover"));
        d.backdrop = url(MiniJson.str(info, "backdrop_path"));
        d.trailer = youtubeId(MiniJson.str(info, "youtube_trailer"));
        d.tmdbId = digits(first(info, "tmdb_id", "tmdb"));
        d.imdbId = MiniJson.str(info, "imdb_id");
        d.rating = parseFloat(MiniJson.str(info, "rating"));
        int secs = (int) parseLong(MiniJson.str(info, "duration_secs"));
        if (secs <= 0) secs = hms(MiniJson.str(info, "duration"));
        if (secs <= 0) secs = (int) parseLong(MiniJson.str(info, "episode_run_time")) * 60;
        d.durationSecs = secs;
        if (d.director != null && d.director.equalsIgnoreCase("n/a")) d.director = null;
        if (d.cast != null && d.cast.equalsIgnoreCase("n/a")) d.cast = null;
    }

    static Details parseSeriesInfo(String body, String loginId) throws Exception {
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(new StringReader(body)));
        if (root == null) throw new Exception("Los odgovor");
        Details d = new Details();
        fillInfo(d, MiniJson.obj(root.get("info")));
        Map<Integer, Season> seasons = new HashMap<>();
        List<Object> sl = MiniJson.arr(root.get("seasons"));
        if (sl != null) {
            for (Object o : sl) {
                Map<String, Object> m = MiniJson.obj(o);
                int n = (int) parseLong(MiniJson.str(m, "season_number"));
                Season s = new Season();
                s.number = n;
                s.name = MiniJson.str(m, "name");
                seasons.put(n, s);
            }
        }
        // epizode: {"1":[...], "2":[...]} ili [[...],[...]]
        Object eps = root.get("episodes");
        List<Object> groups = new ArrayList<>();
        Map<String, Object> em = MiniJson.obj(eps);
        if (em != null) groups.addAll(em.values());
        else if (MiniJson.arr(eps) != null) groups.addAll(MiniJson.arr(eps));
        for (Object g : groups) {
            List<Object> list = MiniJson.arr(g);
            if (list == null) continue;
            for (Object o : list) {
                Map<String, Object> m = MiniJson.obj(o);
                if (m == null) continue;
                Episode e = new Episode();
                e.id = MiniJson.str(m, "id");
                if (e.id == null) continue;
                e.loginId = loginId;
                e.ext = MiniJson.str(m, "container_extension");
                if (e.ext == null) e.ext = "mp4";
                e.season = (int) parseLong(MiniJson.str(m, "season"));
                e.num = (int) parseLong(MiniJson.str(m, "episode_num"));
                e.title = episodeTitle(MiniJson.str(m, "title"));
                Map<String, Object> info = MiniJson.obj(m.get("info"));
                e.plot = first(info, "plot", "description");
                e.image = url(first(info, "movie_image", "cover_big"));
                e.durationSecs = (int) parseLong(MiniJson.str(info, "duration_secs"));
                if (e.durationSecs <= 0) e.durationSecs = hms(MiniJson.str(info, "duration"));
                Season s = seasons.get(e.season);
                if (s == null) {
                    s = new Season();
                    s.number = e.season;
                    seasons.put(e.season, s);
                }
                s.episodes.add(e);
            }
        }
        List<Integer> nums = new ArrayList<>(seasons.keySet());
        Collections.sort(nums);
        for (Integer n : nums) {
            Season s = seasons.get(n);
            if (s.episodes.isEmpty()) continue;
            Collections.sort(s.episodes, new Comparator<Episode>() {
                @Override public int compare(Episode a, Episode b) { return a.num - b.num; }
            });
            if (s.name == null || s.name.matches("(?i)season\\s*\\d+")) s.name = "Sezona " + s.number;
            d.seasons.add(s);
        }
        return d;
    }

    /** "Serija - S01E03 - Naslov" -> "Naslov"; prazno -> null. */
    static String episodeTitle(String t) {
        if (t == null) return null;
        Matcher m = Pattern.compile("(?i)S\\d{1,2}\\s*E\\d{1,3}\\s*[-–:]?\\s*(.*)$").matcher(t);
        if (m.find()) {
            String rest = m.group(1).trim();
            return rest.isEmpty() ? null : rest;
        }
        return t.trim();
    }

    private static String first(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            String v = MiniJson.str(m, k);
            if (v != null) return v;
        }
        return null;
    }

    static String youtubeId(String s) {
        if (s == null) return null;
        s = s.trim();
        Matcher m = Pattern.compile("(?:v=|youtu\\.be/|embed/|shorts/)([A-Za-z0-9_-]{11})").matcher(s);
        if (m.find()) return m.group(1);
        return s.matches("[A-Za-z0-9_-]{11}") ? s : null;
    }

    private static String digits(String s) {
        if (s == null) return null;
        String d = s.replaceAll("[^0-9]", "");
        return d.isEmpty() || d.equals("0") ? null : d;
    }

    static int hms(String s) {
        if (s == null) return 0;
        String[] p = s.trim().split(":");
        try {
            int t = 0;
            for (String x : p) t = t * 60 + Integer.parseInt(x.trim());
            return p.length >= 2 ? t : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ linkovi za gledanje

    public String movieUrl(Src s) {
        Xtream x = xtreamOf(s.loginId);
        return x == null ? null : x.vodUrl("movie", s.id, s.ext);
    }

    public String episodeUrl(Episode e) {
        Xtream x = xtreamOf(e.loginId);
        return x == null ? null : x.vodUrl("series", e.id, e.ext);
    }

    // ------------------------------------------------------------------ nastavi gledati

    /** Kljuc za poziciju: film po stavci, epizoda po seriji + sezoni + broju. */
    public static String movieKey(Item it) { return it.key; }

    public static String episodeKey(Item series, Episode e) {
        return series.key + "#s" + e.season + "e" + e.num;
    }

    /** Sacuvaj gdje je stalo (ms). */
    public void savePosition(String key, long posMs, long durMs) {
        try {
            JSONObject o = new JSONObject();
            o.put("p", posMs);
            o.put("d", durMs);
            o.put("t", System.currentTimeMillis());
            progress.edit().putString("pos:" + key, o.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** {pozicija, trajanje} u ms, ili null. */
    public long[] position(String key) {
        String s = progress.getString("pos:" + key, null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            return new long[]{o.getLong("p"), o.getLong("d")};
        } catch (Exception e) {
            return null;
        }
    }

    /** Pozicija od koje treba nastaviti (0 = od pocetka; odgledano do kraja se ne nastavlja). */
    public long resumeFrom(String key) {
        long[] p = position(key);
        if (p == null || p[0] < 60000) return 0;
        if (p[1] > 0 && p[0] > p[1] * 0.95) return 0;
        return p[0];
    }

    /** Zapamti zadnju gledanu stavku (za "Nastavi gledati"). */
    public void markWatched(Item it, Episode e) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            if (e != null) {
                o.put("s", e.season);
                o.put("e", e.num);
            }
            progress.edit().putString("last:" + it.key, o.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Zadnja gledana epizoda serije: {sezona, epizoda} ili null. */
    public int[] lastEpisode(Item series) {
        String s = progress.getString("last:" + series.key, null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            if (!o.has("s")) return null;
            return new int[]{o.getInt("s"), o.getInt("e")};
        } catch (Exception e) {
            return null;
        }
    }

    /** Stavke koje je korisnik poceo gledati, najnovije prvo. */
    public List<Item> continueWatching(Library lib) {
        final Map<String, Long> when = new HashMap<>();
        for (Map.Entry<String, ?> e : progress.getAll().entrySet()) {
            if (!e.getKey().startsWith("last:")) continue;
            try {
                when.put(e.getKey().substring(5), new JSONObject(String.valueOf(e.getValue())).getLong("t"));
            } catch (Exception ignored) { }
        }
        List<Item> out = new ArrayList<>();
        for (Item it : lib.items) {
            if (!when.containsKey(it.key)) continue;
            if (it.kind == MOVIE) {
                long[] p = position(it.key);
                // film odgledan do kraja ne stoji u "nastavi gledati"
                if (p != null && p[1] > 0 && p[0] > p[1] * 0.95) continue;
            }
            out.add(it);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Long.compare(when.get(b.key), when.get(a.key)); }
        });
        return out;
    }

    /** Ukloni iz "nastavi gledati". */
    public void forget(Item it) {
        progress.edit().remove("last:" + it.key).apply();
    }

    // za URL parametre
    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
