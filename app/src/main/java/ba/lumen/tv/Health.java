package ba.lumen.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Pamti koji streamovi rade, a koji su mrtvi ili blokirani, i provjerava ih u pozadini. */
public final class Health {

    public static final int UNKNOWN = 0, ONLINE = 1, OFFLINE = 2, BLOCKED = 3;
    private static final long TTL = 6L * 60 * 60 * 1000;       // rezultat vazi 6 sati
    private static final int THREADS = 6;

    public interface Progress {
        void onProgress(int done, int total, boolean finished);
    }

    private static Health instance;

    public static synchronized Health get(Context c) {
        if (instance == null) instance = new Health(c.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, long[]> map = new HashMap<>();   // url -> {status, vrijeme}
    private volatile int generation = 0;
    private ExecutorService pool;
    private volatile boolean running;

    private Health(Context app) {
        prefs = app.getSharedPreferences("lumen_health", Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(prefs.getString("data", "{}"));
            Iterator<String> it = o.keys();
            long now = System.currentTimeMillis();
            while (it.hasNext()) {
                String url = it.next();
                String[] p = o.getString(url).split(":");
                long t = Long.parseLong(p[1]);
                if (now - t < TTL) map.put(url, new long[]{Long.parseLong(p[0]), t});
            }
        } catch (Exception ignored) { }
    }

    public synchronized int status(String url) {
        long[] v = map.get(url);
        if (v == null) return UNKNOWN;
        if (System.currentTimeMillis() - v[1] > TTL) return UNKNOWN;
        return (int) v[0];
    }

    public synchronized void set(String url, int status) {
        if (status == UNKNOWN) map.remove(url);
        else map.put(url, new long[]{status, System.currentTimeMillis()});
    }

    public synchronized void clear() {
        map.clear();
        prefs.edit().remove("data").apply();
    }

    public boolean isRunning() { return running; }

    public synchronized void save() {
        JSONObject o = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            for (Map.Entry<String, long[]> e : map.entrySet()) {
                if (now - e.getValue()[1] < TTL) o.put(e.getKey(), e.getValue()[0] + ":" + e.getValue()[1]);
            }
        } catch (Exception ignored) { }
        prefs.edit().putString("data", o.toString()).apply();
    }

    /** Provjeri kanale ciji status jos nije poznat. */
    public void check(List<Channel> channels, final Progress progress) {
        cancel();
        final int gen = generation;
        final List<Channel> todo = new ArrayList<>();
        for (Channel c : channels) {
            if (status(c.url) == UNKNOWN && c.url.toLowerCase().startsWith("http")) todo.add(c);
        }
        final int total = todo.size();
        if (total == 0) {
            progress.onProgress(0, 0, true);
            return;
        }
        running = true;
        final AtomicInteger done = new AtomicInteger();
        pool = Executors.newFixedThreadPool(THREADS);
        for (final Channel c : todo) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    if (gen != generation) return;
                    int s = StreamProbe.check(c);
                    if (gen != generation) return;
                    set(c.url, s);
                    final int d = done.incrementAndGet();
                    final boolean finished = d == total;
                    if (finished) { running = false; save(); }
                    if (finished || d % 10 == 0) {
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (gen == generation) progress.onProgress(d, total, finished);
                            }
                        });
                    }
                }
            });
        }
        pool.shutdown();
    }

    public void cancel() {
        generation++;
        running = false;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
            save();
        }
    }

    /** Rang za sortiranje: manji broj = vise na vrhu. */
    public int rank(Channel c) {
        int s = status(c.url);
        if (s == ONLINE) return 0;
        if (s == UNKNOWN) return c.geoBlocked ? 2 : 1;
        return 3;
    }
}
