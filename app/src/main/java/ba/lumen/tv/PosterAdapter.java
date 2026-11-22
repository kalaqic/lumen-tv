package ba.lumen.tv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.ArrayList;
import java.util.List;

/** Mreza postera za filmove i serije. */
public final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.VH> {

    public interface Listener {
        void onPosterClick(Vod.Item item);
    }

    private final List<Vod.Item> items = new ArrayList<>();
    private final Listener listener;
    private final Vod vod;
    private int posterHeight;

    public PosterAdapter(Vod vod, Listener l) {
        this.vod = vod;
        this.listener = l;
    }

    public void setPosterHeight(int px) { posterHeight = px; }

    public void setItems(List<Vod.Item> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public Vod.Item get(int pos) {
        return pos >= 0 && pos < items.size() ? items.get(pos) : null;
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_poster, parent, false);
        Ui.applyFonts(v);
        final VH vh = new VH(v);
        vh.box.setClipToOutline(true);   // zaobljeni uglovi i na slici
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Vod.Item it = get(vh.getBindingAdapterPosition());
                if (it != null) listener.onPosterClick(it);
            }
        });
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean hasFocus) {
                Ui.focusAnim(view, hasFocus, 1.05f);
                vh.meta.setTextColor(hasFocus ? 0xFFA6AAB2 : 0xFF6C717B);
            }
        });
        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull VH vh, int position) {
        Vod.Item it = items.get(position);
        if (posterHeight > 0 && vh.box.getLayoutParams().height != posterHeight) {
            vh.box.getLayoutParams().height = posterHeight;
            vh.box.requestLayout();
        }
        vh.title.setText(it.title);
        vh.meta.setText(it.meta());
        vh.text.setText(it.title);
        vh.text.setVisibility(View.VISIBLE);
        if (it.poster != null) {
            Glide.with(vh.poster)
                    .load(it.poster)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(300, 450)
                    .centerCrop()
                    .into(vh.poster);
        } else {
            Glide.with(vh.poster).clear(vh.poster);
            vh.poster.setImageDrawable(null);
        }
        // traka "odgledano" (samo filmovi; serije imaju po epizodi)
        long[] p = it.kind == Vod.MOVIE ? vod.position(Vod.movieKey(it)) : null;
        if (p != null && p[1] > 0 && p[0] > 60000) {
            vh.watched.setVisibility(View.VISIBLE);
            vh.watched.setProgress((int) Math.min(1000, p[0] * 1000 / p[1]));
        } else {
            vh.watched.setVisibility(View.GONE);
        }
    }

    static final class VH extends RecyclerView.ViewHolder {
        final View box;
        final ImageView poster;
        final TextView text, title, meta;
        final ProgressBar watched;

        VH(View v) {
            super(v);
            box = v.findViewById(R.id.poster_box);
            poster = v.findViewById(R.id.poster);
            text = v.findViewById(R.id.poster_text);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            watched = v.findViewById(R.id.watched);
        }
    }
}
