package ba.lumen.tv;

/** Jedan kanal iz M3U plejliste. */
public final class Channel {
    public String name = "";       // ocisceno ime za prikaz
    public String rawName = "";
    public String url = "";
    public String logo;            // moze biti null
    public String tvgId;
    public String[] groups = new String[0];
    public String country;         // ISO kod (npr. "BA") ili null
    public String quality;         // npr. "1080p" ili null
    public String userAgent;
    public String referrer;
    public String searchKey = "";  // mala slova bez dijakritika
    public int variant = 0;        // redni broj kad isti naziv ima vise izvora (1, 2, 3…)
    public int variantCount = 1;

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

    /** "Izvor 2/3" kada plejlista ima vise linkova za isti kanal, inace null. */
    public String variantLabel() {
        return variantCount > 1 ? "Izvor " + variant + "/" + variantCount : null;
    }

    public String primaryGroup() {
        return groups.length > 0 ? groups[0] : "Undefined";
    }
}
