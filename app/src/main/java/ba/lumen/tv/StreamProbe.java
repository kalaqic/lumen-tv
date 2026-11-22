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

    /** Brza provjera da li stream odgovara: Health.ONLINE / OFFLINE / BLOCKED. */
    static int check(Channel c) {
        HttpURLConnection conn = null;
        try {
            String current = c.url;
            for (int hop = 0; hop < 6; hop++) {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", c.userAgent != null ? c.userAgent : Repo.UA);
                if (c.referrer != null) conn.setRequestProperty("Referer", c.referrer);
                conn.setRequestProperty("Accept", "*/*");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (loc == null) return Health.OFFLINE;
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code == 401 || code == 403 || code == 451) return Health.BLOCKED;
                if (code >= 400) return Health.OFFLINE;
                byte[] buf = new byte[512];
                int total = 0;
                InputStream in = conn.getInputStream();
                while (total < buf.length) {
                    int n = in.read(buf, total, buf.length - total);
                    if (n <= 0) break;
                    total += n;
                }
                if (total == 0) return Health.OFFLINE;
                String head = new String(buf, 0, total, "ISO-8859-1").trim();
                // neki serveri vrate HTML stranicu "geo blocked" sa kodom 200
                String low = head.toLowerCase(Locale.ROOT);
                if (low.startsWith("<!doctype html") || low.startsWith("<html")) {
                    return low.contains("block") || low.contains("country") || low.contains("region")
                            ? Health.BLOCKED : Health.OFFLINE;
                }
                return Health.ONLINE;
            }
            return Health.OFFLINE;
        } catch (Exception e) {
            return Health.OFFLINE;
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
