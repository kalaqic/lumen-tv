package ba.lumen.tv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SidebarAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onSideClick(int position);
    }

    private final List<SideItem> items = new ArrayList<>();
    private final Listener listener;
    private final NumberFormat nf = NumberFormat.getIntegerInstance(new Locale("bs"));
    public int selected = 1;

    public SidebarAdapter(Listener l) { this.listener = l; }

    public void setItems(List<SideItem> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public SideItem get(int pos) { return pos >= 0 && pos < items.size() ? items.get(pos) : null; }

    public List<SideItem> items() { return items; }

    public void setSelected(int pos) {
        int old = selected;
        selected = pos;
        if (old >= 0 && old < items.size()) notifyItemChanged(old);
        if (pos >= 0 && pos < items.size()) notifyItemChanged(pos);
    }

    @Override public int getItemViewType(int position) {
        return items.get(position).type == SideItem.HEADER ? 0 : 1;
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == 0) {
            View v = inf.inflate(R.layout.item_side_header, parent, false);
            Ui.applyFonts(v);
            return new HeaderVH(v);
        }
        View v = inf.inflate(R.layout.item_side, parent, false);
        Ui.applyFonts(v);
        final ItemVH vh = new ItemVH(v);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                int p = vh.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) listener.onSideClick(p);
            }
        });
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean hasFocus) {
                vh.icon.setColorFilter(hasFocus ? 0xFFFFFFFF : (view.isActivated() ? 0xFFF4F6FB : 0xFF8792AD));
            }
        });
        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        SideItem it = items.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).label.setText(it.label.toUpperCase(new Locale("bs")));
            return;
        }
        ItemVH vh = (ItemVH) holder;
        vh.label.setText(it.label);
        vh.count.setText(it.count > 0 ? nf.format(it.count) : "");
        boolean active = position == selected;
        vh.itemView.setActivated(active);
        if (it.emoji != null) {
            vh.emoji.setVisibility(View.VISIBLE);
            vh.emoji.setText(it.emoji);
            vh.icon.setVisibility(View.GONE);
        } else {
            vh.emoji.setVisibility(View.GONE);
            vh.icon.setVisibility(View.VISIBLE);
            vh.icon.setImageResource(it.icon != 0 ? it.icon : R.drawable.ic_tag);
            boolean focused = vh.itemView.hasFocus();
            vh.icon.setColorFilter(focused ? 0xFFFFFFFF : (active ? 0xFFF4F6FB : 0xFF8792AD));
        }
    }

    static final class HeaderVH extends RecyclerView.ViewHolder {
        final TextView label;
        HeaderVH(View v) { super(v); label = (TextView) v; }
    }

    static final class ItemVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView emoji, label, count;
        ItemVH(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            emoji = v.findViewById(R.id.emoji);
            label = v.findViewById(R.id.label);
            count = v.findViewById(R.id.count);
        }
    }
}
