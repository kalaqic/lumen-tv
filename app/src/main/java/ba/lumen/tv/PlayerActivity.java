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
        releasePlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        probeExec.shutdownNow();
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
                    buffering.setVisibility(View.GONE);
                    errorView.setVisibility(View.GONE);
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
        playingIndex = -1;
    }

    private void play(int i) {
        if (player == null || list.isEmpty()) return;
        index = i;
        Channel c = list.get(i);
        playingIndex = i;
        triedAsHls = false;
        errorView.setVisibility(View.GONE);
        buffering.setVisibility(View.VISIBLE);
        showInfo(c);
        repo.addRecent(c);
        rowAdapter.notifyDataSetChanged();

        final int token = ++playToken;
        String known = mimeFromUrl(c.url);
        String cached;
        synchronized (PROBED) { cached = PROBED.get(c.url); }
        if (known != null || !c.url.toLowerCase(Locale.ROOT).startsWith("http")) {
            startStream(c, known);
        } else if (cached != null) {
            startStream(c, cached.isEmpty() ? null : cached);
        } else {
            // link bez ekstenzije (npr. play.php?stream=…): prvo provjeri sta server zaista vraca
            final Channel ch = c;
            probeExec.execute(new Runnable() {
                @Override public void run() {
                    final String mime = StreamProbe.detect(ch);
                    if (mime != null) synchronized (PROBED) { PROBED.put(ch.url, mime); }
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (token != playToken || player == null) return;
                            startStream(ch, mime == null || mime.isEmpty() ? null : mime);
                        }
                    });
                }
            });
        }
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
        if (player == null || playingIndex < 0) return;
        Channel c = list.get(playingIndex);
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        boolean formatProblem = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED;
        String probed;
        synchronized (PROBED) { probed = PROBED.get(c.url); }
        boolean alreadyHls = MimeTypes.APPLICATION_M3U8.equals(probed) || MimeTypes.APPLICATION_M3U8.equals(mimeFromUrl(c.url));
        if (!triedAsHls && formatProblem && !alreadyHls) {
            // mnogi linkovi bez ekstenzije su zapravo HLS
            triedAsHls = true;
            startStream(c, MimeTypes.APPLICATION_M3U8);
            return;
        }
        buffering.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        errorText.setText(describe(error));
        showInfo(c);
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
        ovNumber.setText((index + 1) + " / " + list.size());
        if (c.quality != null) {
            ovQuality.setVisibility(View.VISIBLE);
            ovQuality.setText(c.quality.toUpperCase(Locale.ROOT));
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
            vh.meta.setText(ChannelAdapter.meta(c) + (c.quality != null ? "  ·  " + c.quality : ""));
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
