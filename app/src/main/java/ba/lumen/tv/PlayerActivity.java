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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(playPending);
        handler.removeCallbacks(watchdog);
        releasePlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        probeExec.shutdownNow();
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
                    if (currentSource != null) {
                        Health.get(PlayerActivity.this).set(currentSource.url, Health.ONLINE);
                    }
                } else if (state == Player.STATE_ENDED) {
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
        sources = orderedSources(c);
        sourceIdx = 0;
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
        if (currentSource != null) Health.get(this).set(currentSource.url, status);
        if (player == null || playingIndex < 0) return;

        if (sourceIdx + 1 < sources.size()) {
            int failedNo = sourceIdx + 1;
            sourceIdx++;
            sourceStatus.setText("Izvor " + failedNo + " ne radi  ·  probam izvor " + (sourceIdx + 1)
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
        errorText.setText(sources.size() > 1
                ? reason + "\nProbano svih " + sources.size() + " izvora ovog kanala."
                : reason);
        showInfo(list.get(playingIndex));
        rowAdapter.notifyItemChanged(playingIndex);
    }

    private String numberText() {
        String t = (index + 1) + " / " + list.size();
        if (index == playingIndex && sources.size() > 1) {
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

        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (panelVisible()) {
                if (!down) hidePanel();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        if (panelVisible()) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (!down) hidePanel();
                return true;
            }
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
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_INFO:
                if (down && e.getRepeatCount() == 0) showInfo(list.get(index));
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
                    if (okDown && !longPressHandled) showPanel();
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
                    if (p != playingIndex) play(p);
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
}
