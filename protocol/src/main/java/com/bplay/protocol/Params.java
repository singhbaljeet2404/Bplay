package com.bplay.protocol;

import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tiny key/value format used for handshakes and mid-session metadata.
 *
 * <p>Encoded as UTF-8 {@code key=value} lines separated by {@code \n}. It replaces JSON here on
 * purpose: the handshake is the one place where sender and receiver must agree byte-for-byte
 * across four codebases (Java, Swift, JavaScript), and a format this small can be reimplemented
 * correctly in a dozen lines on any of them.
 *
 * <p>Backslash escapes {@code \n}, {@code \\} and {@code =} so values may contain any of them --
 * device names routinely contain '=' or newlines when a user renames a phone.
 */
public final class Params {

    private final Map<String, String> values = new LinkedHashMap<>();

    public Params put(String key, String value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("null key or value");
        }
        values.put(key, value);
        return this;
    }

    public Params put(String key, int value) {
        return put(key, Integer.toString(value));
    }

    public Params put(String key, boolean value) {
        return put(key, value ? "1" : "0");
    }

    public String get(String key, String fallback) {
        String v = values.get(key);
        return v != null ? v : fallback;
    }

    public int getInt(String key, int fallback) {
        String v = values.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBoolean(String key, boolean fallback) {
        String v = values.get(key);
        if (v == null) {
            return fallback;
        }
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public Map<String, String> asMap() {
        return new LinkedHashMap<>(values);
    }

    public byte[] encode() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!first) {
                sb.append('\n');
            }
            first = false;
            escape(sb, e.getKey());
            sb.append('=');
            escape(sb, e.getValue());
        }
        return utf8(sb.toString());
    }

    public static Params decode(byte[] data) {
        return decode(data, 0, data.length);
    }

    public static Params decode(byte[] data, int offset, int length) {
        Params p = new Params();
        String text;
        try {
            text = new String(data, offset, length, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 always exists", e);
        }
        StringBuilder token = new StringBuilder();
        String key = null;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                token.append(c == 'n' ? '\n' : c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '=' && key == null) {
                key = token.toString();
                token.setLength(0);
            } else if (c == '\n') {
                if (key != null) {
                    p.values.put(key, token.toString());
                }
                key = null;
                token.setLength(0);
            } else {
                token.append(c);
            }
        }
        if (key != null) {
            p.values.put(key, token.toString());
        }
        return p;
    }

    private static void escape(StringBuilder sb, String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' || c == '=') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else {
                sb.append(c);
            }
        }
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 always exists", e);
        }
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
