package ba.lumen.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Detalji filma ili serije: slika, opis, glumci, trailer, Pusti / Nastavi.
 * Za serije: sezone i epizode (sa trakom odgledanog).
 */
public class VodDetailActivity extends Activity {

    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_KEY = "key";

    private Vod vod;
    private Vod.Item item;
    private Vod.Details details;

    private TextView metaView, plotView, peopleView, statusView, playBtn, restartBtn, trailerBtn;
    private ImageView backdrop, poster;
    private RecyclerView seasonsView, episodesView;
    private SeasonAdapter seasonAdapter;
    private EpisodeAdapter episodeAdapter;
    private int seasonIdx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vod = Vod.get(this);
        int kind = getIntent().getIntExtra(EXTRA_KIND, Vod.MOVIE);
        item = vod.find(kind, getIntent().getStringExtra(EXTRA_KEY));
        if (item == null) {
            // aplikacija je bila zatvorena u pozadini: lista vise nije u memoriji
            finish();
            return;
        }
        Ui.init(this);
        setContentView(R.layout.activity_vod_detail);
        Ui.applyFonts(getWindow().getDecorView());

        backdrop = findViewById(R.id.backdrop);
        poster = findViewById(R.id.poster);
        metaView = findViewById(R.id.meta);
        plotView = findViewById(R.id.plot);
        peopleView = findViewById(R.id.people);
        statusView = findViewById(R.id.status);
        playBtn = findViewById(R.id.btn_play);
        restartBtn = findViewById(R.id.btn_restart);
        trailerBtn = findViewById(R.id.btn_trailer);
        findViewById(R.id.poster_box).setClipToOutline(true);

