package com.kevin.tiertagger.queue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Timing constants and small pure helpers for the queue socket.
 *
 * <p>Deliberately free of Minecraft and Gson types so the arithmetic can be exercised
 * by {@code QueueTuningCheck} without a game running.</p>
 */
public final class QueueTuning {
    private QueueTuning() {}

    /** Backoff steps in seconds; the last one repeats for every further attempt. */
    static final int[] BACKOFF_SECONDS = {5, 10, 20, 40, 60};

    /** A connection that survives this long is healthy, so the backoff resets. */
    public static final long HEALTHY_AFTER_MS = 60_000L;

    /** Silence longer than this (no frame, no ping) means the connection is dead. */
    public static final long STALE_AFTER_MS = 75_000L;

    /** A join/leave request with no answer after this long is reported as lost. */
    public static final long REQUEST_TIMEOUT_MS = 10_000L;

    // Close codes sent by the bot (MOD-PROTOCOL-v2.md, "Close codes").

    /** The token was revoked (/vtl unlink on Discord, or the account linked another install). */
    public static final int CLOSE_TOKEN_REVOKED = 4001;

    /** The same token opened a newer connection (a second game instance). */
    public static final int CLOSE_REPLACED = 4002;

    /** The bot does not speak this protocol version: the mod needs an update. */
    public static final int CLOSE_UNSUPPORTED = 4003;

    /** Policy violation (flooding). */
    public static final int CLOSE_POLICY = 4008;

    /** Too many connections. */
    public static final int CLOSE_TRY_AGAIN_LATER = 1013;

    /** Minimum wait after a policy close, a 1013, or an HTTP 429/503 handshake answer. */
    public static final long POLICY_BACKOFF_MS = 60_000L;

    /**
     * Minimum wait after an HTTP 404/410 or redirect handshake answer: the endpoint does not exist
     * at that URL (an old or mistyped URL, or the bot side is not deployed). Retrying every minute
     * would only add load.
     */
    public static final long MISSING_ENDPOINT_BACKOFF_MS = 10 * 60_000L;

    /** Upper bound on the jitter added to each reconnect delay, in milliseconds. */
    static final int MAX_JITTER_MS = 5_000;

    /** What to do after the server closed an open connection. */
    public enum CloseAction { RECONNECT, TOKEN_REVOKED, STOP_REPLACED, STOP_OUTDATED }

    public static CloseAction actionForClose(int code) {
        return switch (code) {
            case CLOSE_TOKEN_REVOKED -> CloseAction.TOKEN_REVOKED;
            case CLOSE_REPLACED -> CloseAction.STOP_REPLACED;
            case CLOSE_UNSUPPORTED -> CloseAction.STOP_OUTDATED;
            default -> CloseAction.RECONNECT;
        };
    }

    /** Lower bound on the reconnect delay after the server closed with {@code code}. */
    public static long minDelayForClose(int code) {
        return code == CLOSE_POLICY || code == CLOSE_TRY_AGAIN_LATER ? POLICY_BACKOFF_MS : 0L;
    }

    /** Lower bound on the reconnect delay after the handshake was refused with HTTP {@code status}. */
    public static long minDelayForHandshake(int status) {
        return switch (status) {
            case 301, 302, 303, 307, 308, 404, 410 -> MISSING_ENDPOINT_BACKOFF_MS;
            case 429, 503 -> POLICY_BACKOFF_MS;
            default -> 0L;
        };
    }

    /**
     * Delay before the next reconnect attempt.
     *
     * @param attempt    number of attempts made so far, from 0
     * @param minDelayMs lower bound imposed by the close code, or 0
     * @param jitterMs   random component, normally from {@link #jitter()}
     */
    public static long backoffMillis(int attempt, long minDelayMs, int jitterMs) {
        int index = Math.min(Math.max(attempt, 0), BACKOFF_SECONDS.length - 1);
        return Math.max(minDelayMs, BACKOFF_SECONDS[index] * 1000L) + jitterMs;
    }

    /** Random jitter so that clients dropped together don't all return at once. */
    public static int jitter() {
        return ThreadLocalRandom.current().nextInt(0, MAX_JITTER_MS + 1);
    }

    /**
     * Adds {@code v=<version>} to a WebSocket URL that does not carry it yet, so a custom URL
     * typed into the config screen still talks the right protocol.
     */
    public static String withVersion(String url, int version) {
        if (url == null) return null;
        if (url.matches(".*[?&]v=\\d+.*")) return url;
        return url + (url.contains("?") ? "&" : "?") + "v=" + version;
    }

    /**
     * A set that forgets its oldest entry once {@code maxEntries} is reached.
     *
     * <p>Used to remember which queue openings have already been announced: without a bound it
     * would grow for as long as the game stays open.</p>
     */
    public static Set<String> newBoundedSet(int maxEntries) {
        return Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > maxEntries;
            }
        }));
    }
}
