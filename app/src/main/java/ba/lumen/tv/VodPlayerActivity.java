package ba.lumen.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gledanje filma ili epizode: nastavlja gdje je stalo, premotavanje strelicama,
 * titlovi (ugradjeni ili sa interneta) i izbor zvuka, sljedeca epizoda automatski.
 */
public class VodPlayerActivity extends Activity {

    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_FROM = "from";
    public static final String EXTRA_SEASON = "season";
    public static final String EXTRA_EPISODE = "episode";

    private static final long START_TIMEOUT_MS = 25000;
    private static final long OVERLAY_MS = 4000;

    private Vod vod;
    private Subs subs;
    private SharedPreferences prefs;
    private Vod.Item item;
    private Vod.Details details;
    private Vod.Episode episode;          // null = film
    private String posKey;                // kljuc za "nastavi gledati"

    private ExoPlayer player;
    private PlayerView playerView;
    private DefaultHttpDataSource.Factory http;
    private View ovTop, ovBottom, panel, errorView, buffering, pauseBadge, panelLoading;
    private TextView ovTitle, ovSub, ovPos, ovDur, message, errorText, panelTitle, panelSub;
    private ProgressBar ovProgress;
    private RecyclerView panelList;
    private PanelAdapter panelAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private List<String> urls = new ArrayList<>();
    private int urlIdx;
    private long startPos;
    private boolean everReady;
    private int reconnects;
    private long seekTarget = -1;
    /** Titlovi skinuti sa interneta za ovaj naslov. */
    private final List<ExtSub> extSubs = new ArrayList<>();
    /** Titl koji treba izabrati cim plejer vidi nove trake. */
    private String pendingSubLabel;
    private int panelMode;                // 0 = titlovi i zvuk, 1 = rezultati pretrage
    private List<Subs.Result> searchResults = new ArrayList<>();

