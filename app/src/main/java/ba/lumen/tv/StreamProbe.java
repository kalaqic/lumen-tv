package ba.lumen.tv;

import androidx.media3.common.MimeTypes;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Provjerava sta link zaista vraca (HLS, DASH ili obican TS/MP4 stream).
 * Potrebno za linkove bez ekstenzije, npr. ".../play.php?stream=123" koji preusmjeravaju na pravi stream.
 */
final class StreamProbe {
    private StreamProbe() {}

    /** @return mime tip za HLS/DASH, "" za obican stream, ili null ako se ne moze utvrditi */
    static String detect(Channel c) {
        HttpURLConnection conn = null;
        try {
            String current = c.url;
            for (int hop = 0; hop < 6; hop++) {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(7000);
                conn.setRequestProperty("User-Agent", c.userAgent != null ? c.userAgent : Repo.UA);
                if (c.referrer != null) conn.setRequestProperty("Referer", c.referrer);
                conn.setRequestProperty("Accept", "*/*");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (loc == null) return null;
                    current = new URL(new URL(current), loc).toString();
                    String byUrl = fromUrl(current);
                    if (byUrl != null) return byUrl;
                    continue;
                }
                if (code >= 400) return null;

                String type = conn.getContentType();
                if (type != null) {
                    String t = type.toLowerCase(Locale.ROOT);
                    if (t.contains("mpegurl")) return MimeTypes.APPLICATION_M3U8;
                    if (t.contains("dash+xml")) return MimeTypes.APPLICATION_MPD;
                    if (t.contains("mp2t") || t.startsWith("video/") || t.startsWith("audio/")) return "";
                }
                // pogledaj prvih par bajtova
                byte[] buf = new byte[512];
                int total = 0;
                InputStream in = conn.getInputStream();
                while (total < buf.length) {
                    int n = in.read(buf, total, buf.length - total);
                    if (n <= 0) break;
                    total += n;
                }
                if (total == 0) return null;
                if ((buf[0] & 0xFF) == 0x47) return ""; // MPEG-TS sync bajt
                String head = new String(buf, 0, total, "ISO-8859-1").trim();
                if (head.startsWith("﻿")) head = head.substring(1);
                if (head.startsWith("#EXTM3U") || head.startsWith("#EXT-X")) return MimeTypes.APPLICATION_M3U8;
                if (head.contains("<MPD")) return MimeTypes.APPLICATION_MPD;
                return "";
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Provjera da li stream zaista radi: Health.ONLINE / OFFLINE / BLOCKED.
     *
     * Za HLS nije dovoljno da server vrati listu (.m3u8) – cesto lista radi, a sam video je
     * blokiran ili mrtav. Zato se ide do kraja: glavna lista -> lista kvaliteta -> zadnji
     * (najnoviji) video dio, i provjeri se da taj dio stvarno stize.
     */
    static int check(Channel c) {
        try {
            Fetch f = fetch(c, c.url, 64 * 1024);
            if (f.status != Health.ONLINE) return f.status;
            if (f.isHtml()) return f.htmlStatus();
            if (f.isHls()) return checkHls(c, f, 0);
            return f.length > 0 ? Health.ONLINE : Health.OFFLINE;   // TS / MP4: stigli su podaci
        } catch (Exception e) {
            return Health.OFFLINE;
        }
    }

    private static int checkHls(Channel c, Fetch playlist, int depth) throws Exception {
        if (depth > 3) return Health.OFFLINE;
        String text = playlist.text();
        String[] lines = text.split("\r?\n");

        if (text.contains("#EXT-X-STREAM-INF")) {
            // glavna lista: uzmi prvi kvalitet (link u redu nakon #EXT-X-STREAM-INF)
            String variant = null;
            for (int i = 0; i < lines.length && variant == null; i++) {
                if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
                    for (int j = i + 1; j < lines.length; j++) {
                        String l = lines[j].trim();
                        if (l.isEmpty() || l.startsWith("#")) continue;
                        variant = l;
                        break;
                    }
                }
            }
            if (variant == null) return Health.OFFLINE;
            Fetch v = fetch(c, resolve(playlist.finalUrl, variant), 64 * 1024);
            if (v.status != Health.ONLINE) return v.status;
            if (v.isHtml()) return v.htmlStatus();
            return checkHls(c, v, depth + 1);
        }

        // lista video dijelova: provjeri zadnji (najnoviji) dio; ako lista nije ucitana
        // cijela (preduga), zadnji red moze biti odsjecen pa se uzima prvi dio
        boolean truncated = playlist.length >= playlist.data.length;
        String segment = null;
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            segment = l;
            if (truncated) break;
        }
        if (segment == null) return Health.OFFLINE;          // prazna lista = stream ne emituje
        String segUrl = resolve(playlist.finalUrl, segment);
        Fetch s = fetch(c, segUrl, 4096);
        if (s.status != Health.ONLINE) return s.status;
        if (s.length == 0) return Health.OFFLINE;
        if (s.isHtml()) return s.htmlStatus();
        if (s.isHls()) return checkHls(c, s, depth + 1);       // ugnijezdena lista
        return Health.ONLINE;
    }

