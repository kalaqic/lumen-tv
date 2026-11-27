package ba.lumen.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity implements SidebarAdapter.Listener, ChannelAdapter.Listener {

    /** Redoslijed drzava: BiH, Srbija, Hrvatska, pa ostale. */
    static final List<String> PINNED = Arrays.asList("BA", "RS", "HR", "ME", "SI", "MK", "XK");

    private Repo repo;
    private Cloud cloud;
    private Health health;
    private Updater updater;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private RecyclerView side, grid;
    private SidebarAdapter sideAdapter;
    private ChannelAdapter gridAdapter;
    private GridLayoutManager gridLayout;
    private TextView title, subtitle, playlistName, stateTitle, stateText, retryBtn;
    private View stateView, progress;
    private EditText search;

    /** Trenutno odabrana stavka menija (filter: svi, drzava, kategorija...). */
    private SideItem selected;
    private String query = "";
    private String shownPlaylistId;
    private boolean firstFocusDone = false;
    private int shownCount = 0;
    private String healthText = "";
    private int healthCheckId = -1;
    private final NumberFormat nf = NumberFormat.getIntegerInstance(new Locale("bs"));

    /** Odabir stavke menija kad se korisnik zaustavi na njoj (kao ranije). */
    private Runnable pendingSelect;
    /** true dok aplikacija sama pomjera fokus (da se to ne shvati kao korisnikov odabir). */
    private boolean programmaticFocus = false;

    private final Runnable searchRunnable = new Runnable() {
        @Override public void run() { applyFilter(true); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = Repo.get(this);
        cloud = Cloud.get(this);

        // izvor jos nije izabran (npr. pokrenuto preko stare precice) -> prvo izbor
        if (repo.mode() == null) {
            Intent i = new Intent(this, WelcomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
            return;
        }

        Ui.init(this);
        setContentView(R.layout.activity_main);
        Ui.applyFonts(findViewById(R.id.root));
        health = Health.get(this);
        updater = new Updater(this);

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

        findViewById(R.id.source_row).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, WelcomeActivity.class);
                i.putExtra(WelcomeActivity.EXTRA_SWITCH, true);
                startActivity(i);
            }
        });
        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // prvo liste sa sajta, pa kanali
                if (!cloud.paired()) { load(true); return; }
                showLoading();
                cloud.syncAsync(new Cloud.Callback() {
                    @Override public void onSync(int r) {
                        if (isFinishing()) return;
                        if (r == Cloud.SYNC_UNPAIRED) toast("Ovaj TV je odspojen sa sajta.");
                        load(true);
                    }
                });
            }
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
                String q = s.toString().trim();
                if (q.equals(query)) return;
                query = q;
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

        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(
                new ViewTreeObserver.OnGlobalFocusChangeListener() {
                    @Override public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                        onFocusMoved(oldFocus, newFocus);
                    }
                });

        load(false);
        updater.checkOnStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updater == null) return; // activity se zatvara (preusmjereno na izbor izvora)
        updater.onResume();
        // liste su mozda promijenjene na sajtu: kad stignu, ucitaj ponovo ako treba
        cloud.syncAsync(new Cloud.Callback() {
            @Override public void onSync(int r) {
                if (isFinishing() || shownPlaylistId == null) return;
                if (r == Cloud.SYNC_UNPAIRED) toast("Ovaj TV je odspojen sa sajta.");
                if ((r == Cloud.SYNC_CHANGED || r == Cloud.SYNC_UNPAIRED) && !shownPlaylistId.equals(repo.activeId())) {
                    load(false);
                }
            }
        });
        if (shownPlaylistId != null && !shownPlaylistId.equals(repo.activeId())) {
            // liste ili profil su promijenjeni (npr. uklonjena prijava): ucitaj ponovo
            load(false);
            return;
        }
        boolean loaded = shownPlaylistId != null && shownPlaylistId.equals(repo.loadedPlaylistId)
                && !repo.all.isEmpty();
        if (!loaded) return;
        // vracanje iz plejera: favoriti, historija i status kanala su se mozda promijenili
        updateLibraryCounts();
        if (selected != null && (selected.type == SideItem.FAVORITES || selected.type == SideItem.RECENT)
                && query.isEmpty()) {
            applyFilter(false);
        } else {
            gridAdapter.notifyItemRangeChanged(0, gridAdapter.getItemCount());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (health != null) health.cancel(healthCheckId);
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
        boolean otherSource = !p.id.equals(repo.loadedPlaylistId);
        shownPlaylistId = p.id;
        playlistName.setText(repo.isXtream() ? p.name : "Besplatni kanali");
        if (otherSource) {
            // meni od prethodnog izvora vise ne vazi
            selected = null;
            sideAdapter.setItems(Collections.<SideItem>emptyList());
        }
        if (force || otherSource || repo.all.isEmpty()) showLoading();
        if (force) health.clear();
        repo.load(force, new Repo.LoadCallback() {
            @Override public void onLoaded(List<Channel> channels, boolean fromCache) {
                buildSidebar();
                applyFilter(false);
                if (!fromCache) {
                    List<String> failed = repo.lastFailed;
                    if (!failed.isEmpty()) {
                        toast("Nije se učitalo: " + TextUtils.join(", ", failed) + " (server ne odgovara)");
                    } else {
                        toast("Učitano " + nf.format(channels.size()) + " kanala");
                    }
                }
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
        stateText.setText("Prvo učitavanje može potrajati nekoliko sekundi.");
        healthText = "";
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
        cancelPendingSelect();
        List<Channel> all = repo.all;
        boolean xtream = repo.isXtream();

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
        if (!xtream) {
            // Besplatno: Balkan (svi), pa BiH, Srbija, Hrvatska, ostale; pa moje; pa kategorije
            items.add(new SideItem(SideItem.HEADER, null, repo.links().isEmpty() ? "Balkan" : "Kanali", 0));
            SideItem allItem = new SideItem(SideItem.ALL, null, "Svi kanali", all.size());
            allItem.icon = R.drawable.ic_globe;
            items.add(allItem);
            for (String code : sortedCountries(countries.keySet())) {
                SideItem it = new SideItem(SideItem.COUNTRY, code, Labels.countryName(code), countries.get(code));
                it.emoji = Labels.flag(code);
                items.add(it);
            }
            items.add(new SideItem(SideItem.HEADER, null, "Moje", 0));
            addLibrary(items);
            addLists(items, all, false);
            if (cats.size() > 1) {
                items.add(new SideItem(SideItem.HEADER, null, "Kategorije", 0));
                for (String k : sortedCategories(cats.keySet(), false)) {
                    SideItem it = new SideItem(SideItem.CATEGORY, k, Labels.category(k), cats.get(k));
                    it.icon = R.drawable.ic_tag;
                    items.add(it);
                }
            }
        } else {
            // Prijava: svi, moje, filmovi/serije, drzave, prijave, kategorije servera (balkanske prve)
            items.add(new SideItem(SideItem.HEADER, null, "Biblioteka", 0));
            SideItem allItem = new SideItem(SideItem.ALL, null, "Svi kanali", all.size());
            allItem.icon = R.drawable.ic_grid;
            items.add(allItem);
            addLibrary(items);
            // filmovi i serije imaju svoj ekran (otvara se sa OK)
            SideItem movies = new SideItem(SideItem.MOVIES, null, "Filmovi", 0);
            movies.icon = R.drawable.ic_movie;
            items.add(movies);
            SideItem series = new SideItem(SideItem.SERIES, null, "Serije", 0);
            series.icon = R.drawable.ic_series;
            items.add(series);
            // drzave (BiH, Srbija, Hrvatska prve), pa prijave, pa kategorije servera
            if (countries.size() > 0) {
                items.add(new SideItem(SideItem.HEADER, null, "Države", 0));
                for (String code : sortedCountries(countries.keySet())) {
                    SideItem it = new SideItem(SideItem.COUNTRY, code, Labels.countryName(code), countries.get(code));
                    it.emoji = Labels.flag(code);
                    items.add(it);
                }
            }
            addLists(items, all, true);
            if (cats.size() > 1) {
                items.add(new SideItem(SideItem.HEADER, null, "Kategorije", 0));
                for (String k : sortedCategories(cats.keySet(), true)) {
                    SideItem it = new SideItem(SideItem.CATEGORY, k, Labels.category(k), cats.get(k));
                    it.icon = R.drawable.ic_tag;
                    items.add(it);
                }
            }
        }

        // zadrzi prethodni odabir ako jos postoji, inace "Svi kanali"
        int sel = -1;
        if (selected != null) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).sameAs(selected)) { sel = i; break; }
        }
        if (sel < 0) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).type == SideItem.ALL) { sel = i; break; }
        }
        sideAdapter.selected = sel;
        sideAdapter.setItems(items);
        selected = items.get(sel);
    }

    /**
     * Filter po listi: u profilu prijava kad ima vise prijava, u besplatnom kad ima
     * vlastitih linkova. Lista koja se nije ucitala ostaje vidljiva sa 0 kanala.
     */
    private void addLists(List<SideItem> items, List<Channel> all, boolean loginsProfile) {
        List<Repo.Source> own = loginsProfile ? repo.logins() : repo.links();
        if (loginsProfile ? own.size() < 2 : own.isEmpty()) return;
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (!loginsProfile) { ids.add("free"); names.add("Balkanske liste"); }
        for (Repo.Source s : own) { ids.add(s.id); names.add(s.name); }
        items.add(new SideItem(SideItem.HEADER, null, loginsProfile ? "Prijave" : "Liste", 0));
        for (int i = 0; i < ids.size(); i++) {
            int n = 0;
            for (Channel c : all) if (c.inList(ids.get(i))) n++;
            SideItem it = new SideItem(SideItem.LIST, ids.get(i), names.get(i), n);
            it.icon = R.drawable.ic_list;
            items.add(it);
        }
    }

    private void addLibrary(List<SideItem> items) {
        SideItem fav = new SideItem(SideItem.FAVORITES, null, "Favoriti", repo.favoriteChannels().size());
        fav.icon = R.drawable.ic_star;
        items.add(fav);
        SideItem rec = new SideItem(SideItem.RECENT, null, "Nedavno gledano", repo.recentCount());
        rec.icon = R.drawable.ic_history;
        items.add(rec);
    }

    /** BiH, Srbija, Hrvatska, Crna Gora, Slovenija, Makedonija, Kosovo, pa ostale abecedno. */
    private static List<String> sortedCountries(Set<String> codes) {
        final Collator collator = Collator.getInstance(new Locale("bs"));
        List<String> list = new ArrayList<>(codes);
        Collections.sort(list, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int pa = PINNED.indexOf(a), pb = PINNED.indexOf(b);
                if (pa < 0) pa = PINNED.size();
                if (pb < 0) pb = PINNED.size();
                if (pa != pb) return pa - pb;
                return collator.compare(Labels.countryName(a), Labels.countryName(b));
            }
        });
        return list;
    }

    /** Kategorije abecedno ("Ostalo" na kraju); za prijavu balkanske kategorije idu prve. */
    private static List<String> sortedCategories(Set<String> keys, final boolean balkanFirst) {
        final Collator collator = Collator.getInstance(new Locale("bs"));
        List<String> list = new ArrayList<>(keys);
        Collections.sort(list, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                boolean ua = a.equalsIgnoreCase("undefined"), ub = b.equalsIgnoreCase("undefined");
                if (ua != ub) return ua ? 1 : -1;
                if (balkanFirst) {
                    int ra = categoryRank(a), rb = categoryRank(b);
                    if (ra != rb) return ra - rb;
                }
                return collator.compare(Labels.category(a), Labels.category(b));
            }
        });
        return list;
    }

    /** 0 = BiH, 1 = Srbija, 2 = Hrvatska, 3 = ostali Balkan / Ex-YU, 9 = ostalo. */
    static int categoryRank(String group) {
        String n = M3uParser.normalize(group).toUpperCase(Locale.ROOT);
        Set<String> t = new HashSet<>(Arrays.asList(n.split("[^A-Z0-9]+")));
        if (anyOf(t, "BIH", "BA", "BOSNA", "BOSNIA", "BOSANSKI")) return 0;
        if (anyOf(t, "SRB", "RS", "SRBIJA", "SERBIA", "SRPSKI")) return 1;
        if (anyOf(t, "HR", "HRV", "CRO", "HRVATSKA", "CROATIA", "HRVATSKI")) return 2;
        if (anyOf(t, "EXYU", "EX", "YU", "YUGO", "BALKAN", "BALKANS", "CG", "MNE", "MONTENEGRO",
                "SLO", "SLOVENIJA", "SLOVENIA", "MK", "MKD", "MAKEDONIJA", "MACEDONIA", "KOSOVO")) return 3;
        return 9;
    }

    private static boolean anyOf(Set<String> tokens, String... words) {
        for (String w : words) if (tokens.contains(w)) return true;
        return false;
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

    /** OK na stavci menija: odaberi je odmah i predji na kanale. */
    @Override
    public void onSideClick(int position) {
        cancelPendingSelect();
        SideItem clicked = sideAdapter.get(position);
        if (clicked != null && (clicked.type == SideItem.MOVIES || clicked.type == SideItem.SERIES)) {
            Intent i = new Intent(this, VodActivity.class);
            i.putExtra(VodActivity.EXTRA_KIND, clicked.type == SideItem.MOVIES ? Vod.MOVIE : Vod.SERIES);
            startActivity(i);
            return;
        }
        select(position);
        focusGrid();
    }

    private void select(int position) {
        SideItem it = sideAdapter.get(position);
        if (it == null || it.type == SideItem.HEADER || it.type == SideItem.MOVIES || it.type == SideItem.SERIES) return;
        boolean changed = !it.sameAs(selected) || !query.isEmpty();
        selected = it;
        sideAdapter.setSelected(position);
        if (!query.isEmpty()) clearSearch();
        if (changed) applyFilter(true);
    }

    private void onFocusMoved(View oldFocus, View newFocus) {
        if (newFocus == null) return;
        if (!isInside(side, newFocus)) {
            cancelPendingSelect();   // fokus je napustio meni -> nista se ne bira
            return;
        }
        if (programmaticFocus) return;  // aplikacija je pomjerila fokus, ne korisnik

        View item = side.findContainingItemView(newFocus);
        final int pos = item != null ? side.getChildAdapterPosition(item) : RecyclerView.NO_POSITION;
        boolean oldInSide = oldFocus != null && isInside(side, oldFocus);

        if (!oldInSide) {
            // ulazak u meni: samo vrati fokus na odabranu stavku, NE mijenjaj prikaz ni pretragu
            final int sel = sideAdapter.selected;
            if (pos != sel) {
                side.post(new Runnable() {
                    @Override public void run() { focusSideItem(sel); }
                });
            }
            return;
        }
        // korisnik se krece gore/dole kroz meni: odaberi stavku kad se zaustavi na njoj
        scheduleSelect(pos);
    }

    private void scheduleSelect(final int pos) {
        cancelPendingSelect();
        final SideItem target = sideAdapter.get(pos);
        if (target == null || target.type == SideItem.HEADER) return;
        // Filmovi/Serije se otvaraju tek na OK, ne kad se fokus samo zaustavi na njima
        if (target.type == SideItem.MOVIES || target.type == SideItem.SERIES) return;
        pendingSelect = new Runnable() {
            @Override public void run() {
                pendingSelect = null;
                // provjeri da je fokus i dalje na toj istoj stavci i da se meni nije promijenio
                View f = getCurrentFocus();
                if (f == null || !isInside(side, f)) return;
                View it = side.findContainingItemView(f);
                if (it == null || side.getChildAdapterPosition(it) != pos) return;
                if (sideAdapter.get(pos) != target) return;
                select(pos);
            }
        };
        handler.postDelayed(pendingSelect, 350);
    }

    private void cancelPendingSelect() {
        if (pendingSelect != null) {
            handler.removeCallbacks(pendingSelect);
            pendingSelect = null;
        }
    }

    private void focusSideItem(final int pos) {
        if (pos < 0 || pos >= sideAdapter.getItemCount()) return;
        RecyclerView.ViewHolder vh = side.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            requestFocusQuietly(vh.itemView);
        } else {
            side.scrollToPosition(pos);
            side.post(new Runnable() {
                @Override public void run() {
                    RecyclerView.ViewHolder v2 = side.findViewHolderForAdapterPosition(pos);
                    if (v2 != null) requestFocusQuietly(v2.itemView);
                }
            });
        }
    }

    private void requestFocusQuietly(View v) {
        programmaticFocus = true;
        try {
            v.requestFocus();
        } finally {
            programmaticFocus = false;
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

    // ------------------------------------------------------------------ filtriranje i sortiranje

    private void applyFilter(boolean scrollTop) {
        boolean xtream = repo.isXtream();
        List<Channel> out;
        String sectionTitle;
        if (!query.isEmpty()) {
            String q = M3uParser.normalize(query);
            out = new ArrayList<>();
            for (Channel c : repo.all) if (c.searchKey.contains(q)) out.add(c);
            if (!xtream) out = prioritize(out, true);
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
                case SideItem.CATEGORY:
                    out = new ArrayList<>();
                    for (Channel c : repo.all) {
                        for (String g : c.groups) if (g.equals(s.key)) { out.add(c); break; }
                    }
                    if (!xtream) out = prioritize(out, true);
                    sectionTitle = s.label;
                    break;
                case SideItem.LIST:
                    out = new ArrayList<>();
                    for (Channel c : repo.all) if (c.inList(s.key)) out.add(c);
                    if (!xtream) out = prioritize(out, true);
                    sectionTitle = s.label;
                    break;
                case SideItem.COUNTRY:
                    out = new ArrayList<>();
                    for (Channel c : repo.all) if (s.key.equals(c.country)) out.add(c);
                    if (!xtream) out = prioritize(out, false);
                    sectionTitle = s.emoji + "  " + s.label;
                    break;
                default:
                    out = xtream ? new ArrayList<>(repo.all) : prioritize(repo.all, true);
                    sectionTitle = xtream || !repo.links().isEmpty() ? "Svi kanali" : "Svi balkanski kanali";
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

    private void updateSubtitle() {
        int n = shownCount;
        String base = nf.format(n) + (n % 10 == 1 && n % 100 != 11 ? " kanal" : " kanala");
        subtitle.setText(healthText.isEmpty() ? base : base + "   ·   " + healthText);
    }

    /**
     * Besplatni kanali: (po zelji) BiH, Srbija, Hrvatska, ostale; unutar toga prvo kanali
     * koji rade, pa neprovjereni, pa mrtvi/blokirani. Sortiranje je stabilno.
     */
    private List<Channel> prioritize(List<Channel> in, final boolean byCountry) {
        List<Channel> out = new ArrayList<>(in);
        Collections.sort(out, new Comparator<Channel>() {
            @Override public int compare(Channel a, Channel b) {
                return rank(a, byCountry) - rank(b, byCountry);
            }
        });
        return out;
    }

    private int rank(Channel c, boolean byCountry) {
        int r = health.rankChannel(c);
        if (byCountry) {
            int i = c.country == null ? -1 : PINNED.indexOf(c.country);
            r += (i < 0 ? PINNED.size() : i) * 10;
        }
        return r;
    }

    /**
     * Provjera dostupnosti samo za besplatne kanale. Za prijavu (Xtream) se NE radi:
     * vise istovremenih konekcija na takav server moze blokirati nalog.
     */
    private void startHealthCheck() {
        if (repo.isXtream()) {
            health.cancel(healthCheckId);
            return;
        }
        healthCheckId = health.check(new ArrayList<>(repo.all), new Health.Progress() {
            @Override public void onProgress(int done, int total, boolean finished) {
                if (isFinishing() || total == 0) return;
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

    private void clearSearch() {
        handler.removeCallbacks(searchRunnable);
        query = "";
        search.setText("");
        handler.removeCallbacks(searchRunnable);
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
    public void onBackPressed() {
        if (!query.isEmpty()) {
            clearSearch();
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
