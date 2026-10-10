package com.kevin.tiertagger.hub;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long the player has been waiting in a queue opening, counted from the first moment the mod
 * saw them in it (the bot does not send a join time). Shared by the hub and the HUD.
 */
public final class WaitClock {
    private WaitClock() {}

    private static final Map<String, Long> SINCE = new ConcurrentHashMap<>();

    /** Milliseconds waited in {@code sessionId}; starts counting on the first call. */
    public static long waited(String sessionId, long now) {
        if (sessionId == null) return 0;
        if (SINCE.size() > 64) SINCE.clear();
        return now - SINCE.computeIfAbsent(sessionId, s -> now);
    }

    /** {@code m:ss}. */
    public static String format(long millis) {
        long s = Math.max(0, millis / 1000);
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