    private static String resolve(String base, String ref) throws Exception {
        return new URL(new URL(base), ref).toString();
    }

    /** Rezultat jednog HTTP zahtjeva (sa pracenjem preusmjeravanja). */
    private static final class Fetch {
        int status = Health.ONLINE;
        String finalUrl;
        String contentType;
        byte[] data = new byte[0];
        int length;

        String text() throws Exception {
            String t = new String(data, 0, length, "UTF-8");
            return t.startsWith("\uFEFF") ? t.substring(1) : t;
        }

        boolean isHls() throws Exception {
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("mpegurl")) return true;
            String t = text().trim();
            return t.startsWith("#EXTM3U") || t.startsWith("#EXT-X");
        }

        boolean isHtml() throws Exception {
            if (length > 0 && (data[0] & 0xFF) == 0x47) return false;   // MPEG-TS
            String low = text().trim().toLowerCase(Locale.ROOT);
            return low.startsWith("<!doctype html") || low.startsWith("<html");
        }

        /** Neki serveri vrate HTML stranicu "nije dostupno u vasoj zemlji" sa kodom 200. */
        int htmlStatus() throws Exception {
            String low = text().toLowerCase(Locale.ROOT);
            return low.contains("block") || low.contains("country") || low.contains("region")
                    ? Health.BLOCKED : Health.OFFLINE;
        }
    }

    private static Fetch fetch(Channel c, String url, int maxBytes) throws Exception {
        Fetch f = new Fetch();
        HttpURLConnection conn = null;
        try {
            String current = url;
            for (int hop = 0; hop < 6; hop++) {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", c.userAgent != null ? c.userAgent : Repo.UA);
                if (c.referrer != null) {
                    conn.setRequestProperty("Referer", c.referrer);
                    try {
                        URL r = new URL(c.referrer);
                        conn.setRequestProperty("Origin", r.getProtocol() + "://" + r.getHost()
                                + (r.getPort() > 0 ? ":" + r.getPort() : ""));
                    } catch (Exception ignored) { }
                }
                conn.setRequestProperty("Accept", "*/*");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (loc == null) { f.status = Health.OFFLINE; return f; }
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code == 401 || code == 403 || code == 451) { f.status = Health.BLOCKED; return f; }
                if (code >= 400) { f.status = Health.OFFLINE; return f; }

                f.finalUrl = current;
                f.contentType = conn.getContentType();
                byte[] buf = new byte[maxBytes];
                int total = 0;
                InputStream in = conn.getInputStream();
                while (total < buf.length) {
                    int n = in.read(buf, total, buf.length - total);
                    if (n <= 0) break;
                    total += n;
                }
                f.data = buf;
                f.length = total;
                return f;
            }
            f.status = Health.OFFLINE;   // previse preusmjeravanja
            return f;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String fromUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        int q = lower.indexOf('?');
        String path = q >= 0 ? lower.substring(0, q) : lower;
        if (path.endsWith(".m3u8")) return MimeTypes.APPLICATION_M3U8;
        if (path.endsWith(".mpd")) return MimeTypes.APPLICATION_MPD;
        if (path.endsWith(".ts") || path.endsWith(".mp4")) return "";
        return null;
    }
}
