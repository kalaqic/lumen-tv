package ba.lumen.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Jedan kanal iz M3U plejliste.
 *
 * Kad plejlista ima vise linkova za isti kanal, oni se spajaju u jedan kanal:
 * prvi link je sam taj objekat, a svi linkovi (ukljucujuci prvi) su u {@link #sources}.
 */
public final class Channel {
    public String name = "";       // ocisceno ime za prikaz
    public String rawName = "";
    public String url = "";
    public String logo;            // moze biti null
    public String tvgId;
    public String[] groups = new String[0];
    public String country;         // ISO kod (npr. "BA") ili null
    public String quality;         // kvalitet ovog linka, npr. "1080p" ili null
    public String bestQuality;     // najbolji kvalitet medju svim izvorima kanala
    public String userAgent;
    public String referrer;
    public String searchKey = "";  // mala slova bez dijakritika
    public boolean geoBlocked;     // iptv-org oznaka [Geo-blocked]
    public boolean balkan;         // kanal sa Balkana (BA, HR, RS, ME, SI, MK, XK)
    /** Svi linkovi ovog kanala, redom iz plejliste (prvi je ovaj objekat). Null = samo ovaj link. */
    public List<Channel> sources;

    /** Svi izvori kanala (najmanje jedan: sam kanal). */
    public List<Channel> allSources() {
        if (sources == null || sources.isEmpty()) return Collections.singletonList(this);
        return sources;
    }

    public int sourceCount() {
        return sources == null || sources.isEmpty() ? 1 : sources.size();
    }

    /** Svi izvori su oznaceni kao geo-blokirani. */
    public boolean allGeoBlocked() {
        for (Channel s : allSources()) if (!s.geoBlocked) return false;
        return true;
    }

    public String displayQuality() {
        return bestQuality != null ? bestQuality : quality;
    }

    public String initials() {
        String n = name.trim();
        if (n.isEmpty()) return "TV";
        StringBuilder sb = new StringBuilder();
        for (String part : n.split("\\s+")) {
            if (part.isEmpty()) continue;
            int cp = part.codePointAt(0);
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(Character.toUpperCase(cp));
                if (sb.length() >= 2) break;
            }
        }
        return sb.length() == 0 ? "TV" : sb.toString();
    }

    public String primaryGroup() {
        return groups.length > 0 ? groups[0] : "Undefined";
    }

    /** "1080p" -> 1080, null -> 0 */
    static int qualityValue(String q) {
        if (q == null) return 0;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < q.length() && Character.isDigit(q.charAt(i)); i++) d.append(q.charAt(i));
        try { return d.length() == 0 ? 0 : Integer.parseInt(d.toString()); } catch (Exception e) { return 0; }
    }

    static List<Channel> newSourceList(Channel first) {
        List<Channel> l = new ArrayList<>();
        l.add(first);
        return l;
    }
}
