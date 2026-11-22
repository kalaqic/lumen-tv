package ba.lumen.tv;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mali JSON citac koji cita znak po znak (bez ucitavanja cijelog odgovora u memoriju).
 * Liste filmova sa IPTV servera znaju imati desetine hiljada stavki (10-30 MB), pa ih
 * citamo stavku po stavku. Za male odgovore (detalji filma) postoji {@link #parse(Reader)}.
 *
 * Brojevi i true/false se vracaju kao tekst; null kao null.
 */
public final class MiniJson {

    public static final int BEGIN_ARRAY = 1, END_ARRAY = 2, BEGIN_OBJECT = 3, END_OBJECT = 4,
            NAME = 5, STRING = 6, NUMBER = 7, BOOLEAN = 8, NULL = 9, END = 10;

    private final Reader in;
    private final char[] buf = new char[16 * 1024];
    private int pos, len;
    /** Stek: true = unutar objekta, false = unutar niza. */
    private final boolean[] stack = new boolean[256];
    private int depth;
    /** Unutar objekta: sljedeci token je ime (a ne vrijednost). */
    private boolean expectName;
    private int peeked = -1;

    public MiniJson(Reader in) { this.in = in; }

    private int read() throws IOException {
        if (pos >= len) {
            len = in.read(buf, 0, buf.length);
            pos = 0;
            if (len <= 0) { len = 0; return -1; }
        }
        return buf[pos++];
    }

    private int peekChar() throws IOException {
        if (pos >= len) {
            len = in.read(buf, 0, buf.length);
            pos = 0;
            if (len <= 0) { len = 0; return -1; }
        }
        return buf[pos];
    }

    /** Preskoci razmake i separatore (',' i ':'). */
    private int nextSignificant() throws IOException {
        while (true) {
            int c = peekChar();
            if (c == -1) return -1;
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ',' || c == ':' || c == '﻿') {
                pos++;
                continue;
            }
            return c;
        }
    }

    public int peek() throws IOException {
        if (peeked != -1) return peeked;
        int c = nextSignificant();
        int t;
        if (c == -1) t = END;
        else if (c == '[') t = BEGIN_ARRAY;
        else if (c == ']') t = END_ARRAY;
        else if (c == '{') t = BEGIN_OBJECT;
        else if (c == '}') t = END_OBJECT;
        else if (c == '"') t = (depth > 0 && stack[depth - 1] && expectName) ? NAME : STRING;
        else if (c == 't' || c == 'f') t = BOOLEAN;
        else if (c == 'n') t = NULL;
        else t = NUMBER;
        peeked = t;
        return t;
    }

    private void expect(int type) throws IOException {
        int t = peek();
        if (t != type) throw new IOException("JSON: ocekivano " + type + ", dobijeno " + t);
        peeked = -1;
    }

    public void beginArray() throws IOException { expect(BEGIN_ARRAY); pos++; push(false); }
    public void endArray() throws IOException { expect(END_ARRAY); pos++; pop(); }
    public void beginObject() throws IOException { expect(BEGIN_OBJECT); pos++; push(true); }
    public void endObject() throws IOException { expect(END_OBJECT); pos++; pop(); }

    private void push(boolean obj) throws IOException {
        if (depth >= stack.length) throw new IOException("JSON: preduboko");
        stack[depth++] = obj;
        expectName = obj;
    }

    private void pop() {
        depth--;
        valueDone();
    }

    /** Poslije vrijednosti u objektu sljedece ide ime. */
    private void valueDone() {
        expectName = depth > 0 && stack[depth - 1];
    }

    public boolean hasNext() throws IOException {
        int t = peek();
        return t != END_ARRAY && t != END_OBJECT && t != END;
    }

    public String nextName() throws IOException {
        expect(NAME);
        pos++;
        String s = readString();
        expectName = false;
        return s;
    }

    /** Tekst, broj ili true/false kao tekst; null za null. */
    public String nextString() throws IOException {
        int t = peek();
        peeked = -1;
        String v;
        if (t == STRING) {
            pos++;
            v = readString();
        } else if (t == NUMBER || t == BOOLEAN || t == NULL) {
            v = readLiteral();
            if (t == NULL) v = null;
        } else {
            throw new IOException("JSON: nije jednostavna vrijednost (" + t + ")");
        }
        valueDone();
        return v;
    }

    /** Preskoci sljedecu vrijednost (i cijeli niz/objekat). */
    public void skipValue() throws IOException {
        int t = peek();
        if (t == BEGIN_ARRAY) {
            beginArray();
            while (hasNext()) skipValue();
            endArray();
        } else if (t == BEGIN_OBJECT) {
            beginObject();
            while (hasNext()) { nextName(); skipValue(); }
            endObject();
        } else if (t == NAME) {
            nextName();
        } else {
            nextString();
        }
    }

    private String readLiteral() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = peekChar();
            if (c == -1 || c == ',' || c == ']' || c == '}' || c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ':') break;
            sb.append((char) c);
            pos++;
        }
        return sb.toString();
    }

    private String readString() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = read();
            if (c == -1) throw new IOException("JSON: nezavrsen tekst");
            if (c == '"') break;
            if (c == '\\') {
                int e = read();
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': {
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int h = read();
                            int d = Character.digit(h, 16);
                            if (d < 0) throw new IOException("JSON: los \\u");
                            code = code * 16 + d;
                        }
                        sb.append((char) code);
                        break;
                    }
                    case -1: throw new IOException("JSON: nezavrsen tekst");
                    default: sb.append((char) e);
                }
            } else {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    // ---- cijelo stablo (za male odgovore)

    /** Map (objekat), List (niz), String (tekst/broj/true/false) ili null. */
    public static Object parse(Reader r) throws IOException {
        MiniJson j = new MiniJson(r);
        if (j.peek() == END) return null;
        return j.readValue();
    }

    public Object readValue() throws IOException {
        int t = peek();
        if (t == BEGIN_OBJECT) {
            Map<String, Object> m = new LinkedHashMap<>();
            beginObject();
            while (hasNext()) {
                String k = nextName();
                m.put(k, readValue());
            }
            endObject();
            return m;
        }
        if (t == BEGIN_ARRAY) {
            List<Object> l = new ArrayList<>();
            beginArray();
            while (hasNext()) l.add(readValue());
            endArray();
            return l;
        }
        return nextString();
    }

    // ---- pomocne funkcije za citanje stabla

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    /** Tekst polja; prazan tekst i "null" -> null. Za nizove vraca prvi element (npr. backdrop_path). */
    public static String str(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            v = l.isEmpty() ? null : l.get(0);
        }
        if (!(v instanceof String)) return null;
        String s = ((String) v).trim();
        return s.isEmpty() || s.equals("null") ? null : s;
    }
}
