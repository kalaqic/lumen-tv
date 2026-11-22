package ba.lumen.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements SidebarAdapter.Listener, ChannelAdapter.Listener {

    /** Drzave koje se prikazuju na vrhu liste drzava. */
    private static final List<String> PINNED = Arrays.asList("BA", "HR", "RS", "ME", "SI", "MK", "XK");

    private Repo repo;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private RecyclerView side, grid;
    private SidebarAdapter sideAdapter;
    private ChannelAdapter gridAdapter;
    private GridLayoutManager gridLayout;
    private TextView title, subtitle, playlistName, stateTitle, stateText, retryBtn;
    private View stateView, progress;
    private EditText search;

    private SideItem selected;
    private String query = "";
    private String shownPlaylistId;
    private boolean firstFocusDone = false;
    private boolean focusGridAfterLoad = false;
    private final NumberFormat nf = NumberFormat.getIntegerInstance(new Locale("bs"));

    private Runnable pendingSelect;
    private Updater updater;
    private Health health;
    private String healthText = "";
    private final Runnable searchRunnable = new Runnable() {
        @Override public void run() { applyFilter(true); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_main);
        Ui.applyFonts(findViewById(R.id.root));
        repo = Repo.get(this);
        health = Health.get(this);

        side = findViewById(R.id.side_list);
        grid = findViewById(R.id.grid);
        title = findViewById(R.id.section_title);
        subtitle = findViewById(R.id.section_sub);
        playlistName = findViewById(R.id.playlist_name);
        stateView = findViewById(R.id.state_view);
        progress = findViewById(R.id.progress);
        stateTitle = findViewById(R.id.state_title);
        stateText = findViewById(R.id.state_text);
        retryBtn = findViewById(R.id.btn_retry);
        search = findViewById(R.id.search);
        TextView version = findViewById(R.id.app_version);
        try {
            version.setText("Lumen TV · verzija " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            version.setText("Lumen TV");
        }
        updater = new Updater(this);

        sideAdapter = new SidebarAdapter(this);
        side.setLayoutManager(new LinearLayoutManager(this));
        side.setAdapter(sideAdapter);
        side.setItemAnimator(null);

        gridAdapter = new ChannelAdapter(repo, this);
        gridLayout = new GridLayoutManager(this, spanCount());
        grid.setLayoutManager(gridLayout);
        grid.setAdapter(gridAdapter);
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.setItemViewCacheSize(30);

        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load(true); }
        });
        findViewById(R.id.btn_update).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { updater.check(true); }
        });
        retryBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load(true); }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                handler.removeCallbacks(searchRunnable);
                handler.postDelayed(searchRunnable, 280);
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    hideKeyboard();
                    handler.removeCallbacks(searchRunnable);
                    applyFilter(true);
                    focusGrid();
                    return true;
                }
                return false;
            }
        });

        // Kada fokus udje u bocni meni izvana, vrati ga na trenutno odabranu stavku.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(
                new ViewTreeObserver.OnGlobalFocusChangeListener() {
                    @Override public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                        onFocusMoved(oldFocus, newFocus);
                    }
                });

        SharedPreferences sp = getSharedPreferences("lumen", MODE_PRIVATE);
        if (!sp.getBoolean("welcome_done", false)) {
            sp.edit().putBoolean("welcome_done", true).apply();
            startActivity(new Intent(this, WelcomeActivity.class));
        }

        load(false);
        updater.checkOnStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updater.onResume();
        if (shownPlaylistId != null && !shownPlaylistId.equals(repo.activeId())) {
            load(false);
            return;
        }
        if (!repo.all.isEmpty()) {
            // favoriti/nedavno su se mozda promijenili u plejeru
            updateLibraryCounts();
            if (selected != null && (selected.type == SideItem.FAVORITES || selected.type == SideItem.RECENT) && query.isEmpty()) {
                applyFilter(false);
            } else {
                gridAdapter.notifyItemRangeChanged(0, gridAdapter.getItemCount());
            }
        }
    }

    private int spanCount() {
        float density = getResources().getDisplayMetrics().density;
        float widthDp = getResources().getDisplayMetrics().widthPixels / density;
        float avail = widthDp - 250 - 60;
        return Math.max(3, Math.min(7, (int) (avail / 160)));
    }

    // ------------------------------------------------------------------ ucitavanje

    private void load(boolean force) {
        final Repo.Playlist p = repo.active();
        shownPlaylistId = p.id;
        playlistName.setText(p.name);
        if (force || repo.all.isEmpty() || !p.id.equals(repo.loadedPlaylistId)) {
            showLoading();
        }
        if (force) health.clear();
        repo.load(force, new Repo.LoadCallback() {
            @Override public void onLoaded(List<Channel> channels, boolean fromCache) {
                buildSidebar();
                applyFilter(false);
                if (focusGridAfterLoad) { focusGridAfterLoad = false; focusGrid(); }
                if (!fromCache) toast("Učitano " + nf.format(channels.size()) + " kanala");
                startHealthCheck();
            }

            @Override public void onError(String message) {
                showError(message);
            }
        });
    }

    private void showLoading() {
        gridAdapter.setItems(Collections.<Channel>emptyList());
        stateView.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        retryBtn.setVisibility(View.GONE);
        stateTitle.setText(R.string.loading);
        stateText.setText("Prvo učitavanje velike liste može potrajati nekoliko sekundi.");
        subtitle.setText("");
    }

    private void showError(String msg) {
        gridAdapter.setItems(Collections.<Channel>emptyList());
        stateView.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        retryBtn.setVisibility(View.VISIBLE);
        stateTitle.setText("Ups, nešto nije u redu");
        stateText.setText(msg);
        retryBtn.requestFocus();
    }

    private void showEmpty() {
        stateView.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        retryBtn.setVisibility(View.GONE);
        stateTitle.setText(R.string.empty);
        if (!query.isEmpty()) stateText.setText("Probaj drugi pojam za pretragu.");
        else if (selected != null && selected.type == SideItem.FAVORITES) stateText.setText("Dugo drži OK na kanalu da ga dodaš u favorite.");
        else if (selected != null && selected.type == SideItem.RECENT) stateText.setText("Kanali koje gledaš pojavit će se ovdje.");
        else stateText.setText("");
    }

    // ------------------------------------------------------------------ bocni meni

    private void buildSidebar() {
        List<Channel> all = repo.all;
        Map<String, Integer> cats = new HashMap<>();
        Map<String, Integer> countries = new HashMap<>();
        for (Channel c : all) {
            for (String g : c.groups) {
                Integer n = cats.get(g);
                cats.put(g, n == null ? 1 : n + 1);
            }
            if (c.country != null) {
                Integer n = countries.get(c.country);
                countries.put(c.country, n == null ? 1 : n + 1);
            }
        }

        List<SideItem> items = new ArrayList<>();

        // ----- Plejliste (izvori) -----
        items.add(new SideItem(SideItem.HEADER, null, "Plejliste", 0));
        String activeId = repo.activeId();
        for (Repo.Playlist pl : repo.playlists()) {
            String emoji = null, label = pl.name;
            int sp = pl.name.indexOf("  ");
            if (sp > 0 && sp <= 6) {       // vodeci emoji (zastava) odvojen od naziva
                emoji = pl.name.substring(0, sp).trim();
                label = pl.name.substring(sp).trim();
            }
            SideItem pi = new SideItem(SideItem.PLAYLIST, pl.id, label, 0);
            pi.activeFlag = pl.id.equals(activeId);
            if (emoji != null) pi.emoji = emoji;
            else pi.icon = R.drawable.ic_list;
            items.add(pi);
        }
        SideItem add = new SideItem(SideItem.ADD, null, "Dodaj / Xtream prijava", 0);
        add.icon = R.drawable.ic_add;
        items.add(add);

        items.add(new SideItem(SideItem.HEADER, null, "Biblioteka", 0));
        int balkanCount = 0;
        for (Channel c : all) if (c.balkan) balkanCount++;
        if (balkanCount > 0 && balkanCount < all.size()) {
            SideItem balkan = new SideItem(SideItem.BALKAN, null, "Balkan", balkanCount);
            balkan.icon = R.drawable.ic_globe;
            items.add(balkan);
        }
        SideItem allItem = new SideItem(SideItem.ALL, null, "Svi kanali", all.size());
        allItem.icon = R.drawable.ic_grid;
        items.add(allItem);
        SideItem fav = new SideItem(SideItem.FAVORITES, null, "Favoriti", repo.favoriteChannels().size());
        fav.icon = R.drawable.ic_star;
        items.add(fav);
        SideItem rec = new SideItem(SideItem.RECENT, null, "Nedavno gledano", repo.recentCount());
        rec.icon = R.drawable.ic_history;
        items.add(rec);

        final Collator collator = Collator.getInstance(new Locale("bs"));

        if (cats.size() > 1) {
            items.add(new SideItem(SideItem.HEADER, null, "Kategorije", 0));
            List<String> keys = new ArrayList<>(cats.keySet());
            Collections.sort(keys, new Comparator<String>() {
                @Override public int compare(String a, String b) {
                    boolean ua = a.equalsIgnoreCase("undefined"), ub = b.equalsIgnoreCase("undefined");
                    if (ua != ub) return ua ? 1 : -1;
                    return collator.compare(Labels.category(a), Labels.category(b));
                }
            });
            for (String k : keys) {
                SideItem it = new SideItem(SideItem.CATEGORY, k, Labels.category(k), cats.get(k));
                it.icon = R.drawable.ic_tag;
                items.add(it);
            }
        }

        if (countries.size() > 1) {
            items.add(new SideItem(SideItem.HEADER, null, "Države", 0));
            List<String> codes = new ArrayList<>(countries.keySet());
            Collections.sort(codes, new Comparator<String>() {
                @Override public int compare(String a, String b) {
                    int pa = PINNED.indexOf(a), pb = PINNED.indexOf(b);
                    if (pa >= 0 || pb >= 0) {
                        if (pa < 0) return 1;
                        if (pb < 0) return -1;
                        return pa - pb;
                    }
                    return collator.compare(Labels.countryName(a), Labels.countryName(b));
                }
            });
            for (String code : codes) {
                SideItem it = new SideItem(SideItem.COUNTRY, code, Labels.countryName(code), countries.get(code));
                it.emoji = Labels.flag(code);
                items.add(it);
            }
        }

        // podrazumijevani odabir: "Svi kanali"; inace zadrzi prethodni filter
        int sel = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).type == SideItem.ALL) { sel = i; break; }
        if (sel < 0) sel = 1;
        if (selected != null && selected.type != SideItem.PLAYLIST && selected.type != SideItem.ADD) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).sameAs(selected)) { sel = i; break; }
        }
        sideAdapter.selected = sel;
        sideAdapter.setItems(items);
        selected = items.get(sel);
    }

    private void updateLibraryCounts() {
        List<SideItem> items = sideAdapter.items();
        for (int i = 0; i < items.size(); i++) {
            SideItem it = items.get(i);
            int n = -1;
            if (it.type == SideItem.FAVORITES) n = repo.favoriteChannels().size();
            else if (it.type == SideItem.RECENT) n = repo.recentCount();
            if (n >= 0 && n != it.count) {
                it.count = n;
                sideAdapter.notifyItemChanged(i);
            }
        }
    }

    @Override
    public void onSideClick(int position) {
        if (pendingSelect != null) handler.removeCallbacks(pendingSelect);
        SideItem it = sideAdapter.get(position);
        if (it != null && it.type == SideItem.ADD) {
            startActivity(new Intent(this, PlaylistsActivity.class));
            return;
        }
        if (it != null && it.type == SideItem.PLAYLIST) {
            switchPlaylist(it.key);
            return;
        }
        select(position);
        focusGrid();
    }

    private void switchPlaylist(String id) {
        if (id == null || id.equals(repo.activeId())) { focusGrid(); return; }
        repo.setActive(id);
        selected = null;
        query = "";
        search.setText("");
        handler.removeCallbacks(searchRunnable);
        focusGridAfterLoad = true;
        load(false);
    }

    private void select(int position) {
        SideItem it = sideAdapter.get(position);
        if (it == null || it.type == SideItem.HEADER
                || it.type == SideItem.PLAYLIST || it.type == SideItem.ADD) return;
        boolean changed = !it.sameAs(selected);
        selected = it;
        sideAdapter.setSelected(position);
        if (!query.isEmpty()) {
            // odabir kategorije brise pretragu
            query = "";
            search.setText("");
            handler.removeCallbacks(searchRunnable);
            changed = true;
        }
        if (changed) applyFilter(true);
    }

    private void onFocusMoved(View oldFocus, View newFocus) {
        if (newFocus == null) return;
        boolean newInSide = isInside(side, newFocus);
        boolean oldInSide = oldFocus != null && isInside(side, oldFocus);
        if (!newInSide) return;

        View item = side.findContainingItemView(newFocus);
        final int pos = item != null ? side.getChildAdapterPosition(item) : RecyclerView.NO_POSITION;

        if (!oldInSide) {
            final int sel = sideAdapter.selected;
            if (pos != sel) {
                side.post(new Runnable() {
                    @Override public void run() { focusSideItem(sel); }
                });
            }
            return;
        }
        // navigacija unutar menija: odaberi nakon kratke pauze
        if (pendingSelect != null) handler.removeCallbacks(pendingSelect);
        pendingSelect = new Runnable() {
            @Override public void run() { if (pos != RecyclerView.NO_POSITION) select(pos); }
        };
        handler.postDelayed(pendingSelect, 320);
    }

    private void focusSideItem(final int pos) {
        RecyclerView.ViewHolder vh = side.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.requestFocus();
        } else {
            side.scrollToPosition(pos);
            side.post(new Runnable() {
                @Override public void run() {
                    RecyclerView.ViewHolder v2 = side.findViewHolderForAdapterPosition(pos);
                    if (v2 != null) v2.itemView.requestFocus();
                }
            });
        }
    }

    private static boolean isInside(View parent, View v) {
        View cur = v;
        while (cur != null) {
            if (cur == parent) return true;
            if (!(cur.getParent() instanceof View)) return false;
            cur = (View) cur.getParent();
        }
        return false;
    }

    // ------------------------------------------------------------------ filtriranje

    private void applyFilter(boolean scrollTop) {
        List<Channel> out;
        String sectionTitle;
        if (!query.isEmpty()) {
            String q = M3uParser.normalize(query);
            out = new ArrayList<>();
            for (Channel c : repo.all) if (c.searchKey.contains(q)) out.add(c);
            out = prioritize(out, true);
            sectionTitle = "Rezultati za „" + query + "“";
        } else {
            SideItem s = selected;
            int type = s == null ? SideItem.ALL : s.type;
            switch (type) {
                case SideItem.FAVORITES:
                    out = repo.favoriteChannels();
                    sectionTitle = "Favoriti";
                    break;
                case SideItem.RECENT:
                    out = repo.recentChannels();
                    sectionTitle = "Nedavno gledano";
                    break;
                case SideItem.BALKAN:
                    out = new ArrayList<>();
                    for (Channel c : repo.all) if (c.balkan) out.add(c);
                    out = prioritize(out, false);
                    sectionTitle = "Balkan";
                    break;
                case SideItem.CATEGORY:
                    out = new ArrayList<>();
                    for (Channel c : repo.all) {
                        for (String g : c.groups) if (g.equals(s.key)) { out.add(c); break; }
                    }
                    out = prioritize(out, true);
                    sectionTitle = s.label;
                    break;
                case SideItem.COUNTRY:
                    out = new ArrayList<>();
                    for (Channel c : repo.all) if (s.key.equals(c.country)) out.add(c);
                    out = prioritize(out, false);
                    sectionTitle = s.emoji + "  " + s.label;
                    break;
                default:
                    out = prioritize(repo.all, true);
                    sectionTitle = "Svi kanali";
            }
        }
        title.setText(sectionTitle);
        shownCount = out.size();
        updateSubtitle();
        repo.current = out;
        repo.currentTitle = sectionTitle;
        gridAdapter.setItems(out);
        if (scrollTop) grid.scrollToPosition(0);

        if (out.isEmpty()) showEmpty();
        else stateView.setVisibility(View.GONE);

        if (!firstFocusDone && !out.isEmpty()) {
            firstFocusDone = true;
            focusGrid();
        }
    }

    private int shownCount = 0;

    private void updateSubtitle() {
        int n = shownCount;
        String base = nf.format(n) + (n % 10 == 1 && n % 100 != 11 ? " kanal" : " kanala");
        subtitle.setText(healthText.isEmpty() ? base : base + "   ·   " + healthText);
    }

    /** Balkan prvo (ako je trazeno), zatim kanali koji rade, pa neprovjereni, pa mrtvi/blokirani. */
    private List<Channel> prioritize(List<Channel> in, final boolean balkanFirst) {
        List<Channel> out = new ArrayList<>(in);
        Collections.sort(out, new Comparator<Channel>() {   // stabilno sortiranje cuva redoslijed iz plejliste
            @Override public int compare(Channel a, Channel b) {
                return rank(a, balkanFirst) - rank(b, balkanFirst);
            }
        });
        return out;
    }

    private int rank(Channel c, boolean balkanFirst) {
        int r = health.rank(c);
        if (balkanFirst && !c.balkan) r += 10;
        return r;
    }

    /** U pozadini provjeri koji balkanski kanali rade. */
    private void startHealthCheck() {
        List<Channel> balkan = new ArrayList<>();
        for (Channel c : repo.all) if (c.balkan) balkan.add(c);
        // za plejliste bez balkanskih kanala provjeri manje liste u cijelosti
        if (balkan.isEmpty() && repo.all.size() <= 600) balkan.addAll(repo.all);
        if (balkan.isEmpty()) return;
        health.check(balkan, new Health.Progress() {
            @Override public void onProgress(int done, int total, boolean finished) {
                if (total == 0) return;
                gridAdapter.notifyItemRangeChanged(0, gridAdapter.getItemCount());
                if (finished) {
                    healthText = "";
                    // presortiraj samo ako korisnik trenutno nije u mrezi kanala (da ne skace fokus)
                    View f = getCurrentFocus();
                    if (f == null || !isInside(grid, f)) applyFilter(false);
                    else updateSubtitle();
                } else {
                    healthText = "provjeravam dostupnost " + done + "/" + total;
                    updateSubtitle();
                }
            }
        });
    }

    private void focusGrid() {
        if (gridAdapter.getItemCount() == 0) return;
        grid.scrollToPosition(0);
        grid.post(new Runnable() {
            @Override public void run() {
                View first = gridLayout.findViewByPosition(0);
                if (first != null) first.requestFocus();
                else grid.requestFocus();
            }
        });
    }

    // ------------------------------------------------------------------ kanali

    @Override
    public void onChannelClick(int position) {
        repo.current = new ArrayList<>(gridAdapter.items());
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_INDEX, position);
        startActivity(i);
    }

    @Override
    public void onChannelLongClick(int position) {
        Channel c = gridAdapter.items().get(position);
        boolean fav = repo.toggleFavorite(c);
        toast(fav ? "★  " + c.name + " dodan u favorite" : c.name + " uklonjen iz favorita");
        gridAdapter.notifyItemChanged(position);
        updateLibraryCounts();
    }

    // ------------------------------------------------------------------ ostalo

    @Override
    protected void onDestroy() {
        super.onDestroy();
        health.cancel();
    }

    @Override
    public void onBackPressed() {
        if (!query.isEmpty()) {
            search.setText("");
            query = "";
            handler.removeCallbacks(searchRunnable);
            applyFilter(true);
            focusGrid();
            return;
        }
        View f = getCurrentFocus();
        if (f != null && isInside(grid, f)) {
            focusSideItem(sideAdapter.selected);
            return;
        }
        super.onBackPressed();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
