package ba.lumen.tv;

/** Stavka u bocnom meniju. */
public final class SideItem {
    public static final int HEADER = 0, ALL = 1, FAVORITES = 2, RECENT = 3, CATEGORY = 4, COUNTRY = 5, LIST = 6;

    public final int type;
    public final String key;
    public final String label;
    public int count;
    public int icon;       // drawable ili 0
    public String emoji;   // zastava za drzave

    public SideItem(int type, String key, String label, int count) {
        this.type = type;
        this.key = key;
        this.label = label;
        this.count = count;
    }

    public boolean sameAs(SideItem o) {
        if (o == null || o.type != type) return false;
        return key == null ? o.key == null : key.equals(o.key);
    }
}
