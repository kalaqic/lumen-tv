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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Biblioteka filmova ili serija: kategorije lijevo, posteri desno, pretraga gore.
 * Kategorija se bira kad se fokus zaustavi na njoj (kao na glavnom ekranu).
 */
public class VodActivity extends Activity implements SidebarAdapter.Listener, PosterAdapter.Listener {

    public static final String EXTRA_KIND = "kind";

    private static final String KEY_CONTINUE = "__continue";
    private static final String KEY_ALL = "__all";

    private Vod vod;
    private int kind;
    private Vod.Library lib;

    private RecyclerView side, grid;
    private SidebarAdapter sideAdapter;
    private PosterAdapter posters;
    private GridLayoutManager gridLayout;
    private TextView title, subtitle, stateTitle, stateText, retryBtn;
    private View stateView;
    private ProgressBar progress;
    private EditText search;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SideItem selected;
    private String query = "";
    private Runnable pendingSelect;
    private boolean programmaticFocus;
    private final NumberFormat nf = NumberFormat.getIntegerInstance(new Locale("bs"));

    private final Runnable searchRunnable = new Runnable() {
        @Override public void run() { applyFilter(true); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        setContentView(R.layout.activity_vod);
        Ui.applyFonts(getWindow().getDecorView());
        vod = Vod.get(this);
        kind = getIntent().getIntExtra(EXTRA_KIND, Vod.MOVIE);

        side = findViewById(R.id.side_list);
        grid = findViewById(R.id.grid);
        title = findViewById(R.id.section_title);
        subtitle = findViewById(R.id.section_sub);
        stateView = findViewById(R.id.state_view);
        progress = findViewById(R.id.progress);
        stateTitle = findViewById(R.id.state_title);
        stateText = findViewById(R.id.state_text);
        retryBtn = findViewById(R.id.btn_retry);
        search = findViewById(R.id.search);
        ((TextView) findViewById(R.id.vod_kind)).setText(kind == Vod.MOVIE ? "Filmovi" : "Serije");

        sideAdapter = new SidebarAdapter(this);
        side.setLayoutManager(new LinearLayoutManager(this));
        side.setAdapter(sideAdapter);
        side.setItemAnimator(null);

        posters = new PosterAdapter(vod, this);
        int span = spanCount();
        posters.setPosterHeight(posterHeight(span));
        gridLayout = new GridLayoutManager(this, span);
        grid.setLayoutManager(gridLayout);
        grid.setAdapter(posters);
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.setItemViewCacheSize(24);

        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load(true); }
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
                handler.postDelayed(searchRunnable, 300);
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

        Vod.Library cached = vod.cached(kind);
        if (cached != null) show(cached);
        else load(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lib == null) return;
        // povratak sa gledanja: "nastavi gledati" i trake odgledanog su se promijenile
        rebuildSidebar();
        if (selected != null && KEY_CONTINUE.equals(selected.key) && query.isEmpty()) applyFilter(false);
        else posters.notifyItemRangeChanged(0, posters.getItemCount());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    private int spanCount() {
        float density = getResources().getDisplayMetrics().density;
        float widthDp = getResources().getDisplayMetrics().widthPixels / density;
        float avail = widthDp - 250 - 60;
        return Math.max(3, Math.min(8, (int) (avail / 128)));
    }

    /** Visina postera (2:3) prema sirini kolone. */
    private int posterHeight(int span) {
        float density = getResources().getDisplayMetrics().density;
        float widthDp = getResources().getDisplayMetrics().widthPixels / density;
        float colDp = (widthDp - 250 - 60) / span - 16 - 12;   // margine i padding kartice
        return Math.round(colDp * 1.5f * density);
    }

    // ------------------------------------------------------------------ ucitavanje

    private void load(boolean force) {
        showState(true, kind == Vod.MOVIE ? "Učitavam filmove…" : "Učitavam serije…",
                "Prvi put može potrajati, kasnije se otvara odmah.", false);
        posters.setItems(new ArrayList<Vod.Item>());
        vod.load(kind, force, new Vod.Callback<Vod.Library>() {
            @Override public void onResult(Vod.Library l) {
                if (isFinishing()) return;
                show(l);
                if (!l.failed.isEmpty()) {
                    Toast.makeText(VodActivity.this, "Nije se osvježilo: " + TextUtils.join(", ", l.failed)
                            + " (server ne odgovara)", Toast.LENGTH_LONG).show();
                }
            }
            @Override public void onError(String message) {
                if (isFinishing()) return;
                showState(false, "Ne mogu učitati", message, true);
                retryBtn.requestFocus();
            }
        });
    }

    private void show(Vod.Library l) {
        lib = l;
        rebuildSidebar();
        int start = 0;
        for (int i = 0; i < sideAdapter.getItemCount(); i++) {
            SideItem it = sideAdapter.get(i);
            if (it != null && it.type != SideItem.HEADER) { start = i; break; }
        }
        // ako postoji "Nastavi gledati", kreni od "Novo dodano" (sljedeca stavka)
        SideItem first = sideAdapter.get(start);
        if (first != null && KEY_CONTINUE.equals(first.key) && start + 1 < sideAdapter.getItemCount()) start++;
        selected = null;
        select(start);
        focusGrid();
    }

    private void rebuildSidebar() {
        List<SideItem> items = new ArrayList<>();
        List<Vod.Item> cont = vod.continueWatching(lib);
        if (!cont.isEmpty()) {
            SideItem c = new SideItem(SideItem.RECENT, KEY_CONTINUE, "Nastavi gledati", cont.size());
            c.icon = R.drawable.ic_history;
            items.add(c);
        }
        SideItem all = new SideItem(SideItem.ALL, KEY_ALL, "Novo dodano", lib.items.size());
        all.icon = R.drawable.ic_grid;
        items.add(all);
        if (!lib.categories.isEmpty()) {
            items.add(new SideItem(SideItem.HEADER, null, "Kategorije", 0));
            for (Vod.Category c : lib.categories) {
                SideItem it = new SideItem(SideItem.CATEGORY, c.key, c.name, c.count);
                it.icon = R.drawable.ic_tag;
                items.add(it);
            }
        }
        sideAdapter.setItems(items);
        // zadrzi izabranu stavku
        int sel = -1;
        if (selected != null) {
            for (int i = 0; i < items.size(); i++) {
                SideItem it = items.get(i);
                if (it.type == selected.type && TextUtils.equals(it.key, selected.key)) { sel = i; break; }
            }
        }
        if (sel >= 0) sideAdapter.setSelected(sel);
        else if (selected != null && KEY_CONTINUE.equals(selected.key)) {
            // "Nastavi gledati" je nestao (sve odgledano) -> Novo dodano
            for (int i = 0; i < items.size(); i++) if (KEY_ALL.equals(items.get(i).key)) { selected = null; select(i); break; }
        }
    }

    private void applyFilter(boolean scrollTop) {
        if (lib == null) return;
        List<Vod.Item> out;
        String head;
        if (!query.isEmpty()) {
            out = new ArrayList<>();
            String[] words = M3uParser.normalize(query).split("\\s+");
            for (Vod.Item it : lib.items) {
                boolean ok = true;
                for (String w : words) if (!w.isEmpty() && !it.searchKey.contains(w)) { ok = false; break; }
                if (ok) out.add(it);
            }
            head = "Pretraga: „" + query + "“";
        } else if (selected == null || KEY_ALL.equals(selected.key)) {
            out = lib.items;
            head = "Novo dodano";
        } else if (KEY_CONTINUE.equals(selected.key)) {
            out = vod.continueWatching(lib);
            head = "Nastavi gledati";
        } else {
            out = new ArrayList<>();
            for (Vod.Item it : lib.items) if (it.cats.contains(selected.key)) out.add(it);
            head = selected.label;
        }
        title.setText(head);
        subtitle.setText(count(out.size()));
        posters.setItems(out);
        if (scrollTop) grid.scrollToPosition(0);
        if (out.isEmpty()) {
            showState(false, query.isEmpty() ? "Prazno" : "Nema rezultata",
                    query.isEmpty() ? "U ovoj kategoriji nema naslova." : "Pokušaj s drugim nazivom.", false);
        } else {
            stateView.setVisibility(View.GONE);
        }
    }

    private String count(int n) {
        String w = kind == Vod.MOVIE
                ? WelcomeActivity.plural(n, "film", "filma", "filmova")
                : WelcomeActivity.plural(n, "serija", "serije", "serija");
        return n == 0 ? "" : w.replace(String.valueOf(n), nf.format(n));
    }

    private void showState(boolean loading, String t, String text, boolean retry) {
        stateView.setVisibility(View.VISIBLE);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        stateTitle.setText(t);
        stateText.setText(text == null ? "" : text);
        retryBtn.setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ meni i fokus

    private void select(int position) {
        SideItem it = sideAdapter.get(position);
        if (it == null || it.type == SideItem.HEADER) return;
        boolean changed = selected == null || !(it.type == selected.type && TextUtils.equals(it.key, selected.key))
                || !query.isEmpty();
        selected = it;
        sideAdapter.setSelected(position);
        if (!query.isEmpty()) clearSearch();
        if (changed) applyFilter(true);
    }

    @Override
    public void onSideClick(int position) {
        cancelPendingSelect();
        select(position);
        focusGrid();
    }

    private void onFocusMoved(View oldFocus, View newFocus) {
        if (newFocus == null) return;
        if (!isInside(side, newFocus)) {
            cancelPendingSelect();
            return;
        }
        if (programmaticFocus) return;
        View item = side.findContainingItemView(newFocus);
        final int pos = item != null ? side.getChildAdapterPosition(item) : RecyclerView.NO_POSITION;
        boolean oldInSide = oldFocus != null && isInside(side, oldFocus);
        if (!oldInSide) {
            // ulazak u meni: fokus na izabranu kategoriju, bez mijenjanja prikaza
            final int sel = sideAdapter.selected;
            if (pos != sel) {
                side.post(new Runnable() {
                    @Override public void run() { focusSideItem(sel); }
                });
            }
            return;
        }
        scheduleSelect(pos);
    }

    private void scheduleSelect(final int pos) {
        cancelPendingSelect();
        final SideItem target = sideAdapter.get(pos);
        if (target == null || target.type == SideItem.HEADER) return;
        pendingSelect = new Runnable() {
            @Override public void run() {
                pendingSelect = null;
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

    private void focusGrid() {
        if (posters.getItemCount() == 0) {
            focusSideItem(sideAdapter.selected);
            return;
        }
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

    // ------------------------------------------------------------------ otvaranje

    @Override
    public void onPosterClick(Vod.Item item) {
        Intent i = new Intent(this, VodDetailActivity.class);
        i.putExtra(VodDetailActivity.EXTRA_KIND, kind);
        i.putExtra(VodDetailActivity.EXTRA_KEY, item.key);
        startActivity(i);
    }

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
}
