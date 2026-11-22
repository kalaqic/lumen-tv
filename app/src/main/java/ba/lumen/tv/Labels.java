package ba.lumen.tv;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Prevodi kategorija, nazivi drzava i zastave. */
public final class Labels {
    private Labels() {}

    private static final Map<String, String> CATEGORIES = new HashMap<>();
    private static final Map<String, String> COUNTRY_CACHE = new HashMap<>();
    private static final Locale BS = new Locale("bs");

    static {
        String[][] pairs = {
                {"animation", "Animacija"}, {"auto", "Auto-moto"}, {"business", "Biznis"},
                {"classic", "Klasici"}, {"comedy", "Komedija"}, {"cooking", "Kuhanje"},
                {"culture", "Kultura"}, {"documentary", "Dokumentarni"}, {"education", "Obrazovanje"},
                {"entertainment", "Zabava"}, {"family", "Porodica"}, {"general", "Općenito"},
                {"kids", "Djeca"}, {"legislative", "Parlament"}, {"lifestyle", "Lifestyle"},
                {"movies", "Filmovi"}, {"music", "Muzika"}, {"news", "Vijesti"},
                {"outdoor", "Priroda"}, {"relax", "Opuštanje"}, {"religious", "Religija"},
                {"science", "Nauka"}, {"series", "Serije"}, {"shop", "Kupovina"},
                {"sports", "Sport"}, {"sport", "Sport"}, {"travel", "Putovanja"},
                {"weather", "Vremenska prognoza"}, {"undefined", "Ostalo"}, {"public", "Javni servisi"},
                {"interactive", "Interaktivno"}, {"xxx", "Za odrasle"}, {"local", "Lokalni"},
                {"history", "Historija"}, {"nature", "Priroda"},
                {"balkans", "Balkan"}, {"balkan", "Balkan"}, {"ex-yu", "Ex-YU"}, {"exyu", "Ex-YU"},
                {"documentaries", "Dokumentarni"}, {"cinema", "Filmovi"}, {"film", "Filmovi"}
        };
        for (String[] p : pairs) CATEGORIES.put(p[0], p[1]);
    }

    public static String category(String raw) {
        if (raw == null) return "Ostalo";
        String t = CATEGORIES.get(raw.toLowerCase(Locale.ROOT));
        return t != null ? t : raw;
    }

    public static String countryName(String code) {
        if (code == null) return "Nepoznato";
        String cached = COUNTRY_CACHE.get(code);
        if (cached != null) return cached;
        String name;
        if (code.equals("XK")) {
            name = "Kosovo";
        } else {
            Locale loc = new Locale("", code);
            name = loc.getDisplayCountry(BS);
            if (name == null || name.isEmpty() || name.equalsIgnoreCase(code)) {
                name = loc.getDisplayCountry(Locale.ENGLISH);
            }
            if (name == null || name.isEmpty()) name = code;
            if (name.length() > 0) name = name.substring(0, 1).toUpperCase(BS) + name.substring(1);
        }
        COUNTRY_CACHE.put(code, name);
        return name;
    }

    public static String flag(String code) {
        if (code == null || code.length() != 2) return "🌐"; // globus
        char a = Character.toUpperCase(code.charAt(0));
        char b = Character.toUpperCase(code.charAt(1));
        if (a < 'A' || a > 'Z' || b < 'A' || b > 'Z') return "🌐";
        int first = 0x1F1E6 + (a - 'A');
        int second = 0x1F1E6 + (b - 'A');
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }
}
