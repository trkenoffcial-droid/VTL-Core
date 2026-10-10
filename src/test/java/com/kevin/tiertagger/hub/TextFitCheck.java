package com.kevin.tiertagger.hub;

/**
 * Runnable self-check for text scales that stay sharp and fit their box. Pure Java, no game:
 * part of {@code ./gradlew hubStateCheck}.
 */
public final class TextFitCheck {
    private TextFitCheck() {}

    private static int checks = 0;

    public static void main(String[] args) {
        snapLandsOnWholeScreenPixels();
        snapNeverGoesBelowOnePixel();
        snapWithoutGuiScale();
        nearestRoundsToClosestPixel();
        fitPicksLargestSharpScaleThatFits();
        fitRespectsMaxScale();
        fitIsZeroWhenNothingFits();
        System.out.println("TextFitCheck: " + checks + " checks passed");
    }

    private static void snapLandsOnWholeScreenPixels() {
        check(TextFit.snap(0.78f, 4) == 0.75f, "0.78 at GUI scale 4 -> 0.75 (3 px per font pixel)");
        check(TextFit.snap(1.75f, 2) == 1.5f, "1.75 at GUI scale 2 -> 1.5");
        check(TextFit.snap(1.75f, 4) == 1.75f, "1.75 at GUI scale 4 stays");
        check(TextFit.snap(2.5f, 3) == 7f / 3f, "2.5 at GUI scale 3 -> 7/3");
        check(TextFit.snap(1f, 3) == 1f, "1 stays 1");
    }

    private static void snapNeverGoesBelowOnePixel() {
        check(TextFit.snap(0.1f, 4) == 0.25f, "below one screen pixel -> one screen pixel");
    }

    private static void snapWithoutGuiScale() {
        check(TextFit.snap(1.5f, 0) == 1f, "GUI scale 0 counts as 1");
    }

    private static void nearestRoundsToClosestPixel() {
        check(TextFit.nearest(0.85f, 2) == 1f, "0.85 at GUI scale 2 -> 1 (closer than 0.5)");
        check(TextFit.nearest(0.7f, 2) == 0.5f, "0.7 at GUI scale 2 -> 0.5");
        check(TextFit.nearest(0.85f, 4) == 0.75f, "0.85 at GUI scale 4 -> 0.75");
        check(TextFit.nearest(0.1f, 4) == 0.25f, "never below one screen pixel");
        check(TextFit.nearest(2.5f, 3) == 8f / 3f, "2.5 at GUI scale 3 -> 8/3");
    }

    private static void fitPicksLargestSharpScaleThatFits() {
        check(TextFit.fit(41, 32, 1f, 4) == 0.75f, "41 px into 32 at GUI scale 4 -> 0.75");
        check(TextFit.fit(80, 32, 1f, 4) == 0.25f, "80 px into 32 at GUI scale 4 -> 0.25");
        check(TextFit.fit(17, 32, 1f, 4) == 1f, "short text keeps scale 1");
    }

    private static void fitRespectsMaxScale() {
        check(TextFit.fit(10, 100, 0.85f, 4) == 0.75f, "max 0.85 at GUI scale 4 -> 0.75");
        check(TextFit.fit(10, 100, 0.85f, 2) == 0.5f, "max 0.85 at GUI scale 2 -> 0.5");
    }

    private static void fitIsZeroWhenNothingFits() {
        check(TextFit.fit(200, 32, 1f, 4) == 0f, "200 px never fits 32, even at one pixel per font pixel");
        check(TextFit.fit(10, 0, 1f, 4) == 0f, "no room -> 0");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("TextFitCheck failed: " + message);
    }
}
