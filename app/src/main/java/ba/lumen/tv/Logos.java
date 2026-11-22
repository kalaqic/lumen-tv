package ba.lumen.tv;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

/** Ucitavanje logotipa kanala sa inicijalima kao rezervom. */
public final class Logos {
    private Logos() {}

    public static void bind(final ImageView logo, final TextView initials, Channel c) {
        initials.setText(c.initials());
        initials.setVisibility(View.VISIBLE);
        if (c.logo == null || c.logo.isEmpty()) {
            Glide.with(logo).clear(logo);
            logo.setImageDrawable(null);
            return;
        }
        Glide.with(logo)
                .load(c.logo)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(320, 200)
                .fitCenter()
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                        initials.setVisibility(View.VISIBLE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        initials.setVisibility(View.INVISIBLE);
                        return false;
                    }
                })
                .into(logo);
    }
}
