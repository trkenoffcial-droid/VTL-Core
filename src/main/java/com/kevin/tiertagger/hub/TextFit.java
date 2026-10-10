package com.kevin.tiertagger.hub;

/**
 * Text scales for Minecraft's pixel font. A scale that is a whole number of screen pixels per
 * font pixel keeps the letters sharp; anything in between smears them unevenly.
 */
public final class TextFit {
    private TextFit() {}

    /** {@code scale} rounded down to a multiple of {@code 1 / guiScale}, and at least that. */
    public static float snap(float scale, int guiScale) {
        int gs = Math.max(1, guiScale);
        int pixels = (int) Math.floor(scale * gs + 1e-4);
        return Math.max(1, pixels) / (float) gs;
    }

    /** The sharp scale closest to {@code scale}, at least one screen pixel per font pixel. */
    public static float nearest(float scale, int guiScale) {
        int gs = Math.max(1, guiScale);
        return Math.max(1, Math.round(scale * gs)) / (float) gs;
    }

    /**
     * The largest sharp scale up to {@code maxScale} at which text {@code textWidth} wide (at
     * scale 1) fits in {@code maxWidth}; 0 when it does not fit even at one screen pixel per font
     * pixel.
     */
    public static float fit(int textWidth, int maxWidth, float maxScale, int guiScale) {
        int gs = Math.max(1, guiScale);
        if (maxWidth <= 0) return 0f;
        if (textWidth <= 0) return snap(maxScale, gs);
        float wanted = Math.min(maxScale, maxWidth / (float) textWidth);
        int pixels = (int) Math.floor(wanted * gs + 1e-4);
        return pixels < 1 ? 0f : pixels / (float) gs;
    }
}
