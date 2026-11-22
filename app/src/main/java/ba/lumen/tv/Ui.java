package ba.lumen.tv;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

/** Pomocne funkcije za izgled: fontovi, animacije fokusa, dp. */
public final class Ui {
    private Ui() {}

    private static Typeface regular, medium, semibold, bold;

    public static void init(Context c) {
        if (regular != null) return;
        AssetManager am = c.getAssets();
        regular = load(am, "fonts/Inter-Regular.otf", Typeface.DEFAULT);
        medium = load(am, "fonts/Inter-Medium.otf", regular);
        semibold = load(am, "fonts/Inter-SemiBold.otf", Typeface.DEFAULT_BOLD);
        bold = load(am, "fonts/Inter-Bold.otf", semibold);
    }

    private static Typeface load(AssetManager am, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(am, path);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static Typeface font(String weight) {
        if (regular == null) return Typeface.DEFAULT;
        if ("bold".equals(weight)) return bold;
        if ("semibold".equals(weight)) return semibold;
        if ("medium".equals(weight)) return medium;
        return regular;
    }

    /** Postavlja Inter font na sve TextView-ove. Tezina se cita iz android:tag. */
    public static void applyFonts(View v) {
        if (v instanceof TextView) {
            Object tag = v.getTag();
            if ("system".equals(tag)) return; // emoji / zastave koriste sistemski font
            ((TextView) v).setTypeface(font(tag instanceof String ? (String) tag : null));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyFonts(g.getChildAt(i));
        }
    }

    public static int dp(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    public static void focusAnim(View v, boolean focused, float scale) {
        float s = focused ? scale : 1f;
        v.animate().scaleX(s).scaleY(s).translationZ(focused ? dp(v.getContext(), 10) : 0f)
                .setDuration(focused ? 180 : 140).setInterpolator(EASE).start();
    }
}
