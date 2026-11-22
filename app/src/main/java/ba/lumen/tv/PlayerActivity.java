package ba.lumen.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends Activity {

    public static final String EXTRA_INDEX = "index";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Repo repo;
    private List<Channel> list;
    private int index;
    private int playingIndex = -1;

    private ExoPlayer player;
    private DefaultHttpDataSource.Factory httpFactory;
    private PlayerView playerView;
    private View buffering, errorView, overlay, panel;
    private TextView errorText, ovName, ovMeta, ovNumber, ovQuality, ovClock, ovInitials, panelTitle, panelSub;
    private ImageView ovLogo, ovFav;
    private RecyclerView panelList;
    private RowAdapter rowAdapter;

    private boolean triedAsHls = false;
    private int playToken = 0;

    /** Izvor koji ne krene za ovoliko vremena smatra se neispravnim -> probaj sljedeci. */
    private static final long START_TIMEOUT_MS = 15000;
    /** Izvori kanala koji se trenutno pusta, redom kako se probaju. */
    private List<Channel> sources = new ArrayList<>();
    private int sourceIdx = 0;
    private Channel currentSource;
    private TextView sourceStatus;

    // ---- TV vodic (samo Xtream prijava) i arhiva
    private Xtream xtream;
    private final ExecutorService epgExec = Executors.newSingleThreadExecutor();
    private List<Xtream.Program> epg = new ArrayList<>();
    private String epgStreamId;
    private boolean epgLoading = false;
    private int epgToken = 0;
    private boolean archiveMode = false;
    private Xtream.Program archiveProgram;
    private int archiveOffsetMin = 0;
    /** cilj premotavanja (u min od pocetka emisije) dok se ceka da korisnik pusti tipku; -1 = nema */
    private int archiveSeekTargetMin = -1;
    private View epgBox, epgPanel, pauseBadge;
    private TextView ovLive, ovHint, epgNowTime, epgNowTitle, epgLeft, epgNext;
    private TextView epgPanelTitle, epgPanelSub, epgEmpty, epgDesc;
    private ProgressBar epgProgress;
    private RecyclerView epgList;
    private EpgAdapter epgAdapter;
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateEpgBar();
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable archiveSeekCommit = new Runnable() {
        @Override public void run() {
            if (archiveMode && archiveProgram != null && archiveSeekTargetMin >= 0) {
                int t = archiveSeekTargetMin;
                archiveSeekTargetMin = -1;
                playArchive(archiveProgram, t);
            }
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (player == null || currentSource == null) return;
            if (player.getPlaybackState() == Player.STATE_READY) return;
            onSourceFailed(Health.OFFLINE, "Stream ne odgovara.");
        }
    };
    private final ExecutorService probeExec = Executors.newSingleThreadExecutor();
    /** url -> otkriveni mime tip ("" = obicni stream / TS) */
    private static final Map<String, String> PROBED = new HashMap<>();
    private boolean longPressHandled = false;
    private boolean okDown = false;

    private final Runnable hideOverlay = new Runnable() {
        @Override public void run() { overlay.animate().alpha(0f).setDuration(350).start(); }
    };
    private final Runnable playPending = new Runnable() {
        @Override public void run() { play(index); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        Ui.applyFonts(getWindow().getDecorView());

        repo = Repo.get(this);
        list = new ArrayList<>(repo.current);
        if (list.isEmpty()) { finish(); return; }
        index = Math.max(0, Math.min(list.size() - 1, getIntent().getIntExtra(EXTRA_INDEX, 0)));
        if (savedInstanceState != null) index = savedInstanceState.getInt(EXTRA_INDEX, index);

        playerView = findViewById(R.id.player_view);
        buffering = findViewById(R.id.buffering);
        errorView = findViewById(R.id.error_view);
        errorText = findViewById(R.id.error_text);
        overlay = findViewById(R.id.overlay);
        ovName = findViewById(R.id.ov_name);
        ovMeta = findViewById(R.id.ov_meta);
        ovNumber = findViewById(R.id.ov_number);
        ovQuality = findViewById(R.id.ov_quality);
        ovClock = findViewById(R.id.ov_clock);
        ovInitials = findViewById(R.id.ov_initials);
        ovLogo = findViewById(R.id.ov_logo);
        ovFav = findViewById(R.id.ov_fav);
        panel = findViewById(R.id.panel);
        panelTitle = findViewById(R.id.panel_title);
        panelSub = findViewById(R.id.panel_sub);
        panelList = findViewById(R.id.panel_list);
        sourceStatus = findViewById(R.id.source_status);
        epgBox = findViewById(R.id.epg_box);
        epgPanel = findViewById(R.id.epg_panel);
        pauseBadge = findViewById(R.id.pause_badge);
        ovLive = findViewById(R.id.ov_live);
        ovHint = findViewById(R.id.ov_hint);
        epgNowTime = findViewById(R.id.epg_now_time);
        epgNowTitle = findViewById(R.id.epg_now_title);
        epgLeft = findViewById(R.id.epg_left);
        epgNext = findViewById(R.id.epg_next);
        epgProgress = findViewById(R.id.epg_progress);
        epgPanelTitle = findViewById(R.id.epg_panel_title);
        epgPanelSub = findViewById(R.id.epg_panel_sub);
        epgEmpty = findViewById(R.id.epg_empty);
        epgDesc = findViewById(R.id.epg_desc);
        epgList = findViewById(R.id.epg_list);
        epgAdapter = new EpgAdapter();
        epgList.setLayoutManager(new LinearLayoutManager(this));
        epgList.setAdapter(epgAdapter);
        epgList.setItemAnimator(null);
        xtream = repo.xtream();

        rowAdapter = new RowAdapter();
        panelList.setLayoutManager(new LinearLayoutManager(this));
        panelList.setAdapter(rowAdapter);
        panelList.setItemAnimator(null);
        panelTitle.setText(repo.currentTitle);
        panelSub.setText(list.size() + " kanala");
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (list == null || list.isEmpty()) return;
        initPlayer();
        play(index);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(playPending);
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(archiveSeekCommit);
        releasePlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        probeExec.shutdownNow();
        epgExec.shutdownNow();
        Health.get(this).save();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_INDEX, index);
    }

    // ------------------------------------------------------------------ plejer

    private void initPlayer() {
        if (player != null) return;
        httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(Repo.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 50000, 1500, 3000)
                .build();

        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
                .setLoadControl(loadControl)
                .build();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    buffering.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_READY) {
                    handler.removeCallbacks(watchdog);
                    buffering.setVisibility(View.GONE);
                    errorView.setVisibility(View.GONE);
                    sourceStatus.setVisibility(View.GONE);
                    if (currentSource != null && !archiveMode) {
                        Health.get(PlayerActivity.this).set(currentSource.url, Health.ONLINE);
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (archiveMode) {
                        // kraj emisije iz arhive -> nazad na uzivo
                        Toast.makeText(PlayerActivity.this, "Kraj emisije · nazad na uživo", Toast.LENGTH_SHORT).show();
                        play(playingIndex >= 0 ? playingIndex : index);
                        return;
                    }
                    // live stream ne bi trebao zavrsiti – pokusaj ponovo
                    if (player != null) { player.seekToDefaultPosition(); player.prepare(); }
                }
            }

            @Override public void onPlayerError(@NonNull PlaybackException error) {
                handleError(error);
            }
        });
        playerView.setPlayer(player);
        playerView.setKeepContentOnPlayerReset(false);
    }

    private void releasePlayer() {
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        handler.removeCallbacks(watchdog);
        playingIndex = -1;
        currentSource = null;
    }

    private void play(int i) {
        if (player == null || list.isEmpty()) return;
        handler.removeCallbacks(watchdog);
        index = i;
        Channel c = list.get(i);
        playingIndex = i;
        archiveMode = false;
        archiveProgram = null;
        archiveSeekTargetMin = -1;
        handler.removeCallbacks(archiveSeekCommit);
        pauseBadge.setVisibility(View.GONE);
        sources = orderedSources(c);
        sourceIdx = 0;
        loadEpg(c);
        errorView.setVisibility(View.GONE);
        sourceStatus.setVisibility(View.GONE);
        buffering.setVisibility(View.VISIBLE);
        showInfo(c);
        repo.addRecent(c);
        rowAdapter.notifyDataSetChanged();
        playSource();
    }

    /** Izvori kanala: prvo oni za koje se zna da rade, pa neprovjereni, pa neispravni (stabilno). */
    private List<Channel> orderedSources(Channel c) {
        final Health h = Health.get(this);
        List<Channel> out = new ArrayList<>(c.allSources());
        Collections.sort(out, new java.util.Comparator<Channel>() {
            @Override public int compare(Channel a, Channel b) { return h.rank(a) - h.rank(b); }
        });
        return out;
    }

    /** Pusti trenutni izvor (sources[sourceIdx]). */
    private void playSource() {
        if (player == null || sources.isEmpty()) return;
        handler.removeCallbacks(watchdog);
        final Channel s = sources.get(sourceIdx);
        currentSource = s;
        triedAsHls = false;
        ovNumber.setText(numberText());

        final int token = ++playToken;
        String known = mimeFromUrl(s.url);
        String cached;
        synchronized (PROBED) { cached = PROBED.get(s.url); }
        if (known != null || !s.url.toLowerCase(Locale.ROOT).startsWith("http")) {
            startStream(s, known);
        } else if (repo.isXtream()) {
            // Xtream: bez probne konekcije (server cesto dozvoljava samo 1 konekciju);
            // live kanali su MPEG-TS, a ako ne uspije, plejer sam proba kao HLS
            startStream(s, null);
        } else if (cached != null) {
            startStream(s, cached.isEmpty() ? null : cached);
        } else {
            // link bez ekstenzije (npr. play.php?stream=…): prvo provjeri sta server zaista vraca
            probeExec.execute(new Runnable() {
                @Override public void run() {
                    final String mime = StreamProbe.detect(s);
                    if (mime != null) synchronized (PROBED) { PROBED.put(s.url, mime); }
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (token != playToken || player == null) return;
                            startStream(s, mime == null || mime.isEmpty() ? null : mime);
                        }
                    });
                }
            });
        }
    }

    /** Trenutni izvor ne radi: zapamti to i probaj sljedeci; kad nema vise izvora, prikazi gresku. */
    private void onSourceFailed(int status, String reason) {
        handler.removeCallbacks(watchdog);
        if (currentSource != null && !archiveMode) Health.get(this).set(currentSource.url, status);
        if (player == null || playingIndex < 0) return;

        if (sourceIdx + 1 < sources.size()) {
            int failedNo = sourceIdx + 1;
            sourceIdx++;
            sourceStatus.setText(archiveMode
                    ? "Arhiva: probam drugi format…"
                    : "Izvor " + failedNo + " ne radi  ·  probam izvor " + (sourceIdx + 1)
                    + " od " + sources.size() + "…");
            sourceStatus.setVisibility(View.VISIBLE);
            errorView.setVisibility(View.GONE);
            buffering.setVisibility(View.VISIBLE);
            playSource();
            return;
        }

        player.stop();
        buffering.setVisibility(View.GONE);
        sourceStatus.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        if (archiveMode) {
            errorText.setText("Ova emisija se trenutno ne može pustiti iz arhive.\nPritisni Nazad za povratak na uživo.");
        } else {
            errorText.setText(sources.size() > 1
                    ? reason + "\nProbano svih " + sources.size() + " izvora ovog kanala."
                    : reason);
        }
        showInfo(list.get(playingIndex));
        rowAdapter.notifyItemChanged(playingIndex);
    }

    private String numberText() {
        String t = (index + 1) + " / " + list.size();
        if (!archiveMode && index == playingIndex && sources.size() > 1) {
            t += "   ·   izvor " + (sourceIdx + 1) + "/" + sources.size();
        }
        return t;
    }

    private static String mimeFromUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        int q = lower.indexOf('?');
        String path = q >= 0 ? lower.substring(0, q) : lower;
        if (path.endsWith(".m3u8") || path.endsWith(".m3u") || lower.contains(".m3u8") || lower.contains("format=m3u8")) return MimeTypes.APPLICATION_M3U8;
        if (path.endsWith(".mpd") || lower.contains(".mpd")) return MimeTypes.APPLICATION_MPD;
        if (path.endsWith(".ts") || path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".flv")
                || path.endsWith(".aac") || path.endsWith(".mp3")) return "";
        return null;
    }

    private void startStream(Channel c, String forcedMime) {
        Map<String, String> headers = new HashMap<>();
        if (c.referrer != null) {
            headers.put("Referer", c.referrer);
            String origin = originOf(c.referrer);
            if (origin != null) headers.put("Origin", origin);
        }
        httpFactory.setDefaultRequestProperties(headers);
        httpFactory.setUserAgent(c.userAgent != null ? c.userAgent : Repo.UA);

        MediaItem.Builder b = new MediaItem.Builder().setUri(c.url);
        if (forcedMime != null && !forcedMime.isEmpty()) b.setMimeType(forcedMime);

        player.stop();
        player.setMediaItem(b.build());
        player.prepare();
        player.setPlayWhenReady(true);
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, START_TIMEOUT_MS);
    }

    private static String originOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return null;
        }
    }

    private void handleError(PlaybackException error) {
        if (player == null || playingIndex < 0 || currentSource == null) return;
        Channel s = currentSource;
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        boolean formatProblem = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED;
        String probed;
        synchronized (PROBED) { probed = PROBED.get(s.url); }
        boolean alreadyHls = MimeTypes.APPLICATION_M3U8.equals(probed) || MimeTypes.APPLICATION_M3U8.equals(mimeFromUrl(s.url));
        if (!triedAsHls && formatProblem && !alreadyHls) {
            // mnogi linkovi bez ekstenzije su zapravo HLS
            triedAsHls = true;
            startStream(s, MimeTypes.APPLICATION_M3U8);
            return;
        }
        onSourceFailed(error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                ? Health.BLOCKED : Health.OFFLINE, describe(error));
    }

    private static String describe(PlaybackException e) {
        int code = e.errorCode;
        if (code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return "Server je odbio zahtjev. Stream je možda ugašen ili geo-blokiran.";
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) return "Ne mogu se povezati sa serverom.";
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) return "Server ne odgovara.";
        if (code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) return "Stream nije pronađen.";
        if (code >= 4000 && code < 5000) return "Format ovog streama nije podržan na ovom uređaju.";
        if (code >= 3000 && code < 4000) return "Stream nije ispravan ili je trenutno offline.";
        return "Stream trenutno nije dostupan (" + e.getErrorCodeName() + ").";
    }

    // ------------------------------------------------------------------ info traka

    private void showInfo(Channel c) {
        ovName.setText(c.name);
        ovMeta.setText(ChannelAdapter.meta(c) + (c.country != null ? "  ·  " + Labels.countryName(c.country) : ""));
        ovNumber.setText(numberText());
        boolean arch = archiveMode && index == playingIndex;
        ovLive.setText(arch ? "ARHIVA" : "UŽIVO");
        ovLive.setBackgroundResource(arch ? R.drawable.bg_archive : R.drawable.bg_live);
        ovHint.setText(arch
                ? "◀ ▶  premotaj      OK  pauza      Nazad  uživo      ▲ ▼  kanal"
                : xtream != null
                ? "▲ ▼  kanal      OK  lista kanala      ▶  program      drži OK  favorit"
                : "▲ ▼  kanal      OK  lista kanala      drži OK  favorit");
        updateEpgBar();
        String q = c.displayQuality();
        if (q != null) {
            ovQuality.setVisibility(View.VISIBLE);
            ovQuality.setText(q.toUpperCase(Locale.ROOT));
        } else {
            ovQuality.setVisibility(View.GONE);
        }
        ovFav.setVisibility(repo.isFavorite(c) ? View.VISIBLE : View.GONE);
        ovClock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        Logos.bind(ovLogo, ovInitials, c);
        overlay.animate().cancel();
        overlay.animate().alpha(1f).setDuration(200).start();
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, 5000);
    }

    private void zap(int delta) {
        if (list.size() < 2) return;
        index = (index + delta + list.size()) % list.size();
        showInfo(list.get(index));
        handler.removeCallbacks(playPending);
        handler.removeCallbacks(watchdog);
        playToken++;
        handler.postDelayed(playPending, 450);
    }

    private void toggleFavorite() {
        Channel c = list.get(index);
        boolean fav = repo.toggleFavorite(c);
        Toast.makeText(this, fav ? "★  Dodano u favorite" : "Uklonjeno iz favorita", Toast.LENGTH_SHORT).show();
        showInfo(c);
        rowAdapter.notifyItemChanged(index);
    }

    // ------------------------------------------------------------------ TV vodic (Xtream)

    private void loadEpg(Channel c) {
        epg = new ArrayList<>();
        epgStreamId = null;
        epgLoading = false;
        epgToken++;
        if (xtream == null) { updateEpgBar(); return; }
        final String sid = Xtream.streamId(c.url);
        if (sid == null) { updateEpgBar(); return; }
        epgStreamId = sid;
        List<Xtream.Program> cached = xtream.cachedEpg(sid);
        if (cached != null) {
            epg = cached;
            updateEpgBar();
            return;
        }
        epgLoading = true;
        final int tok = epgToken;
        final Xtream x = xtream;
        epgExec.execute(new Runnable() {
            @Override public void run() {
                List<Xtream.Program> l;
                try { l = x.fetchEpg(sid); } catch (Exception e) { l = null; }
                final List<Xtream.Program> res = l;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (tok != epgToken) return;
                        epgLoading = false;
                        epg = res != null ? res : new ArrayList<Xtream.Program>();
                        updateEpgBar();
                        if (epgPanelVisible()) fillEpgPanel();
                    }
                });
            }
        });
    }

    private Xtream.Program programAt(long t) {
        for (Xtream.Program p : epg) if (p.isNow(t)) return p;
        return null;
    }

    private Xtream.Program programAfter(Xtream.Program cur) {
        int i = epg.indexOf(cur);
        return i >= 0 && i + 1 < epg.size() ? epg.get(i + 1) : null;
    }

    /** Donja traka: sada / slijedi (uzivo) ili napredak emisije (arhiva). */
    private void updateEpgBar() {
        if (epgBox == null) return;
        if (index != playingIndex) {      // pregled drugog kanala (▲▼) – njegov program jos nije ucitan
            epgBox.setVisibility(View.GONE);
            return;
        }
        if (archiveMode && archiveProgram != null) {
            Xtream.Program p = archiveProgram;
            long total = Math.max(1, p.stop - p.start);
            long elapsed = archiveSeekTargetMin >= 0
                    ? archiveSeekTargetMin * 60000L
                    : archiveOffsetMin * 60000L + (player != null ? Math.max(0, player.getCurrentPosition()) : 0);
            elapsed = Math.max(0, Math.min(total, elapsed));
            epgBox.setVisibility(View.VISIBLE);
            epgNowTime.setText(hm.format(new Date(p.start + elapsed)));
            epgNowTitle.setText(p.title);
            epgLeft.setText("još " + Math.max(0, (total - elapsed) / 60000) + " min");
            epgProgress.setProgress((int) (elapsed * 1000 / total));
            epgNext.setText("Emisija " + hm.format(new Date(p.start)) + " – " + hm.format(new Date(p.stop))
                    + "   ·   arhiva");
            return;
        }
        long now = System.currentTimeMillis();
        Xtream.Program cur = programAt(now);
        if (cur == null) {
            epgBox.setVisibility(View.GONE);
            return;
        }
        epgBox.setVisibility(View.VISIBLE);
        epgNowTime.setText(hm.format(new Date(cur.start)) + " – " + hm.format(new Date(cur.stop)));
        epgNowTitle.setText(cur.title);
        epgLeft.setText("još " + Math.max(1, (cur.stop - now) / 60000) + " min");
        epgProgress.setProgress((int) ((now - cur.start) * 1000 / Math.max(1, cur.stop - cur.start)));
        Xtream.Program next = programAfter(cur);
        epgNext.setText(next != null ? "Zatim   " + hm.format(new Date(next.start)) + "   " + next.title : "");
    }

    private boolean epgPanelVisible() { return epgPanel.getVisibility() == View.VISIBLE; }

    private void showEpgPanel() {
        handler.removeCallbacks(hideOverlay);
        overlay.animate().alpha(0f).setDuration(200).start();
        Channel c = list.get(playingIndex >= 0 ? playingIndex : index);
        epgPanelTitle.setText("Program  ·  " + c.name);
        epgPanel.setVisibility(View.VISIBLE);
        epgPanel.setAlpha(0f);
        epgPanel.setTranslationX(Ui.dp(this, 40));
        epgPanel.animate().alpha(1f).translationX(0f).setDuration(220).start();
        fillEpgPanel();
    }

    private void hideEpgPanel() {
        epgPanel.animate().alpha(0f).translationX(Ui.dp(this, 40)).setDuration(180).withEndAction(new Runnable() {
            @Override public void run() { epgPanel.setVisibility(View.GONE); }
        }).start();
        View f = getCurrentFocus();
        if (f != null) f.clearFocus();
    }

    private void fillEpgPanel() {
        String msg = null;
        if (xtream == null) msg = "TV vodič i arhiva su dostupni uz Xtream prijavu.\nBesplatni kanali nemaju TV vodič.";
        else if (epgStreamId == null) msg = "Za ovaj kanal nema TV vodiča.";
        else if (epgLoading) msg = "Učitavam program…";
        else if (epg.isEmpty()) msg = "Server nema program za ovaj kanal.";
        if (msg != null) {
            epgAdapter.setItems(new ArrayList<Object>());
            epgEmpty.setText(msg);
            epgEmpty.setVisibility(View.VISIBLE);
            epgPanelSub.setText("");
            epgDesc.setText("");
            epgPanel.requestFocus();
            return;
        }
        epgEmpty.setVisibility(View.GONE);
        boolean anyArchive = false;
        for (Xtream.Program p : epg) if (p.archive) { anyArchive = true; break; }
        epgPanelSub.setText(anyArchive ? "Emisije sa oznakom ARHIVA možeš pogledati ponovo" : "Server nema arhivu za ovaj kanal");

        // redovi sa naslovima dana (Juče, Danas, Sutra…)
        List<Object> rows = new ArrayList<>();
        String lastDay = null;
        long now = System.currentTimeMillis();
        int focus = -1;
        Xtream.Program target = archiveMode && archiveProgram != null ? archiveProgram : programAt(now);
        for (Xtream.Program p : epg) {
            String day = dayLabel(p.start);
            if (!day.equals(lastDay)) { rows.add(day); lastDay = day; }
            if (target != null && p.start == target.start) focus = rows.size();
            rows.add(p);
        }
        if (focus < 0) {   // nema trenutne: prva buduca emisija
            for (int i = 0; i < rows.size(); i++) {
                Object o = rows.get(i);
                if (o instanceof Xtream.Program && ((Xtream.Program) o).stop > now) { focus = i; break; }
            }
        }
        if (focus < 0) focus = rows.size() - 1;
        epgAdapter.setItems(rows);
        final int f = focus;
        ((LinearLayoutManager) epgList.getLayoutManager()).scrollToPositionWithOffset(f, Ui.dp(this, 140));
        epgList.post(new Runnable() {
            @Override public void run() {
                RecyclerView.ViewHolder vh = epgList.findViewHolderForAdapterPosition(f);
                if (vh != null) vh.itemView.requestFocus();
                else epgList.requestFocus();
            }
        });
    }

    private String dayLabel(long t) {
        java.util.Calendar a = java.util.Calendar.getInstance();
        java.util.Calendar b = java.util.Calendar.getInstance();
        b.setTimeInMillis(t);
        int diff = (int) Math.round((startOfDay(b) - startOfDay(a)) / 86400000.0);
        if (diff == 0) return "DANAS";
        if (diff == -1) return "JUČE";
        if (diff == 1) return "SUTRA";
        String d = new SimpleDateFormat("EEEE d.M.", new Locale("bs")).format(new Date(t));
        return d.toUpperCase(new Locale("bs"));
    }

    private static long startOfDay(java.util.Calendar c) {
        java.util.Calendar x = (java.util.Calendar) c.clone();
        x.set(java.util.Calendar.HOUR_OF_DAY, 0);
        x.set(java.util.Calendar.MINUTE, 0);
        x.set(java.util.Calendar.SECOND, 0);
        x.set(java.util.Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }

    private void onProgramClicked(Xtream.Program p) {
        long now = System.currentTimeMillis();
        if (p.isFuture(now)) {
            Toast.makeText(this, "Počinje u " + hm.format(new Date(p.start)), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!p.archive) {
            if (p.isNow(now)) {          // trenutna emisija bez arhive -> uzivo
                hideEpgPanel();
                if (archiveMode) play(playingIndex);
                return;
            }
            Toast.makeText(this, "Ova emisija nije u arhivi.", Toast.LENGTH_SHORT).show();
            return;
        }
        hideEpgPanel();
        playArchive(p, 0);   // prosla emisija, ili trenutna "od pocetka"
    }

    // ------------------------------------------------------------------ arhiva

    private void playArchive(Xtream.Program p, int offsetMin) {
        if (xtream == null || epgStreamId == null || player == null || playingIndex < 0) return;
        archiveMode = true;
        archiveProgram = p;
        archiveOffsetMin = Math.max(0, Math.min(p.minutes() - 1, offsetMin));
        archiveSeekTargetMin = -1;
        Channel base = list.get(playingIndex);
        List<Channel> src = new ArrayList<>();
        for (String u : xtream.archiveUrls(p, epgStreamId, archiveOffsetMin)) {
            Channel s = new Channel();
            s.url = u;
            s.name = base.name;
            s.userAgent = base.userAgent;
            s.referrer = base.referrer;
            src.add(s);
        }
        handler.removeCallbacks(watchdog);
        sources = src;
        sourceIdx = 0;
        errorView.setVisibility(View.GONE);
        sourceStatus.setVisibility(View.GONE);
        pauseBadge.setVisibility(View.GONE);
        buffering.setVisibility(View.VISIBLE);
        playSource();
        showInfo(base);
    }

    /** Premotavanje u arhivi: dir = -1 nazad, +1 naprijed; repeat = koliko dugo se drzi tipka. */
    private void seekArchive(int dir, int repeat) {
        if (player == null || archiveProgram == null) return;
        boolean seekable = player.isCurrentMediaItemSeekable() && player.getDuration() != C.TIME_UNSET
                && player.getDuration() > 0;
        if (seekable && archiveSeekTargetMin < 0) {
            long step = repeat > 8 ? 120000 : repeat > 3 ? 60000 : 30000;
            long pos = player.getCurrentPosition() + dir * step;
            pos = Math.max(0, Math.min(player.getDuration() - 1000, pos));
            player.seekTo(pos);
        } else {
            // server ne dozvoljava premotavanje: kreni arhivu ispocetka od novog minuta (kad se pusti tipka)
            int cur = archiveSeekTargetMin >= 0 ? archiveSeekTargetMin
                    : archiveOffsetMin + (int) (Math.max(0, player.getCurrentPosition()) / 60000);
            int step = repeat > 3 ? 5 : 1;
            archiveSeekTargetMin = Math.max(0, Math.min(archiveProgram.minutes() - 1, cur + dir * step));
            handler.removeCallbacks(archiveSeekCommit);
            handler.postDelayed(archiveSeekCommit, 900);
        }
        showInfo(list.get(playingIndex));
    }

    private void togglePause() {
        if (player == null) return;
        boolean play = !player.getPlayWhenReady();
        player.setPlayWhenReady(play);
        pauseBadge.setVisibility(play ? View.GONE : View.VISIBLE);
        showInfo(list.get(playingIndex >= 0 ? playingIndex : index));
    }

    // ------------------------------------------------------------------ lista kanala

    private boolean panelVisible() { return panel.getVisibility() == View.VISIBLE; }

    private void showPanel() {
        handler.removeCallbacks(hideOverlay);
        overlay.animate().alpha(0f).setDuration(200).start();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setTranslationX(-Ui.dp(this, 40));
        panel.animate().alpha(1f).translationX(0f).setDuration(220).start();
        rowAdapter.notifyDataSetChanged();
        final LinearLayoutManager lm = (LinearLayoutManager) panelList.getLayoutManager();
        lm.scrollToPositionWithOffset(index, Ui.dp(this, 160));
        panelList.post(new Runnable() {
            @Override public void run() {
                RecyclerView.ViewHolder vh = panelList.findViewHolderForAdapterPosition(index);
                if (vh != null) vh.itemView.requestFocus();
                else panelList.requestFocus();
            }
        });
    }

    private void hidePanel() {
        panel.animate().alpha(0f).translationX(-Ui.dp(this, 40)).setDuration(180).withEndAction(new Runnable() {
            @Override public void run() { panel.setVisibility(View.GONE); }
        }).start();
        View f = getCurrentFocus();
        if (f != null) f.clearFocus();
    }

    // ------------------------------------------------------------------ daljinski

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        // ---- Nazad: zatvori panel -> izadji iz arhive (uzivo) -> izadji iz plejera
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (panelVisible()) {
                if (!down) hidePanel();
                return true;
            }
            if (epgPanelVisible()) {
                if (!down) hideEpgPanel();
                return true;
            }
            if (archiveMode) {
                if (!down) {
                    Toast.makeText(this, "Nazad na uživo", Toast.LENGTH_SHORT).show();
                    play(playingIndex >= 0 ? playingIndex : index);
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        // ---- lista kanala (lijevo) je otvorena
        if (panelVisible()) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (!down) hidePanel();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        // ---- program (desno) je otvoren: gore/dole/OK idu na listu emisija
        if (epgPanelVisible()) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (!down) hideEpgPanel();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            return super.dispatchKeyEvent(e);
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (down) zap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down) zap(+1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) {
                    if (archiveMode) seekArchive(-1, e.getRepeatCount());
                    else if (e.getRepeatCount() == 0) showInfo(list.get(index));
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) {
                    if (archiveMode) seekArchive(+1, e.getRepeatCount());
                    else if (e.getRepeatCount() == 0 && code == KeyEvent.KEYCODE_DPAD_RIGHT) showEpgPanel();
                }
                return true;
            case KeyEvent.KEYCODE_INFO:
                if (down && e.getRepeatCount() == 0) showInfo(list.get(index));
                return true;
            case KeyEvent.KEYCODE_GUIDE:
                if (down && e.getRepeatCount() == 0) showEpgPanel();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BOOKMARK:
                if (down && e.getRepeatCount() == 0) toggleFavorite();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        longPressHandled = false;
                    } else if (!longPressHandled && e.getEventTime() - e.getDownTime() > 650) {
                        longPressHandled = true;
                        toggleFavorite();
                    }
                } else {
                    if (okDown && !longPressHandled) {
                        if (archiveMode) togglePause();   // u arhivi OK = pauza / nastavi
                        else showPanel();
                    }
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && e.getRepeatCount() == 0 && player != null) {
                    if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) player.pause();
                    else if (code == KeyEvent.KEYCODE_MEDIA_PLAY) player.play();
                    else player.setPlayWhenReady(!player.getPlayWhenReady());
                    pauseBadge.setVisibility(player.getPlayWhenReady() ? View.GONE : View.VISIBLE);
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    // ------------------------------------------------------------------ adapter za listu

    private final class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {

        @Override public int getItemCount() { return list.size(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false);
            Ui.applyFonts(v);
            final VH vh = new VH(v);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    int p = vh.getBindingAdapterPosition();
                    if (p == RecyclerView.NO_POSITION) return;
                    hidePanel();
                    handler.removeCallbacks(playPending);
                    if (p != playingIndex || archiveMode) play(p);
                    else showInfo(list.get(p));
                }
            });
            v.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View view) {
                    int p = vh.getBindingAdapterPosition();
                    if (p == RecyclerView.NO_POSITION) return true;
                    Channel c = list.get(p);
                    boolean fav = repo.toggleFavorite(c);
                    Toast.makeText(PlayerActivity.this, fav ? "★  " + c.name + " dodan u favorite" : c.name + " uklonjen iz favorita", Toast.LENGTH_SHORT).show();
                    notifyItemChanged(p);
                    return true;
                }
            });
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull VH vh, int position) {
            Channel c = list.get(position);
            vh.number.setText(String.valueOf(position + 1));
            vh.name.setText(repo.isFavorite(c) ? "★ " + c.name : c.name);
            String q = c.displayQuality();
            vh.meta.setText(ChannelAdapter.meta(c) + (q != null ? "  ·  " + q : ""));
            vh.playing.setVisibility(position == playingIndex ? View.VISIBLE : View.GONE);
            Logos.bind(vh.logo, vh.initials, c);
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView number, name, meta, initials;
            final ImageView logo, playing;
            VH(View v) {
                super(v);
                number = v.findViewById(R.id.number);
                name = v.findViewById(R.id.name);
                meta = v.findViewById(R.id.meta);
                initials = v.findViewById(R.id.initials);
                logo = v.findViewById(R.id.logo);
                playing = v.findViewById(R.id.playing);
            }
        }
    }

    // ------------------------------------------------------------------ adapter za program

    private final class EpgAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Object> rows = new ArrayList<>();

        void setItems(List<Object> l) {
            rows.clear();
            rows.addAll(l);
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return rows.size(); }

        @Override public int getItemViewType(int position) { return rows.get(position) instanceof String ? 0 : 1; }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == 0) {
                View v = inf.inflate(R.layout.item_epg_header, parent, false);
                Ui.applyFonts(v);
                return new RecyclerView.ViewHolder(v) { };
            }
            View v = inf.inflate(R.layout.item_epg, parent, false);
            Ui.applyFonts(v);
            final ProgVH vh = new ProgVH(v);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    int p = vh.getBindingAdapterPosition();
                    if (p == RecyclerView.NO_POSITION || !(rows.get(p) instanceof Xtream.Program)) return;
                    onProgramClicked((Xtream.Program) rows.get(p));
                }
            });
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View view, boolean hasFocus) {
                    if (!hasFocus) return;
                    int p = vh.getBindingAdapterPosition();
                    if (p == RecyclerView.NO_POSITION || !(rows.get(p) instanceof Xtream.Program)) return;
                    Xtream.Program pr = (Xtream.Program) rows.get(p);
                    String when = hm.format(new Date(pr.start)) + " – " + hm.format(new Date(pr.stop));
                    epgDesc.setText(when + (pr.desc.isEmpty() ? "" : "\n" + pr.desc));
                }
            });
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object o = rows.get(position);
            if (o instanceof String) {
                ((TextView) holder.itemView).setText((String) o);
                return;
            }
            Xtream.Program p = (Xtream.Program) o;
            ProgVH vh = (ProgVH) holder;
            long now = System.currentTimeMillis();
            vh.time.setText(hm.format(new Date(p.start)));
            vh.title.setText(p.title);
            boolean playingThis = archiveMode && archiveProgram != null && archiveProgram.start == p.start;
            if (playingThis) {
                vh.badge.setVisibility(View.VISIBLE);
                vh.badge.setText("▶ GLEDAŠ");
                vh.badge.setBackgroundResource(R.drawable.bg_archive);
            } else if (p.isNow(now)) {
                vh.badge.setVisibility(View.VISIBLE);
                vh.badge.setText(p.archive ? "UŽIVO · OD POČETKA" : "UŽIVO");
                vh.badge.setBackgroundResource(R.drawable.bg_live);
            } else if (p.isPast(now) && p.archive) {
                vh.badge.setVisibility(View.VISIBLE);
                vh.badge.setText("↺ ARHIVA");
                vh.badge.setBackgroundResource(R.drawable.bg_archive);
            } else {
                vh.badge.setVisibility(View.GONE);
            }
            // prosle emisije bez arhive su prigusene
            vh.itemView.setAlpha(p.isPast(now) && !p.archive ? 0.5f : 1f);
        }

        final class ProgVH extends RecyclerView.ViewHolder {
            final TextView time, title, badge;
            ProgVH(View v) {
                super(v);
                time = v.findViewById(R.id.epg_time);
                title = v.findViewById(R.id.epg_title);
                badge = v.findViewById(R.id.epg_badge);
            }
        }
    }
}