    static final class ExtSub {
        final File file;
        final String language, label;
        ExtSub(File f, String language, String label) { this.file = f; this.language = language; this.label = label; }
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (player != null && !everReady) nextSourceOrError("Server ne šalje sliku.");
        }
    };
    private final Runnable hideOverlay = new Runnable() {
        @Override public void run() {
            if (player != null && !player.isPlaying() && player.getPlaybackState() == Player.STATE_READY) return;
            ovTop.setVisibility(View.GONE);
            ovBottom.setVisibility(View.GONE);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateTime();
            handler.postDelayed(this, 500);
        }
    };
    private final Runnable saveTick = new Runnable() {
        @Override public void run() {
            savePosition();
            handler.postDelayed(this, 10000);
        }
    };
    private final Runnable commitSeek = new Runnable() {
        @Override public void run() {
            if (player != null && seekTarget >= 0) player.seekTo(seekTarget);
            seekTarget = -1;
        }
    };
    private final Runnable hideMessage = new Runnable() {
        @Override public void run() { message.setVisibility(View.GONE); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vod = Vod.get(this);
        subs = new Subs(this);
        prefs = getSharedPreferences("lumen_vod", Context.MODE_PRIVATE);
        int kind = getIntent().getIntExtra(EXTRA_KIND, Vod.MOVIE);
        item = vod.find(kind, getIntent().getStringExtra(EXTRA_KEY));
        if (item == null) { finish(); return; }
        if (kind == Vod.SERIES) {
            details = vod.cachedDetails(item);
            episode = findEpisode(getIntent().getIntExtra(EXTRA_SEASON, -1), getIntent().getIntExtra(EXTRA_EPISODE, -1));
            if (episode == null) { finish(); return; }
        } else {
            details = vod.cachedDetails(item);
        }
        startPos = getIntent().getLongExtra(EXTRA_FROM, 0);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.init(this);
        setContentView(R.layout.activity_vod_player);
        Ui.applyFonts(getWindow().getDecorView());
        playerView = findViewById(R.id.player_view);
        ovTop = findViewById(R.id.ov_top);
        ovBottom = findViewById(R.id.ov_bottom);
        ovTitle = findViewById(R.id.ov_title);
        ovSub = findViewById(R.id.ov_sub);
        ovPos = findViewById(R.id.ov_pos);
        ovDur = findViewById(R.id.ov_dur);
        ovProgress = findViewById(R.id.ov_progress);
        message = findViewById(R.id.message);
        panel = findViewById(R.id.panel);
        panelTitle = findViewById(R.id.panel_title);
        panelSub = findViewById(R.id.panel_sub);
        panelList = findViewById(R.id.panel_list);
        panelLoading = findViewById(R.id.panel_loading);
        errorView = findViewById(R.id.error);
        errorText = findViewById(R.id.error_text);
        buffering = findViewById(R.id.buffering);
        pauseBadge = findViewById(R.id.pause_badge);

        panelAdapter = new PanelAdapter();
        panelList.setLayoutManager(new LinearLayoutManager(this));
        panelList.setAdapter(panelAdapter);
        panelList.setItemAnimator(null);

        styleSubtitles();
        prepareSource();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (item == null) return;
        if (player == null) initPlayer();
        handler.post(tick);
        handler.postDelayed(saveTick, 10000);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            savePosition();
            startPos = player.getCurrentPosition();
            player.release();
            player = null;
        }
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.shutdownNow();
    }

    // ------------------------------------------------------------------ izvor

    private Vod.Episode findEpisode(int season, int num) {
        if (details == null) return null;
        for (Vod.Season s : details.seasons) {
            for (Vod.Episode e : s.episodes) if (e.season == season && e.num == num) return e;
        }
        return null;
    }

    /** Linkovi redom (film moze imati vise izvora iz razlicitih prijava). */
    private void prepareSource() {
        urls.clear();
        urlIdx = 0;
        everReady = false;
        reconnects = 0;
        if (episode == null) {
            posKey = Vod.movieKey(item);
            for (Vod.Src s : item.sources) {
                String u = vod.movieUrl(s);
                if (u != null) urls.add(u);
            }
            ovTitle.setText(item.title);
            ovSub.setText(item.meta());
        } else {
            posKey = Vod.episodeKey(item, episode);
            String u = vod.episodeUrl(episode);
            if (u != null) urls.add(u);
            ovTitle.setText(item.title);
            ovSub.setText("S" + episode.season + " E" + episode.num
                    + (episode.title != null ? "  ·  " + episode.title : ""));
        }
        vod.markWatched(item, episode);
        // titl koji je vec skinut za ovaj naslov
        extSubs.clear();
        String saved = prefs.getString("sub:" + posKey, null);
        if (saved != null) {
            String[] p = saved.split("\\|", 3);
            File f = new File(p[0]);
            if (f.exists() && p.length == 3) extSubs.add(new ExtSub(f, p[1], p[2]));
        }
    }

    private void initPlayer() {
        http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Repo.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000);
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(30000, 120000, 4000, 8000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .setLoadControl(load)
                .build();
        // titlovi na nasim jezicima se ukljuce sami (osim ako ih je korisnik iskljucio)
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredTextLanguages("bs", "hr", "sr", "sh")
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, prefs.getBoolean("subs_off", false))
                .build());
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                buffering.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) {
                    if (!everReady) {
                        everReady = true;
                        handler.removeCallbacks(watchdog);
                        showOverlay();
                    }
                    errorView.setVisibility(View.GONE);
                } else if (state == Player.STATE_ENDED) {
                    onEnded();
                }
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                pauseBadge.setVisibility(!isPlaying && player != null && !player.getPlayWhenReady()
                        && player.getPlaybackState() == Player.STATE_READY ? View.VISIBLE : View.GONE);
            }

            @Override public void onTracksChanged(@NonNull Tracks tracks) {
                if (pendingSubLabel != null) {
                    if (selectTextByLabel(pendingSubLabel)) pendingSubLabel = null;
                }
                if (panel.getVisibility() == View.VISIBLE && panelMode == 0) fillTracksPanel();
            }

            @Override public void onPlayerError(@NonNull PlaybackException error) {
                handleError(error);
            }
        });
        playerView.setPlayer(player);
        play(startPos);
    }

    private void play(long from) {
        if (player == null) return;
        if (urls.isEmpty()) {
            showError("Ova prijava više nije sačuvana.");
            return;
        }
        MediaItem.Builder b = new MediaItem.Builder().setUri(urls.get(urlIdx));
        List<MediaItem.SubtitleConfiguration> sc = new ArrayList<>();
        for (ExtSub s : extSubs) {
            sc.add(new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s.file))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(s.language)
                    .setLabel(s.label)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build());
        }
        if (!sc.isEmpty()) b.setSubtitleConfigurations(sc);
        player.setMediaItem(b.build(), Math.max(0, from));
        player.prepare();
        player.setPlayWhenReady(true);
        handler.removeCallbacks(watchdog);
        if (!everReady) handler.postDelayed(watchdog, START_TIMEOUT_MS);
    }

    private void handleError(PlaybackException error) {
        if (player == null) return;
        long pos = player.getCurrentPosition();
        boolean io = error.errorCode >= 2000 && error.errorCode < 3000;
        if (everReady && io && reconnects < 3) {
            // veza pukla usred filma: nastavi od istog mjesta
            reconnects++;
            showMessage("Veza prekinuta · nastavljam…");
            final long p = pos;
            handler.postDelayed(new Runnable() {
                @Override public void run() { if (player != null) play(p); }
            }, 1500);
            return;
        }
        if (!everReady) {
            nextSourceOrError(error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                    ? "Server je odbio zahtjev." : error.errorCode >= 4000 && error.errorCode < 5000
                    ? "Format ovog fajla nije podržan na ovom uređaju." : "Ne mogu se povezati sa serverom.");
            return;
        }
        showError("Reprodukcija je prekinuta. Pritisni OK da pokušaš ponovo.");
    }

    private void nextSourceOrError(String reason) {
        handler.removeCallbacks(watchdog);
        if (urlIdx + 1 < urls.size()) {
            urlIdx++;
            showMessage("Izvor ne radi · probam drugi (" + (urlIdx + 1) + "/" + urls.size() + ")");
            play(startPos);
        } else {
            showError(reason);
        }
    }

    private void showError(String text) {
        errorText.setText(text);
        errorView.setVisibility(View.VISIBLE);
        buffering.setVisibility(View.GONE);
    }

    private void onEnded() {
        if (player != null) {
            long d = player.getDuration();
            if (d > 0) vod.savePosition(posKey, d, d);
        }
        Vod.Episode next = episode == null ? null : nextEpisode(episode);
        if (next != null) {
            showMessage("Sljedeća epizoda: S" + next.season + " E" + next.num);
            episode = next;
            startPos = vod.resumeFrom(Vod.episodeKey(item, next));
            prepareSource();
            play(startPos);
        } else {
            finish();
        }
    }

    private Vod.Episode nextEpisode(Vod.Episode cur) {
        if (details == null) return null;
        boolean found = false;
        for (Vod.Season s : details.seasons) {
            for (Vod.Episode e : s.episodes) {
                if (found) return e;
                if (e == cur) found = true;
            }
        }
        return null;
    }

    private void savePosition() {
        if (player == null || !everReady) return;
        long d = player.getDuration();
        long p = player.getCurrentPosition();
        if (d == C.TIME_UNSET || d <= 0) return;
        vod.savePosition(posKey, p, d);
    }

    // ------------------------------------------------------------------ prikaz

    private void showOverlay() {
        ovTop.setVisibility(View.VISIBLE);
        ovBottom.setVisibility(View.VISIBLE);
        updateTime();
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, OVERLAY_MS);
    }

    private void updateTime() {
        if (player == null || ovBottom.getVisibility() != View.VISIBLE) return;
        long d = player.getDuration();
        long p = seekTarget >= 0 ? seekTarget : player.getCurrentPosition();
        ovPos.setText(time(p));
        ovDur.setText(d > 0 ? time(d) : "");
        if (d > 0) {
            ovProgress.setProgress((int) (p * 1000 / d));
            ovProgress.setSecondaryProgress((int) (player.getBufferedPosition() * 1000 / d));
        }
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideMessage);
        handler.postDelayed(hideMessage, 3500);
    }

    /** 1:05:09 ili 42:10 */
    static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    private void styleSubtitles() {
        SubtitleView sv = playerView.getSubtitleView();
        if (sv == null) return;
        // bijela slova sa crnim obrubom, malo veca nego obicno (gleda se sa kauca)
        sv.setStyle(new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Ui.font("semibold")));
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f);
        sv.setApplyEmbeddedStyles(false);
    }

    // ------------------------------------------------------------------ tipke

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);

        if (panel.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (down) {
                    if (panelMode == 1) fillTracksPanel(); else hidePanel();
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (player == null) return super.dispatchKeyEvent(e);

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (down && e.getRepeatCount() == 0) {
                    if (errorView.getVisibility() == View.VISIBLE) {
                        // ponovo pokusaj od mjesta gdje je stalo
                        errorView.setVisibility(View.GONE);
                        reconnects = 0;
                        play(Math.max(startPos, player.getCurrentPosition()));
                    } else {
                        player.setPlayWhenReady(!player.getPlayWhenReady());
                        showOverlay();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down) { player.setPlayWhenReady(true); showOverlay(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down) { player.setPlayWhenReady(false); showOverlay(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) seek(-1, e.getRepeatCount(), code == KeyEvent.KEYCODE_MEDIA_REWIND);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) seek(+1, e.getRepeatCount(), code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_CAPTIONS:
                if (down && e.getRepeatCount() == 0) showPanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_INFO:
                if (down) showOverlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (down && episode != null) {
                    Vod.Episode n = nextEpisode(episode);
                    if (n != null) {
                        savePosition();
                        episode = n;
                        startPos = vod.resumeFrom(Vod.episodeKey(item, n));
                        prepareSource();
                        play(startPos);
                        showOverlay();
                    }
                }
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Premotavanje: 10 s, a kad se drzi tipka sve brze (30 s, pa 60 s). */
    private void seek(int dir, int repeat, boolean bigStep) {
        long d = player.getDuration();
        long base = seekTarget >= 0 ? seekTarget : player.getCurrentPosition();
        long step = bigStep ? 30000 : repeat > 20 ? 60000 : repeat > 6 ? 30000 : 10000;
        long t = base + dir * step;
        if (d > 0) t = Math.min(t, d - 1000);
        seekTarget = Math.max(0, t);
        showOverlay();
        handler.removeCallbacks(commitSeek);
        handler.postDelayed(commitSeek, 450);
    }

    @Override
    public void onBackPressed() {
        if (panel.getVisibility() == View.VISIBLE) {
            if (panelMode == 1) fillTracksPanel(); else hidePanel();
            return;
        }
        if (seekTarget >= 0) {
            handler.removeCallbacks(commitSeek);
            seekTarget = -1;
            updateTime();
            return;
        }
        if (ovBottom.getVisibility() == View.VISIBLE) {
            handler.removeCallbacks(hideOverlay);
            ovTop.setVisibility(View.GONE);
            ovBottom.setVisibility(View.GONE);
            return;
        }
        savePosition();
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ titlovi i zvuk

    private static final int ROW_HEADER = 0, ROW_SUB_OFF = 1, ROW_TEXT = 2, ROW_AUDIO = 3, ROW_SEARCH = 4,
            ROW_RESULT = 5, ROW_SETUP = 6, ROW_INFO = 7;

    static final class Row {
        final int type;
        final String title, sub;
        boolean checked;
        Tracks.Group group;
        int index;
        Subs.Result result;
        Row(int type, String title, String sub) { this.type = type; this.title = title; this.sub = sub; }
    }

    private void showPanel() {
        handler.removeCallbacks(hideOverlay);
        ovTop.setVisibility(View.GONE);
        ovBottom.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        fillTracksPanel();
    }

    private void hidePanel() {
        panel.setVisibility(View.GONE);
        panelLoading.setVisibility(View.GONE);
    }

    private void fillTracksPanel() {
        panelMode = 0;
        panelTitle.setText("Titlovi i zvuk");
        panelSub.setText("");
        panelLoading.setVisibility(View.GONE);
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(ROW_HEADER, "TITLOVI", null));
        boolean textOn = false;
        List<Row> textRows = new ArrayList<>(), audioRows = new ArrayList<>();
        if (player != null) {
            for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
                int type = g.getType();
                if (type != C.TRACK_TYPE_TEXT && type != C.TRACK_TYPE_AUDIO) continue;
                for (int i = 0; i < g.length; i++) {
                    if (!g.isTrackSupported(i)) continue;
                    Format f = g.getTrackFormat(i);
                    Row r = new Row(type == C.TRACK_TYPE_TEXT ? ROW_TEXT : ROW_AUDIO, trackName(f, type), trackSub(f, type));
                    r.group = g;
                    r.index = i;
                    r.checked = g.isTrackSelected(i);
                    if (type == C.TRACK_TYPE_TEXT) {
                        if (r.checked) textOn = true;
                        textRows.add(r);
                    } else {
                        audioRows.add(r);
                    }
                }
            }
        }
        Row off = new Row(ROW_SUB_OFF, "Isključeni", null);
        off.checked = !textOn;
        rows.add(off);
        rows.addAll(textRows);
        rows.add(subs.configured()
                ? new Row(ROW_SEARCH, "Traži titlove na internetu…", "bosanski, hrvatski, srpski, engleski")
                : new Row(ROW_SETUP, "Titlovi sa interneta", "OpenSubtitles ključ upiši na sajtu " + Cloud.get(this).siteLabel()));
        if (audioRows.size() > 1) {
            rows.add(new Row(ROW_HEADER, "ZVUK", null));
            rows.addAll(audioRows);
        }
        panelAdapter.setRows(rows);
        focusFirstRow();
    }

    private void focusFirstRow() {
        panelList.post(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < panelAdapter.getItemCount(); i++) {
                    if (panelAdapter.rows.get(i).type != ROW_HEADER && panelAdapter.rows.get(i).type != ROW_INFO) {
                        panelList.scrollToPosition(i);
                        final int pos = i;
                        panelList.post(new Runnable() {
                            @Override public void run() {
                                RecyclerView.ViewHolder vh = panelList.findViewHolderForAdapterPosition(pos);
                                if (vh != null) vh.itemView.requestFocus();
                            }
                        });
                        return;
                    }
                }
            }
        });
    }

    private static String trackName(Format f, int type) {
        String lang = f.language;
        String name = f.label;
        if (name == null || name.isEmpty()) {
            if (lang != null && !lang.equals("und")) {
                String n = Subs.languageName(lang);
                name = n.equals(lang) ? new Locale(lang).getDisplayLanguage(new Locale("bs")) : n;
            }
        }
        if (name == null || name.isEmpty()) name = type == C.TRACK_TYPE_TEXT ? "Titl" : "Zvuk";
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private static String trackSub(Format f, int type) {
        if (type == C.TRACK_TYPE_AUDIO) {
            String ch = f.channelCount == 6 ? "5.1" : f.channelCount == 8 ? "7.1" : f.channelCount == 2 ? "stereo"
                    : f.channelCount == 1 ? "mono" : null;
            String codec = f.sampleMimeType == null ? null : f.sampleMimeType.replace("audio/", "").toUpperCase(Locale.ROOT);
            if (ch == null) return codec;
            return codec == null ? ch : ch + " · " + codec;
        }
        return null;
    }

    private void onRowClick(Row r) {
        if (player == null) return;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
        switch (r.type) {
            case ROW_SUB_OFF:
                player.setTrackSelectionParameters(b.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                prefs.edit().putBoolean("subs_off", true).apply();
                hidePanel();
                break;
            case ROW_TEXT:
                player.setTrackSelectionParameters(b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(new TrackSelectionOverride(r.group.getMediaTrackGroup(), r.index)).build());
                prefs.edit().putBoolean("subs_off", false).apply();
                hidePanel();
                break;
            case ROW_AUDIO:
                player.setTrackSelectionParameters(b.setOverrideForType(
                        new TrackSelectionOverride(r.group.getMediaTrackGroup(), r.index)).build());
                hidePanel();
                break;
            case ROW_SETUP:
                // ključ se upisuje na sajtu (na TV-u se ništa ne kuca)
                hidePanel();
                showMessage("Na sajtu " + Cloud.get(this).siteLabel() + " u dijelu „Titlovi“ upiši OpenSubtitles ključ");
                break;
            case ROW_SEARCH:
                searchSubs();
                break;
            case ROW_RESULT:
                downloadSub(r.result);
                break;
        }
    }

    private void searchSubs() {
        panelMode = 1;
        panelTitle.setText("Titlovi sa interneta");
        panelSub.setText("Tražim…");
        panelAdapter.setRows(new ArrayList<Row>());
        panelLoading.setVisibility(View.VISIBLE);
        final String title = item.title;
        final String year = item.year;
        final String tmdb = details != null ? details.tmdbId : null;
        final int season = episode == null ? 0 : episode.season;
        final int num = episode == null ? 0 : episode.num;
        net.execute(new Runnable() {
            @Override public void run() {
                List<Subs.Result> res;
                String err = null;
                try {
                    res = subs.search(title, year, tmdb, season, num);
                    if (res.isEmpty() && tmdb != null) res = subs.search(title, year, null, season, num);
                } catch (Exception e) {
                    res = new ArrayList<>();
                    err = e.getMessage();
                }
                final List<Subs.Result> fr = res;
                final String fe = err;
                handler.post(new Runnable() {
                    @Override public void run() { showResults(fr, fe); }
                });
            }
        });
    }

    private void showResults(List<Subs.Result> res, String err) {
        if (panelMode != 1 || isFinishing()) return;
        panelLoading.setVisibility(View.GONE);
        searchResults = res;
        List<Row> rows = new ArrayList<>();
        if (err != null) {
            panelSub.setText("Greška");
            rows.add(new Row(ROW_INFO, err, null));
            rows.add(new Row(ROW_SETUP, "Podešavanje titlova", "Ključ i nalog mijenjaš na sajtu " + Cloud.get(this).siteLabel()));
        } else if (res.isEmpty()) {
            panelSub.setText("Nema titlova za ovaj naslov");
            rows.add(new Row(ROW_SETUP, "Podešavanje titlova", "Ključ i nalog mijenjaš na sajtu " + Cloud.get(this).siteLabel()));
        } else {
            panelSub.setText(WelcomeActivity.plural(res.size(), "titl", "titla", "titlova") + " · ◀ nazad");
            int max = Math.min(res.size(), 40);
            for (int i = 0; i < max; i++) {
                Subs.Result s = res.get(i);
                Row r = new Row(ROW_RESULT, s.label(), s.release);
                r.result = s;
                rows.add(r);
            }
        }
        panelAdapter.setRows(rows);
        focusFirstRow();
    }

    private void downloadSub(final Subs.Result r) {
        panelSub.setText("Preuzimam…");
        panelLoading.setVisibility(View.VISIBLE);
        final File dir = new File(getCacheDir(), "subs");
        net.execute(new Runnable() {
            @Override public void run() {
                File f = null;
                String err = null;
                try {
                    f = subs.download(r, dir);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final File ff = f;
                final String fe = err;
                handler.post(new Runnable() {
                    @Override public void run() {
                        panelLoading.setVisibility(View.GONE);
                        if (ff == null) {
                            panelSub.setText(fe == null ? "Greška" : fe);
                            return;
                        }
                        attachSubtitle(ff, r);
                    }
                });
            }
        });
    }

    /** Dodaj skinuti titl i ukljuci ga (film nastavlja sa istog mjesta). */
    private void attachSubtitle(File f, Subs.Result r) {
        if (player == null) return;
        String label = r.label() + " · internet";
        // samo jedan titl sa interneta odjednom (zadnji izabrani)
        extSubs.clear();
        extSubs.add(new ExtSub(f, r.language, label));
        prefs.edit()
                .putString("sub:" + posKey, f.getAbsolutePath() + "|" + (r.language == null ? "" : r.language) + "|" + label)
                .putBoolean("subs_off", false)
                .apply();
        pendingSubLabel = label;
        long pos = player.getCurrentPosition();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build());
        hidePanel();
        showMessage("Titl: " + r.label());
        play(pos);
    }

    private boolean selectTextByLabel(String label) {
        if (player == null) return false;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                if (label.equals(f.label)) {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i)).build());
                    return true;
                }
            }
        }
        return false;
    }

    private final class PanelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<Row> rows = new ArrayList<>();

        void setRows(List<Row> l) {
            rows.clear();
            rows.addAll(l);
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return rows.size(); }

        @Override public int getItemViewType(int position) {
            int t = rows.get(position).type;
            return t == ROW_HEADER || t == ROW_INFO ? 0 : 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == 0) {
                View v = inf.inflate(R.layout.item_panel_header, parent, false);
                Ui.applyFonts(v);
                return new RecyclerView.ViewHolder(v) { };
            }
            View v = inf.inflate(R.layout.item_panel_row, parent, false);
            Ui.applyFonts(v);
            final RecyclerView.ViewHolder vh = new RecyclerView.ViewHolder(v) { };
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    int p = vh.getBindingAdapterPosition();
                    if (p != RecyclerView.NO_POSITION && p < rows.size()) onRowClick(rows.get(p));
                }
            });
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            Row r = rows.get(position);
            if (getItemViewType(position) == 0) {
                TextView t = (TextView) vh.itemView;
                t.setText(r.title);
                // poruka o gresci: obicnim slovima, citljivo
                if (r.type == ROW_INFO) {
                    t.setLetterSpacing(0f);
                    t.setTextColor(getResources().getColor(R.color.text_2));
                    t.setTextSize(14);
                } else {
                    t.setLetterSpacing(0.12f);
                    t.setTextColor(getResources().getColor(R.color.text_3));
                    t.setTextSize(12);
                }
                return;
            }
            ((TextView) vh.itemView.findViewById(R.id.check)).setText(r.checked ? "✓" : "");
            ((TextView) vh.itemView.findViewById(R.id.title)).setText(r.title);
            TextView sub = vh.itemView.findViewById(R.id.sub);
            sub.setText(r.sub == null ? "" : r.sub);
            sub.setVisibility(r.sub == null || r.sub.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }
}
