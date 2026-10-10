package com.kevin.tiertagger.config;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Reads the {@code tiertagger.toml} that builds before 3.0 wrote through ukulib (toml4j), so a
 * player's settings carry over to {@code vtl.json} on the first start.
 *
 * <p>Only what that file ever contained is understood: flat {@code key = value} lines with
 * quoted strings, booleans and integers (decimal or {@code 0x} hex), and {@code [table]}
 * sections of the same (used by {@code tierColors}). Anything else is skipped, never fatal.</p>
 */
public final class LegacyTomlImport {
    private static final Logger LOG = Logger.getLogger("VTL");

    private LegacyTomlImport() {}

    /** The file's values as JSON, ready for Gson; empty when the file is missing or unreadable. */
    public static JsonObject read(Path toml) {
        JsonObject root = new JsonObject();
        if (!Files.isRegularFile(toml)) return root;
        try {
            JsonObject current = root;
            for (String raw : Files.readAllLines(toml, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]") && !line.startsWith("[[")) {
                    String name = line.substring(1, line.length() - 1).strip();
                    if (!name.matches("[A-Za-z0-9_-]+")) {
                        current = new JsonObject(); // unknown shape: read into nowhere
                        continue;
                    }
                    current = new JsonObject();
                    root.add(name, current);
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (!key.matches("[A-Za-z0-9_-]+")) continue;
                putValue(current, key, value);
            }
        } catch (IOException e) {
            LOG.warning("Could not read " + toml + ": " + e);
            return new JsonObject();
        }
        return root;
    }

    private static void putValue(JsonObject target, String key, String value) {
        if (value.equals("true") || value.equals("false")) {
            target.addProperty(key, Boolean.parseBoolean(value));
        } else if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            target.addProperty(key, unescape(value.substring(1, value.length() - 1)));
        } else if (value.matches("[+-]?\\d+")) {
            try {
                target.addProperty(key, Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // out of range: skip
            }
        } else if (value.matches("0x[0-9A-Fa-f]+")) {
            try {
                target.addProperty(key, Long.parseLong(value.substring(2), 16));
            } catch (NumberFormatException ignored) {
                // out of range: skip
            }
        }
        // arrays, floats, dates, broken values: not something tiertagger.toml ever held
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                default -> out.append('\\').append(n);
            }
        }
        return out.toString();
    }
}
