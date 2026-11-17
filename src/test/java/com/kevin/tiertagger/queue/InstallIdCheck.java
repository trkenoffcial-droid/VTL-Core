package com.kevin.tiertagger.queue;

import com.kevin.tiertagger.util.UpdateCheck;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runnable self-check for the install id kept in the config folder and for reading the website's
 * latest-version answer. Neither needs a game.
 *
 * <p>Run with {@code ./gradlew installIdCheck}.</p>
 */
public final class InstallIdCheck {
    private InstallIdCheck() {}

    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        createsAndKeepsOneId();
        replacesAFileThatIsNotAnId();
        validatesOnlyVersion4();
        survivesAnUnwritableFolder();
        readsTheLatestAnswer();
        comparesVersions();
        keepsDownloadsOnTheWebsite();

        System.out.println("InstallIdCheck: " + checks + " checks passed");
    }

    private static void createsAndKeepsOneId() throws Exception {
        Path dir = Files.createTempDirectory("vtl-id");
        Path file = dir.resolve("vtl-install-id.txt");
        String first = InstallId.loadOrCreate(file);
        check(InstallId.isValid(first), "a new id is a v4 UUID: " + first);
        check(Files.exists(file), "the id is written to the config folder");
        check(first.equals(InstallId.loadOrCreate(file)), "the same id comes back on the next start");
        check(first.equals(first.toLowerCase()), "ids are lower case");
    }

    private static void replacesAFileThatIsNotAnId() throws Exception {
        Path dir = Files.createTempDirectory("vtl-id");
        Path file = dir.resolve("vtl-install-id.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        String id = InstallId.loadOrCreate(file);
        check(InstallId.isValid(id), "a broken file is replaced with a new id");
        check(id.equals(Files.readString(file, StandardCharsets.UTF_8).trim()), "the new id is saved");

        String upper = "3F2B8C1E-9A4D-4E6F-8B2A-1C3D5E7F9A0B";
        Files.writeString(file, upper + "\n", StandardCharsets.UTF_8);
        check(upper.toLowerCase().equals(InstallId.loadOrCreate(file)), "a stored id is read in any case");
    }

    private static void validatesOnlyVersion4() {
        check(InstallId.isValid("3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b"), "v4 accepted");
        check(!InstallId.isValid("3f2b8c1e-9a4d-1e6f-8b2a-1c3d5e7f9a0b"), "v1 rejected");
        check(!InstallId.isValid("3f2b8c1e9a4d4e6f8b2a1c3d5e7f9a0b"), "no dashes rejected");
        check(!InstallId.isValid(null), "null rejected");
    }

    private static void survivesAnUnwritableFolder() throws Exception {
        Path dir = Files.createTempDirectory("vtl-id");
        Path blocker = dir.resolve("not-a-folder");
        Files.writeString(blocker, "x", StandardCharsets.UTF_8);
        String id = InstallId.loadOrCreate(blocker.resolve("vtl-install-id.txt"));
        check(InstallId.isValid(id), "an id is still returned when it cannot be saved");
    }

    private static void readsTheLatestAnswer() {
        UpdateCheck.Latest l = UpdateCheck.parse("{\"version\":\"1.2.0\",\"url\":\"https://tierslist.net/download\"}");
        check(l != null && "1.2.0".equals(l.version()), "version read");
        check(l != null && "https://tierslist.net/download".equals(l.url()), "url read");
        check(UpdateCheck.parse("{\"error\":\"unavailable\"}") == null, "an error answer is ignored");
        check(UpdateCheck.parse("<html>") == null, "a page that is not JSON is ignored");
        check(UpdateCheck.parse("{\"version\":\"drop table\"}") == null, "a version that is not numbers is ignored");
        check(UpdateCheck.parse(null) == null, "null is ignored");
    }

    private static void comparesVersions() {
        check(UpdateCheck.isNewer("1.2.0", "1.1.0+mc1.21.11"), "1.2.0 > 1.1.0");
        check(UpdateCheck.isNewer("1.10.0", "1.9.3"), "numbers compare as numbers");
        check(UpdateCheck.isNewer("1.1.1", "1.1"), "a missing part counts as 0");
        check(!UpdateCheck.isNewer("1.1.0", "1.1.0+mc26.3"), "same version is not newer");
        check(!UpdateCheck.isNewer("1.0.9", "1.1.0"), "older is not newer");
        check(!UpdateCheck.isNewer("1.2.0", null), "unknown current version never prompts");
    }

    private static void keepsDownloadsOnTheWebsite() {
        check("https://tierslist.net/download".equals(UpdateCheck.safeUrl("https://tierslist.net/download?v=1.2.0") .replaceAll("\\?.*", "")),
                "a tierslist.net link is kept");
        check(UpdateCheck.DEFAULT_URL.equals(UpdateCheck.safeUrl("https://evil.example/download")), "other hosts fall back");
        check(UpdateCheck.DEFAULT_URL.equals(UpdateCheck.safeUrl("http://tierslist.net/download")), "plain http falls back");
        check(UpdateCheck.DEFAULT_URL.equals(UpdateCheck.safeUrl("https://tierslist.net.evil.example/")), "look-alike hosts fall back");
        check(UpdateCheck.DEFAULT_URL.equals(UpdateCheck.safeUrl(null)), "no url falls back");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError("InstallIdCheck failed: " + what);
    }
}
