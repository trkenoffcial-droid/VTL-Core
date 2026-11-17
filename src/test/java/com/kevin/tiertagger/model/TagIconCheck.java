package com.kevin.tiertagger.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runnable self-check for the kit icons drawn in front of a tier on name tags. The icon is a glyph
 * of the mod's font, so a kit only gets one when the font maps its character to a texture that
 * ships in the jar. Needs no game.
 *
 * <p>Run with {@code ./gradlew tagIconCheck} from the mod's root folder.</p>
 */
public final class TagIconCheck {
    private TagIconCheck() {}

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        JsonObject font = JsonParser.parseString(Files.readString(
                RESOURCES.resolve("assets/minecraft/font/default.json"), StandardCharsets.UTF_8)).getAsJsonObject();

        spearHasAGlyph(font);
        spearIsNotWhite();

        System.out.println("TagIconCheck: " + checks + " checks passed");
    }

    private static void spearHasAGlyph(JsonObject font) {
        String file = fileFor(font, TagIcons.SPEAR);
        check(file != null, "the font maps the Spear character U+" + Integer.toHexString(TagIcons.SPEAR).toUpperCase());
        if (file == null) return;
        check(file.equals("tier-tagger:mctiers/spear.png"), "the Spear glyph uses mctiers/spear.png, got " + file);
        Path png = RESOURCES.resolve("assets/tier-tagger/textures/mctiers/spear.png");
        check(Files.isRegularFile(png), "mctiers/spear.png ships in the jar");
    }

    private static void spearIsNotWhite() {
        // GameMode treats a white colour as "no icon" and prints only the tier
        check(TagIcons.SPEAR_COLOR != 0xFFFFFF, "the Spear colour is not white, so the icon is drawn");
    }

    /** The texture the font draws for {@code c}, or null when no provider has it. */
    private static String fileFor(JsonObject font, char c) {
        for (JsonElement p : font.getAsJsonArray("providers")) {
            JsonObject provider = p.getAsJsonObject();
            JsonArray chars = provider.getAsJsonArray("chars");
            if (chars == null) continue;
            for (JsonElement row : chars) {
                if (row.getAsString().indexOf(c) >= 0) return provider.get("file").getAsString();
            }
        }
        return null;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        checks++;
    }
}
