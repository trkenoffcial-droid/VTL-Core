package com.kevin.tiertagger.dev;

import net.minecraft.util.Util;

/**
 * Development only: replaces the game clock so it moves exactly one video frame per rendered
 * frame while the trailer is recorded. A slow machine then still gives a smooth 30 fps video,
 * because nothing in the game sees the real time between two captured frames.
 */
final class VirtualClock {
    private VirtualClock() {}

    static final int FPS = 30;
    private static final long FRAME_NANOS = 1_000_000_000L / FPS;

    private static volatile long nanos;
    private static boolean installed;

    /** From now on {@link Util#getMillis()} and {@link Util#getNanos()} read this clock. */
    static void install() {
        if (installed) return;
        nanos = System.nanoTime();
        Util.timeSource = () -> nanos;
        installed = true;
    }

    /** One frame of time passes. */
    static void advance() {
        nanos += FRAME_NANOS;
    }
}
