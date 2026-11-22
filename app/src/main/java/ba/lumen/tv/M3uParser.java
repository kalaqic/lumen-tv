package ba.lumen.tv;

import java.io.BufferedReader;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Brzi, tolerantni parser za M3U / M3U8 IPTV plejliste. */
public final class M3uParser {

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_:-]+)=\"([^\"]*)\"");
    private static final Pattern QUALITY = Pattern.compile("\\s*\\((\\d{3,4}[pi])\\)");
    private static final Pattern BRACKETS = Pattern.compile("\\s*\\[[^\\]]*\\]");
    private static final Pattern COUNTRY = Pattern.compile("\\.([a-z]{2})(?:@|$)");
    private static final Pattern MARKS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    /** Oznaka koju Repo upise ispred svake liste kad spaja vise lista u jedan fajl. */
    static final String LIST_MARK = "#LUMEN-LIST:";

    /** Prefiks drzave u nazivu: "BA: ", "|SRB| ", "[HR] ", "EX-YU - " ... */
    private static final Pattern NAME_PREFIX = Pattern.compile(
            "^[\\[(|]?\\s*(EX-?YU|[A-Za-z]{2,3})\\s*[\\])|:\\-]+\\s*(?=\\S)");
    /** Oznake kvaliteta/kodeka koje ne mijenjaju koji je to kanal. */
    private static final Set<String> QUALITY_WORDS = new HashSet<>(Arrays.asList(
            "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "hevc", "h265", "h264", "x265",
            "1080p", "1080i", "720p", "576p", "576i", "480p", "2160p", "50fps", "60fps", "fps",
            "raw", "backup", "bkp", "multi", "audio", "tv"));
    private static final Map<String, String> PREFIX_CODES = new HashMap<>();
    static {
        String[][] m = {
                {"BA", "BA"}, {"BIH", "BA"}, {"BH", "BA"},
                {"RS", "RS"}, {"SRB", "RS"},
                {"HR", "HR"}, {"HRV", "HR"}, {"CRO", "HR"},
                {"ME", "ME"}, {"MNE", "ME"}, {"CG", "ME"},
                {"SI", "SI"}, {"SLO", "SI"},
                {"MK", "MK"}, {"MKD", "MK"},
                {"EXYU", "EXYU"}, {"YU", "EXYU"},
                {"UK", "GB"}, {"GB", "GB"}, {"US", "US"}, {"USA", "US"}, {"DE", "DE"}, {"GER", "DE"},
                {"AT", "AT"}, {"CH", "CH"}, {"FR", "FR"}, {"IT", "IT"}, {"ES", "ES"}, {"TR", "TR"},
                {"NL", "NL"}, {"PL", "PL"}, {"AL", "AL"}, {"RO", "RO"}, {"HU", "HU"}, {"BG", "BG"},
                {"GR", "GR"}, {"PT", "PT"}, {"SE", "SE"}, {"DK", "DK"}, {"NO", "NO"}, {"CZ", "CZ"},
                {"SK", "SK"}, {"RU", "RU"}, {"AR", "AR"}, {"IN", "IN"}, {"CA", "CA"}, {"AU", "AU"},
                {"XK", "XK"}, {"KS", "XK"}
        };
        for (String[] e : m) PREFIX_CODES.put(e[0], e[1]);
    }

    private static final Set<String> BALKAN = new HashSet<>(Arrays.asList("BA", "HR", "RS", "ME", "SI", "MK", "XK"));

    private M3uParser() {}

    static boolean isBalkan(Channel c) {
        if (c.country != null && BALKAN.contains(c.country)) return true;
        for (String g : c.groups) {
            String l = g.toLowerCase(Locale.ROOT);
            if (l.contains("balkan") || l.contains("ex-yu") || l.contains("exyu") || l.contains("yugo")
                    || l.equals("bih") || l.equals("bosna") || l.equals("hrvatska") || l.equals("srbija")) return true;
        }
        return false;
    }

    public static List<Channel> parse(BufferedReader reader) throws IOException {
        return parse(reader, false);
    }

    /**
     * @param smartMerge spoji isti kanal i kad je u razlicitim listama/kategorijama
     *                   (profil "Moje prijave"), inace samo unutar iste kategorije.
     */
    public static List<Channel> parse(BufferedReader reader, boolean smartMerge) throws IOException {
        List<Channel> out = new ArrayList<>();
        String listId = null;
        Set<String> seenUrls = new HashSet<>();
        Channel cur = null;
        String line;
        boolean first = true;
        while ((line = reader.readLine()) != null) {
            if (first) {
                // ukloni BOM ako postoji
                if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1);
                first = false;
            }
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.regionMatches(true, 0, "#EXTINF", 0, 7)) {
                cur = parseInf(line);
            } else if (line.regionMatches(true, 0, "#EXTVLCOPT", 0, 10)) {
                if (cur != null) {
                    int colon = line.indexOf(':');
                    if (colon > 0) applyOption(cur, line.substring(colon + 1));
                }
            } else if (line.regionMatches(true, 0, "#EXTGRP:", 0, 8)) {
                if (cur != null && cur.groups.length == 0) {
                    cur.groups = splitGroups(line.substring(8));
                }
            } else if (line.startsWith(LIST_MARK)) {
                // nova lista pocinje: linkovi poslije ovoga su iz nje
                String id = line.substring(LIST_MARK.length()).trim();
                listId = id.isEmpty() ? null : id;
                cur = null;
            } else if (line.charAt(0) == '#') {
                // ostali tagovi se ignorisu
            } else {
                if (cur == null) {
                    cur = new Channel();
                    cur.rawName = line;
                    cur.name = nameFromUrl(line);
                }
                String url = line;
                int pipe = url.indexOf('|');
                if (pipe > 0) {
                    // Kodi stil: url|User-Agent=xxx&Referer=yyy
                    String extra = url.substring(pipe + 1);
                    url = url.substring(0, pipe);
                    for (String kv : extra.split("&")) applyOption(cur, kv);
                }
                cur.url = url.trim();
                if (cur.groups.length == 0) cur.groups = new String[]{"Undefined"};
                cur.searchKey = normalize(cur.name);
                cur.balkan = isBalkan(cur);
                cur.listId = listId;
                // filmovi i serije iz Xtream liste nisu kanali (imaju svoj ekran)
                boolean vod = smartMerge && isVodUrl(cur.url);
                if (!vod && !cur.url.isEmpty() && seenUrls.add(cur.url)) out.add(cur);
                cur = null;
            }
        }
        return smartMerge ? groupSmart(out) : group(out);
    }

    /**
     * Spoji linkove istog kanala (isti naziv, ista grupa, ista drzava) u jedan kanal.
     * Redoslijed kanala ostaje kao u plejlisti; izvori unutar kanala takodje.
     */
    static List<Channel> group(List<Channel> list) {
        Map<String, Channel> byKey = new LinkedHashMap<>();
        for (Channel c : list) {
            String k = c.searchKey + "|" + c.primaryGroup().toLowerCase(Locale.ROOT) + "|"
                    + (c.country == null ? "" : c.country);
            Channel first = byKey.get(k);
            if (first == null) {
                c.sources = Channel.newSourceList(c);
                c.bestQuality = c.quality;
                byKey.put(k, c);
            } else {
                first.sources.add(c);
                if (first.logo == null && c.logo != null) first.logo = c.logo;
                if (first.tvgId == null && c.tvgId != null) first.tvgId = c.tvgId;
                if (Channel.qualityValue(c.quality) > Channel.qualityValue(first.bestQuality)) {
                    first.bestQuality = c.quality;
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Spajanje za profil prijava: isti kanal iz razlicitih lista (ili kategorija) postaje
     * jedan kanal sa vise izvora. Kljuc je naziv bez oznaka kvaliteta i prefiksa drzave,
     * plus drzava (iz prefiksa, kategorije ili tvg-country), da se npr. "Arena Sport 1"
     * za BiH i za Srbiju ne pomijesaju.
     */
    static List<Channel> groupSmart(List<Channel> list) {
        Map<String, Channel> byKey = new LinkedHashMap<>();
        for (Channel c : list) {
            String k = smartKey(c);
            Channel first = byKey.get(k);
            if (first == null) {
                c.sources = Channel.newSourceList(c);
                c.bestQuality = c.quality;
                byKey.put(k, c);
            } else {
                first.sources.add(c);
                if (first.logo == null && c.logo != null) first.logo = c.logo;
                if (first.tvgId == null && c.tvgId != null) first.tvgId = c.tvgId;
                if (first.country == null && c.country != null) first.country = c.country;
                if (!first.balkan && c.balkan) first.balkan = true;
                if (Channel.qualityValue(c.quality) > Channel.qualityValue(first.bestQuality)) {
                    first.bestQuality = c.quality;
                }
                // kanal se vidi u kategorijama svih lista iz kojih dolazi
                List<String> g = new ArrayList<>(Arrays.asList(first.groups));
                boolean added = false;
                for (String x : c.groups) {
                    if (!"Undefined".equals(x) && !g.contains(x)) { g.add(x); added = true; }
                }
                if (added) {
                    g.remove("Undefined");
                    first.groups = g.toArray(new String[0]);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    static String smartKey(Channel c) {
        String name = c.name == null ? "" : c.name.trim();
        String region = null;
        // prefiks drzave moze biti i dvostruk ("|EX-YU| BA: ...") - zadnji je najtacniji
        for (int i = 0; i < 2; i++) {
            Matcher m = NAME_PREFIX.matcher(name);
            if (!m.find()) break;
            String code = PREFIX_CODES.get(m.group(1).toUpperCase(Locale.ROOT).replace("-", ""));
            if (code == null) break;
            if (region == null || !"EXYU".equals(code)) region = code;
            name = name.substring(m.end());
        }
        if (region == null || "EXYU".equals(region)) {
            String fromGroup = regionOfGroups(c.groups);
            if (fromGroup != null) region = fromGroup;
        }
        if (region == null || "EXYU".equals(region)) {
            if (c.country != null) region = c.country;
        }
        StringBuilder k = new StringBuilder();
        for (String w : normalize(name).split("[^a-z0-9+]+")) {
            if (w.isEmpty() || QUALITY_WORDS.contains(w)) continue;
            if (k.length() > 0) k.append(' ');
            k.append(w);
        }
        String key = k.length() == 0 ? normalize(name) : k.toString();
        return key + "|" + (region == null ? "" : region);
    }

    /** Drzava iz naziva kategorije ("BIH", "Srbija HD", "HRVATSKA" ...), ili null. */
    static String regionOfGroups(String[] groups) {
        for (String g : groups) {
            Set<String> t = new HashSet<>(Arrays.asList(
                    normalize(g).toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")));
            if (has(t, "BIH", "BA", "BOSNA", "BOSNIA", "BOSANSKI", "FBIH")) return "BA";
            if (has(t, "SRB", "RS", "SRBIJA", "SERBIA", "SRPSKI")) return "RS";
            if (has(t, "HR", "HRV", "CRO", "HRVATSKA", "CROATIA", "HRVATSKI")) return "HR";
            if (has(t, "CG", "MNE", "MONTENEGRO", "CRNA")) return "ME";
            if (has(t, "SLO", "SLOVENIJA", "SLOVENIA")) return "SI";
            if (has(t, "MK", "MKD", "MAKEDONIJA", "MACEDONIA")) return "MK";
        }
        return null;
    }

    private static boolean has(Set<String> t, String... words) {
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    static Channel parseInf(String line) {
        Channel c = new Channel();
        int start = line.indexOf(':') + 1;
        if (start <= 0) start = line.length();
        boolean inQuotes = false;
        int comma = -1;
        for (int i = start; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') inQuotes = !inQuotes;
            else if (ch == ',' && !inQuotes) { comma = i; break; }
        }
        String attrs = comma >= 0 ? line.substring(start, comma) : line.substring(start);
        String name = comma >= 0 ? line.substring(comma + 1).trim() : "";
        String tvgName = null;

        Matcher m = ATTR.matcher(attrs);
        while (m.find()) {
            String k = m.group(1).toLowerCase(Locale.ROOT);
            String v = m.group(2).trim();
            if (v.isEmpty()) continue;
            switch (k) {
                case "tvg-logo":
                case "logo":
                    c.logo = v; break;
                case "tvg-id":
                    c.tvgId = v; break;
                case "tvg-name":
                    tvgName = v; break;
                case "group-title":
                    c.groups = splitGroups(v); break;
                case "http-user-agent":
                case "user-agent":
                    c.userAgent = v; break;
                case "http-referrer":
                case "http-referer":
                case "referrer":
                case "referer":
                    c.referrer = v; break;
                case "tvg-country":
                    if (v.length() == 2) c.country = v.toUpperCase(Locale.ROOT);
                    break;
                default:
                    break;
            }
        }
        if (name.isEmpty() && tvgName != null) name = tvgName;
        c.rawName = name;

        String lowerName = name.toLowerCase(Locale.ROOT);
        if (lowerName.contains("[geo-blocked]") || lowerName.contains("[geo blocked]")) c.geoBlocked = true;

        Matcher qm = QUALITY.matcher(name);
        if (qm.find()) {
            c.quality = qm.group(1);
            name = qm.replaceAll("");
        }
        name = BRACKETS.matcher(name).replaceAll("").trim();
        if (name.isEmpty()) name = c.rawName.isEmpty() ? "Kanal" : c.rawName;
        c.name = name;

        if (c.country == null && c.tvgId != null) {
            Matcher cm = COUNTRY.matcher(c.tvgId.toLowerCase(Locale.ROOT));
            if (cm.find()) {
                String cc = cm.group(1).toUpperCase(Locale.ROOT);
                if (cc.equals("UK")) cc = "GB";
                c.country = cc;
            }
        }
        return c;
    }

    private static void applyOption(Channel c, String kv) {
        int eq = kv.indexOf('=');
        if (eq <= 0) return;
        String k = kv.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        String v = kv.substring(eq + 1).trim();
        if (v.isEmpty()) return;
        if (k.equals("http-user-agent") || k.equals("user-agent")) c.userAgent = v;
        else if (k.equals("http-referrer") || k.equals("http-referer") || k.equals("referer") || k.equals("referrer")) c.referrer = v;
    }

    static String[] splitGroups(String v) {
        String[] parts = v.split(";");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    private static String nameFromUrl(String url) {
        String s = url;
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        return s.isEmpty() ? "Kanal" : s;
    }

    /** Xtream film ili epizoda serije: ".../movie/U/P/123.mkv", ".../series/U/P/456.mp4". */
    static boolean isVodUrl(String url) {
        if (url == null) return false;
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash >= 0 ? path.substring(slash) : "";
        }
        return path.startsWith("/movie/") || path.startsWith("/series/");
    }

    public static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        n = MARKS.matcher(n).replaceAll("");
        return n.replace('đ', 'd').replace("ß", "ss");
    }
}
