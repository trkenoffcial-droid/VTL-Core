package com.kevin.tiertagger.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Runnable self-check for the config store and the one-time import of the ukulib-era
 * {@code tiertagger.toml}. Pure Java + Gson, no game: {@code ./gradlew configCheck}.
 */
public final class ConfigCheck {
    private ConfigCheck() {}

    private static int checks = 0;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Stand-in config: the store is generic, so the check does not need Minecraft classes. */
    static final class Sample {
        int a = 1;
        String b = "x";
        boolean c = false;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("vtl-config-check");
        try {
            missingFileGivesDefaults(dir);
            roundTripKeepsValues(dir);
            brokenJsonIsMovedAside(dir);
            tomlImportReadsKnownKeys(dir);
            tomlImportSkipsGarbage(dir);
            tomlImportMissingFileIsEmpty(dir);
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println("ConfigCheck: " + checks + " checks passed");
    }

    private static JsonConfigStore<Sample> store(Path file) {
        return new JsonConfigStore<>(file, Sample.class, Sample::new, GSON);
    }

    private static void missingFileGivesDefaults(Path dir) {
        Sample s = store(dir.resolve("none.json")).load();
        check(s.a == 1 && "x".equals(s.b) && !s.c, "a missing file gives the defaults");
    }

    private static void roundTripKeepsValues(Path dir) {
        Path file = dir.resolve("vtl.json");
        Sample s = new Sample();
        s.a = 42;
        s.b = "kept";
        s.c = true;
        store(file).save(s);
        check(Files.isRegularFile(file), "save writes the file");
        check(!Files.exists(dir.resolve("vtl.json.tmp")), "no temp file is left behind");
        Sample back = store(file).load();
        check(back.a == 42 && "kept".equals(back.b) && back.c, "values survive a save/load round trip");
    }

    private static void brokenJsonIsMovedAside(Path dir) throws IOException {
        Path file = dir.resolve("broken.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        Sample s = store(file).load();
        check(s.a == 1, "a broken file falls back to the defaults");
        check(Files.isRegularFile(dir.resolve("broken.json.broken")), "the broken file is kept as .broken");
        check(!Files.exists(file), "the broken file is moved, not left in place");
    }

    private static void tomlImportReadsKnownKeys(Path dir) throws IOException {
        Path toml = dir.resolve("tiertagger.toml");
        Files.writeString(toml, String.join("\n",
                "enabled = true",
                "gameMode = \"sword\"",
                "queueRegion = \"EU\"",
                "queueSound = false",
                "retiredColor = 10671871",
                "hexColor = 0xa2d6ff",
                "apiUrl = \"https://www.tierslist.net/api\"",
                "",
                "[tierColors]",
                "  HT1 = 15252026",
                "  LT1 = 13988693",
                ""), StandardCharsets.UTF_8);
        JsonObject o = LegacyTomlImport.read(toml);
        check(o.get("enabled").getAsBoolean(), "booleans are read");
        check("sword".equals(o.get("gameMode").getAsString()), "strings are read without quotes");
        check("EU".equals(o.get("queueRegion").getAsString()), "enum names come through as strings");
        check(!o.get("queueSound").getAsBoolean(), "false is read");
        check(o.get("retiredColor").getAsInt() == 10671871, "integers are read");
        check(o.get("hexColor").getAsInt() == 0xa2d6ff, "hex integers are read");
        JsonObject colors = o.getAsJsonObject("tierColors");
        check(colors != null && colors.get("HT1").getAsInt() == 15252026 && colors.get("LT1").getAsInt() == 13988693,
                "the [tierColors] table becomes a nested object");
        check(!o.has("HT1"), "table keys do not leak to the top level");
    }

    private static void tomlImportSkipsGarbage(Path dir) throws IOException {
        Path toml = dir.resolve("garbage.toml");
        Files.writeString(toml, String.join("\n",
                "???",
                "x = [1,2",
                "# a comment",
                "queueMode = \"mace\"",
                "y = \"unterminated",
                "z ="), StandardCharsets.UTF_8);
        JsonObject o = LegacyTomlImport.read(toml);
        check("mace".equals(o.get("queueMode").getAsString()), "good lines still import next to garbage");
        check(!o.has("x") && !o.has("y") && !o.has("z") && !o.has("???"), "garbled lines are skipped");
    }

    private static void tomlImportMissingFileIsEmpty(Path dir) {
        check(LegacyTomlImport.read(dir.resolve("absent.toml")).size() == 0, "a missing toml imports nothing");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + message);
    }
}
