package ba.lumen.tv;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public final class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.VH> {

    public interface Listener {
        void onChannelClick(int position);
        void onChannelLongClick(int position);
    }

    private final List<Channel> items = new ArrayList<>();
    private final Listener listener;
    private final Repo repo;

    public ChannelAdapter(Repo repo, Listener l) {
        this.repo = repo;
        this.listener = l;
        setHasStableIds(true);
    }

    public void setItems(List<Channel> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public List<Channel> items() { return items; }

    @Override public long getItemId(int position) {
        Channel c = items.get(position);
        return ((long) c.url.hashCode() << 32) ^ position;
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel, parent, false);
        Ui.applyFonts(v);
        final VH vh = new VH(v);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                int p = vh.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) listener.onChannelClick(p);
            }
        });
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                int p = vh.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) listener.onChannelLongClick(p);
                return true;
            }
        });
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean hasFocus) {
                Ui.focusAnim(view, hasFocus, 1.09f);
                vh.name.setEllipsize(hasFocus ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
                vh.name.setSelected(hasFocus);
                vh.meta.setTextColor(hasFocus ? 0xFFA3AECA : 0xFF64708A);
            }
        });
        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull VH vh, int position) {
        Channel c = items.get(position);
        vh.name.setText(c.name);
        vh.meta.setText(meta(c));
        if (c.quality != null) {
            vh.quality.setVisibility(View.VISIBLE);
            vh.quality.setText(c.quality.toUpperCase());
        } else {
            vh.quality.setVisibility(View.GONE);
        }
        vh.fav.setVisibility(repo.isFavorite(c) ? View.VISIBLE : View.GONE);
        int st = Health.get(vh.itemView.getContext()).status(c.url);
        vh.onlineDot.setVisibility(st == Health.ONLINE ? View.VISIBLE : View.GONE);
        if (st == Health.OFFLINE || st == Health.BLOCKED) {
            vh.status.setVisibility(View.VISIBLE);
            vh.status.setText(st == Health.BLOCKED ? "BLOKIRAN" : "NE RADI");
            vh.status.setBackgroundResource(R.drawable.bg_status_bad);
            vh.logoBox.setAlpha(0.4f);
            vh.name.setAlpha(0.55f);
        } else if (st == Health.UNKNOWN && c.geoBlocked) {
            vh.status.setVisibility(View.VISIBLE);
            vh.status.setText("GEO");
            vh.status.setBackgroundResource(R.drawable.bg_chip);
            vh.logoBox.setAlpha(1f);
            vh.name.setAlpha(1f);
        } else {
            vh.status.setVisibility(View.GONE);
            vh.logoBox.setAlpha(1f);
            vh.name.setAlpha(1f);
        }
        Logos.bind(vh.logo, vh.initials, c);
        boolean focused = vh.itemView.isFocused();
        vh.itemView.setScaleX(focused ? 1.09f : 1f);
        vh.itemView.setScaleY(focused ? 1.09f : 1f);
    }

    static String meta(Channel c) {
        String cat = Labels.category(c.primaryGroup());
        String v = c.variantLabel();
        if (v != null) cat = cat + "  ·  " + v;
        if (c.country != null) return Labels.flag(c.country) + "  " + cat;
        return cat;
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView name, meta, quality, initials, status;
        final ImageView logo, fav;
        final View logoBox, onlineDot;
        VH(View v) {
            super(v);
            status = v.findViewById(R.id.status);
            logoBox = v.findViewById(R.id.logo_box);
            onlineDot = v.findViewById(R.id.online_dot);
            name = v.findViewById(R.id.name);
            meta = v.findViewById(R.id.meta);
            quality = v.findViewById(R.id.quality);
            initials = v.findViewById(R.id.initials);
            logo = v.findViewById(R.id.logo);
            fav = v.findViewById(R.id.fav);
        }
    }
}