        ((TextView) findViewById(R.id.title)).setText(item.title);
        ((TextView) findViewById(R.id.poster_text)).setText(item.title);
        loadImage(poster, item.poster);
        metaView.setText(item.meta());
        plotView.setText("Učitavam opis…");
        plotView.setTextColor(getResources().getColor(R.color.text_3));

        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(false); }
        });
        restartBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(true); }
        });
        trailerBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openTrailer(); }
        });

        if (item.kind == Vod.SERIES) {
            findViewById(R.id.series_box).setVisibility(View.VISIBLE);
            plotView.setMaxLines(3);
            seasonsView = findViewById(R.id.seasons);
            episodesView = findViewById(R.id.episodes);
            seasonsView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            episodesView.setLayoutManager(new LinearLayoutManager(this));
            seasonAdapter = new SeasonAdapter();
            episodeAdapter = new EpisodeAdapter();
            seasonsView.setAdapter(seasonAdapter);
            episodesView.setAdapter(episodeAdapter);
            seasonsView.setItemAnimator(null);
            episodesView.setItemAnimator(null);
            playBtn.setText("Učitavam…");
        }
        playBtn.requestFocus();
        updatePlayButtons();

        vod.details(item, new Vod.Callback<Vod.Details>() {
            @Override public void onResult(Vod.Details d) {
                if (isFinishing()) return;
                details = d;
                showDetails();
            }
            @Override public void onError(String message) {
                if (isFinishing()) return;
                plotView.setText(item.kind == Vod.SERIES ? message : "Opis nije dostupan.");
                if (item.kind == Vod.SERIES) playBtn.setText("Pokušaj ponovo");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (item == null) return;
        // povratak sa gledanja: "Nastavi od" i trake odgledanog
        updatePlayButtons();
        if (episodeAdapter != null) episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.getItemCount());
    }

    private void loadImage(ImageView v, String url) {
        if (url == null) return;
        Glide.with(v).load(url).diskCacheStrategy(DiskCacheStrategy.ALL).into(v);
    }

    private void showDetails() {
        Vod.Details d = details;
        // meta: godina · zanr · trajanje · ocjena · drzava
        List<String> parts = new ArrayList<>();
        String year = item.year != null ? item.year : Vod.yearOf(d.release);
        if (year != null) parts.add(year);
        if (d.genre != null) parts.add(d.genre);
        if (d.durationText() != null) parts.add(d.durationText());
        float rating = d.rating > 0 ? d.rating : item.rating;
        if (rating > 0 && rating <= 10) parts.add("★ " + String.format(java.util.Locale.ROOT, "%.1f", rating));
        if (d.country != null) parts.add(d.country);
        if (item.kind == Vod.SERIES && !d.seasons.isEmpty()) {
            parts.add(WelcomeActivity.plural(d.seasons.size(), "sezona", "sezone", "sezona"));
        }
        metaView.setText(TextUtils.join("  ·  ", parts));

        plotView.setTextColor(getResources().getColor(R.color.text));
        plotView.setText(d.plot != null ? d.plot : "Opis nije dostupan.");
        StringBuilder people = new StringBuilder();
        if (d.director != null) people.append("Režija: ").append(d.director);
        if (d.cast != null) {
            if (people.length() > 0) people.append('\n');
            people.append("Uloge: ").append(d.cast);
        }
        peopleView.setText(people);
        peopleView.setVisibility(people.length() > 0 ? View.VISIBLE : View.GONE);

        if (d.poster != null && item.poster == null) loadImage(poster, d.poster);
        loadImage(backdrop, d.backdrop != null ? d.backdrop : (d.poster != null ? d.poster : item.poster));
        trailerBtn.setVisibility(d.trailer != null ? View.VISIBLE : View.GONE);

        if (item.kind == Vod.SERIES) {
            // otvori sezonu zadnje gledane epizode
            seasonIdx = 0;
            int[] last = vod.lastEpisode(item);
            if (last != null) {
                for (int i = 0; i < d.seasons.size(); i++) if (d.seasons.get(i).number == last[0]) seasonIdx = i;
            }
            seasonAdapter.notifyDataSetChanged();
            episodeAdapter.notifyDataSetChanged();
        }
        updatePlayButtons();
    }

    private void showSeason(int idx) {
        int old = seasonIdx;
        seasonIdx = idx;
        // samo te dvije stavke (da fokus ostane na sezoni)
        seasonAdapter.notifyItemChanged(old);
        seasonAdapter.notifyItemChanged(idx);
        episodeAdapter.notifyDataSetChanged();
        episodesView.scrollToPosition(0);
    }

    // ------------------------------------------------------------------ pusti / nastavi

    private void updatePlayButtons() {
        if (item.kind == Vod.MOVIE) {
            long from = vod.resumeFrom(Vod.movieKey(item));
            if (from > 0) {
                playBtn.setText("Nastavi od " + VodPlayerActivity.time(from));
                restartBtn.setVisibility(View.VISIBLE);
            } else {
                playBtn.setText("Pusti");
                restartBtn.setVisibility(View.GONE);
            }
            return;
        }
        if (details == null || details.seasons.isEmpty()) return;
        Vod.Episode e = nextEpisode();
        long from = vod.resumeFrom(Vod.episodeKey(item, e));
        String label = "S" + e.season + " E" + e.num;
        playBtn.setText((from > 0 || vod.lastEpisode(item) != null ? "Nastavi " : "Pusti ") + label);
        restartBtn.setVisibility(View.GONE);
    }

    /** Epizoda za dugme "Nastavi": zadnja gledana (ili sljedeca ako je odgledana), inace prva. */
    private Vod.Episode nextEpisode() {
        int[] last = vod.lastEpisode(item);
        List<Vod.Episode> all = new ArrayList<>();
        for (Vod.Season s : details.seasons) all.addAll(s.episodes);
        if (last != null) {
            for (int i = 0; i < all.size(); i++) {
                Vod.Episode e = all.get(i);
                if (e.season == last[0] && e.num == last[1]) {
                    long[] p = vod.position(Vod.episodeKey(item, e));
                    boolean done = p != null && p[1] > 0 && p[0] > p[1] * 0.95;
                    return done && i + 1 < all.size() ? all.get(i + 1) : e;
                }
            }
        }
        return all.get(0);
    }

    private void play(boolean fromStart) {
        if (item.kind == Vod.MOVIE) {
            startPlayer(null, fromStart ? 0 : vod.resumeFrom(Vod.movieKey(item)));
            return;
        }
        if (details == null) {
            // detalji serije nisu stigli: pokusaj ponovo
            playBtn.setText("Učitavam…");
            vod.details(item, new Vod.Callback<Vod.Details>() {
                @Override public void onResult(Vod.Details d) { details = d; showDetails(); }
                @Override public void onError(String message) {
                    playBtn.setText("Pokušaj ponovo");
                    Toast.makeText(VodDetailActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        Vod.Episode e = nextEpisode();
        startPlayer(e, vod.resumeFrom(Vod.episodeKey(item, e)));
    }

    private void startPlayer(Vod.Episode e, long from) {
        Intent i = new Intent(this, VodPlayerActivity.class);
        i.putExtra(VodPlayerActivity.EXTRA_KIND, item.kind);
        i.putExtra(VodPlayerActivity.EXTRA_KEY, item.key);
        i.putExtra(VodPlayerActivity.EXTRA_FROM, from);
        if (e != null) {
            i.putExtra(VodPlayerActivity.EXTRA_SEASON, e.season);
            i.putExtra(VodPlayerActivity.EXTRA_EPISODE, e.num);
        }
        startActivity(i);
    }

    private void openTrailer() {
        if (details == null || details.trailer == null) return;
        String id = details.trailer;
        try {
            // YouTube aplikacija na TV-u
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + id)));
            return;
        } catch (ActivityNotFoundException ignored) { }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Za trailer treba aplikacija YouTube.", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ sezone i epizode

    private Vod.Season season() {
        if (details == null || details.seasons.isEmpty()) return null;
        return details.seasons.get(Math.min(seasonIdx, details.seasons.size() - 1));
    }

    private final class SeasonAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return details == null ? 0 : details.seasons.size(); }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_season, parent, false);
            Ui.applyFonts(v);
            final RecyclerView.ViewHolder vh = new RecyclerView.ViewHolder(v) { };
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    int p = vh.getBindingAdapterPosition();
                    if (p != RecyclerView.NO_POSITION) {
                        showSeason(p);
                        // na prvu epizodu
                        episodesView.post(new Runnable() {
                            @Override public void run() {
                                RecyclerView.ViewHolder first = episodesView.findViewHolderForAdapterPosition(0);
                                if (first != null) first.itemView.requestFocus();
                            }
                        });
                    }
                }
            });
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View view, boolean hasFocus) {
                    // sezona se prikaze cim se fokus zaustavi na njoj
                    int p = vh.getBindingAdapterPosition();
                    if (hasFocus && p != RecyclerView.NO_POSITION && p != seasonIdx) showSeason(p);
                }
            });
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            Vod.Season s = details.seasons.get(position);
            ((TextView) vh.itemView).setText(s.name + "  ·  " + s.episodes.size());
            vh.itemView.setActivated(position == seasonIdx);
        }
    }

    private final class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.VH> {
        @Override public int getItemCount() {
            Vod.Season s = season();
            return s == null ? 0 : s.episodes.size();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_episode, parent, false);
            Ui.applyFonts(v);
            final VH vh = new VH(v);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    Vod.Season s = season();
                    int p = vh.getBindingAdapterPosition();
                    if (s == null || p == RecyclerView.NO_POSITION || p >= s.episodes.size()) return;
                    Vod.Episode e = s.episodes.get(p);
                    startPlayer(e, vod.resumeFrom(Vod.episodeKey(item, e)));
                }
            });
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View view, boolean hasFocus) {
                    vh.plot.setVisibility(hasFocus && vh.plot.getText().length() > 0 ? View.VISIBLE : View.GONE);
                }
            });
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull VH vh, int position) {
            Vod.Episode e = season().episodes.get(position);
            vh.num.setText(String.valueOf(e.num));
            vh.title.setText(e.title != null ? e.title : "Epizoda " + e.num);
            vh.plot.setText(e.plot == null ? "" : e.plot);
            vh.plot.setVisibility(vh.itemView.hasFocus() && e.plot != null ? View.VISIBLE : View.GONE);
            vh.duration.setText(e.durationSecs > 0 ? Math.round(e.durationSecs / 60f) + " min" : "");
            long[] p = vod.position(Vod.episodeKey(item, e));
            if (p != null && p[1] > 0 && p[0] > 30000) {
                vh.watched.setVisibility(View.VISIBLE);
                vh.watched.setProgress((int) Math.min(1000, p[0] * 1000 / p[1]));
            } else {
                vh.watched.setVisibility(View.GONE);
            }
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView num, title, plot, duration;
            final ProgressBar watched;
            VH(View v) {
                super(v);
                num = v.findViewById(R.id.num);
                title = v.findViewById(R.id.title);
                plot = v.findViewById(R.id.plot);
                duration = v.findViewById(R.id.duration);
                watched = v.findViewById(R.id.watched);
            }
        }
    }
}
