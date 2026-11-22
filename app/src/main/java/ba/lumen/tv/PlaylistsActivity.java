package ba.lumen.tv;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class PlaylistsActivity extends Activity {

    private static final String BASE = "https://iptv-org.github.io/iptv/";
    /** Svjetske liste (iptv-org). Balkanske liste po drzavama dolaze iz Repo.COUNTRY_LISTS. */
    private static final String[][] PRESETS = {
            {"🌍", "IPTV-org · Svi kanali", BASE + "index.m3u"},
            {"⚽", "Sport", BASE + "categories/sports.m3u"},
            {"📰", "Vijesti", BASE + "categories/news.m3u"},
            {"🎬", "Filmovi", BASE + "categories/movies.m3u"},
            {"🎵", "Muzika", BASE + "categories/music.m3u"},
            {"🧸", "Djeca", BASE + "categories/kids.m3u"},
            {"🌿", "Dokumentarni", BASE + "categories/documentary.m3u"},
    };

    private Repo repo;
    private final List<Repo.Playlist> items = new ArrayList<>();
    private Adapter adapter;
    private EditText inName, inUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_playlists);
        Ui.applyFonts(getWindow().getDecorView());
        repo = Repo.get(this);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setItemAnimator(null);

        inName = findViewById(R.id.in_name);
        inUrl = findViewById(R.id.in_url);
        inUrl.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    hideKeyboard();
                    findViewById(R.id.btn_save).requestFocus();
                    return true;
                }
                return false;
            }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveFromInputs(); }
        });
        findViewById(R.id.btn_xtream).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(PlaylistsActivity.this, XtreamActivity.class));
            }
        });

        LinearLayout presets = findViewById(R.id.presets);
        LayoutInflater inf = LayoutInflater.from(this);
        List<String[]> all = new ArrayList<>();
        for (String[] c : Repo.COUNTRY_LISTS) all.add(new String[]{c[1], c[2], repo.countryListUrl(c[0])});
        for (String[] p : PRESETS) all.add(p);
        for (final String[] p : all) {
            View row = inf.inflate(R.layout.item_preset, presets, false);
            Ui.applyFonts(row);
            ((TextView) row.findViewById(R.id.emoji)).setText(p[0]);
            ((TextView) row.findViewById(R.id.label)).setText(p[1]);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { addAndActivate(p[1], p[2]); }
            });
            presets.addView(row);
        }

        reload();
        list.post(new Runnable() {
            @Override public void run() {
                RecyclerView rv = findViewById(R.id.list);
                int active = indexOfActive();
                RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(active);
                if (vh != null) vh.itemView.findViewById(R.id.row).requestFocus();
            }
        });
    }

    private int indexOfActive() {
        String id = repo.activeId();
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) return i;
        return 0;
    }

    private void reload() {
        items.clear();
        items.addAll(repo.playlists());
        adapter.notifyDataSetChanged();
    }

    private void saveFromInputs() {
        String url = inUrl.getText().toString().trim();
        String name = inName.getText().toString().trim();
        if (!(url.startsWith("http://") || url.startsWith("https://")) || url.length() < 12) {
            Toast.makeText(this, "Unesi ispravan link (počinje sa http:// ili https://)", Toast.LENGTH_LONG).show();
            inUrl.requestFocus();
            return;
        }
        if (name.isEmpty()) {
            try {
                name = new java.net.URL(url).getHost();
            } catch (Exception e) {
                name = "Moja plejlista";
            }
        }
        hideKeyboard();
        inName.setText("");
        inUrl.setText("https://");
        addAndActivate(name, url);
    }

    private void addAndActivate(String name, String url) {
        Repo.Playlist p = repo.addPlaylist(name, url);
        repo.setActive(p.id);
        Toast.makeText(this, "„" + p.name + "“ je aktivna plejlista", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override public int getItemCount() { return items.size(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist, parent, false);
            Ui.applyFonts(v);
            final VH vh = new VH(v);
            vh.row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    int p = vh.getBindingAdapterPosition();
                    if (p == RecyclerView.NO_POSITION) return;
                    repo.setActive(items.get(p).id);
                    finish();
                }
            });
            vh.delete.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    int p = vh.getBindingAdapterPosition();
                    if (p == RecyclerView.NO_POSITION) return;
                    Repo.Playlist pl = items.get(p);
                    repo.removePlaylist(pl.id);
                    Toast.makeText(PlaylistsActivity.this, "Obrisano: " + pl.name, Toast.LENGTH_SHORT).show();
                    reload();
                }
            });
            vh.row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View view, boolean hasFocus) {
                    Ui.focusAnim(view, hasFocus, 1.02f);
                }
            });
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull VH vh, int position) {
            Repo.Playlist p = items.get(position);
            vh.name.setText(p.name);
            List<String> urls = Repo.urlsOf(p);
            vh.url.setText(urls.size() > 1 ? urls.size() + " spojenih lista  ·  " + urls.get(0) : p.url);
            boolean active = p.id.equals(repo.activeId());
            vh.check.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
            vh.checkBg.setAlpha(active ? 1f : 0.15f);
        }

        final class VH extends RecyclerView.ViewHolder {
            final View row, delete, check, checkBg;
            final TextView name, url;
            VH(View v) {
                super(v);
                row = v.findViewById(R.id.row);
                delete = v.findViewById(R.id.delete);
                check = v.findViewById(R.id.check);
                checkBg = v.findViewById(R.id.check_bg);
                name = v.findViewById(R.id.name);
                url = v.findViewById(R.id.url);
            }
        }
    }
}
