package com.kevin.tiertagger.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reads tierslist.net's latest-version answer and decides whether to offer an update. */
public final class UpdateCheck {
    private UpdateCheck() {}

    public static final String LATEST_URL = "https://tierslist.net/api/mod/latest";
    public static final String DEFAULT_URL = "https://tierslist.net/download";

    private static final Pattern VERSION = Pattern.compile("^\\d+(\\.\\d+){0,3}$");

    public record Latest(String version, String url) {}

    /** {@code {"version":"1.2.0","url":"..."}} or null when the answer is unusable. */
    public static Latest parse(String json) {
        if (json == null) return null;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            if (!o.has("version") || !o.get("version").isJsonPrimitive()) return null;
            String version = o.get("version").getAsString().trim();
            if (!VERSION.matcher(version).matches()) return null;
            String url = o.has("url") && o.get("url").isJsonPrimitive() ? o.get("url").getAsString() : null;
            return new Latest(version, url);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when {@code latest} is a higher version than {@code current}; build suffixes ({@code +mc…}) are ignored. */
    public static boolean isNewer(String latest, String current) {
        int[] a = numbers(latest);
        int[] b = numbers(current);
        if (a == null || b == null) return false;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] numbers(String v) {
        if (v == null) return null;
        String plain = v.trim();
        int cut = plain.indexOf('+');
        if (cut >= 0) plain = plain.substring(0, cut);
        cut = plain.indexOf('-');
        if (cut >= 0) plain = plain.substring(0, cut);
        if (!VERSION.matcher(plain).matches()) return null;
        String[] parts = plain.split("\\.");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** Only https links on tierslist.net (or a subdomain) are opened; anything else opens the download page. */
    public static String safeUrl(String url) {
        if (url == null) return DEFAULT_URL;
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null) return DEFAULT_URL;
            host = host.toLowerCase(Locale.ROOT);
            if (host.equals("tierslist.net") || host.endsWith(".tierslist.net")) return uri.toString();
        } catch (Exception ignored) {
            // Malformed: fall through.
        }
        return DEFAULT_URL;
    }
}
