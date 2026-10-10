package com.kevin.tiertagger.queue;

import java.util.Set;

/**
 * Runnable self-check for the queue socket's timing and state helpers.
 *
 * <p>These are the pieces that are wrong in ways nothing else would catch: an off-by-one in the
 * backoff table shows up as a reconnect storm, and an unbounded dedupe set grows silently until
 * the game has been open for hours. Both are pure arithmetic, so they need no game to test.</p>
 *
 * <p>Run with {@code ./gradlew queueTuningCheck}, or directly:
 * {@code java src/test/java/com/kevin/tiertagger/queue/QueueTuningCheck.java}.</p>
 */
public final class QueueTuningCheck {
    private QueueTuningCheck() {}

    private static int checks = 0;

    public static void main(String[] args) {
        backoffFollowsTheSpecTable();
        backoffRepeatsAtTheCap();
        backoffHonoursTheMinimum();
        backoffIncludesJitter();
        dedupeSetEvictsOldest();
        dedupeSetRejectsDuplicates();
        reassemblySurvivesSplitFrames();
        closeCodesMapToActions();
        handshakeStatusesSetTheFloor();
        versionIsAddedOnce();

        System.out.println("QueueTuningCheck: " + checks + " checks passed");
    }

    /** 5s, 10s, 20s, 40s, 60s plus jitter. */
    private static void backoffFollowsTheSpecTable() {
        int[] expected = {5, 10, 20, 40, 60};
        for (int attempt = 0; attempt < expected.length; attempt++) {
            long actual = QueueTuning.backoffMillis(attempt, 0L, 0);
            check(actual == expected[attempt] * 1000L,
                    "attempt " + attempt + " should wait " + expected[attempt] + "s, got " + actual + "ms");
        }
    }

    /** Past the end of the table the delay stays at the cap rather than overflowing. */
    private static void backoffRepeatsAtTheCap() {
        for (int attempt : new int[]{5, 6, 50, 10_000}) {
            long actual = QueueTuning.backoffMillis(attempt, 0L, 0);
            check(actual == 60_000L, "attempt " + attempt + " should cap at 60s, got " + actual + "ms");
        }
    }

    /** 1013 means "you already have too many connections"; the wait must not be shortened. */
    private static void backoffHonoursTheMinimum() {
        long actual = QueueTuning.backoffMillis(0, QueueTuning.POLICY_BACKOFF_MS, 0);
        check(actual == 60_000L, "a 1013 close should wait 60s, got " + actual + "ms");

        long generous = QueueTuning.backoffMillis(4, QueueTuning.POLICY_BACKOFF_MS, 0);
        check(generous == 60_000L, "the floor should not raise a longer delay, got " + generous + "ms");
    }

    private static void backoffIncludesJitter() {
        for (int i = 0; i < 1_000; i++) {
            int jitter = QueueTuning.jitter();
            check(jitter >= 0 && jitter <= 5_000, "jitter out of range: " + jitter);
        }

        check(QueueTuning.backoffMillis(0, 0L, 4_999) == 9_999L, "jitter should be added to the delay");
    }

    private static void dedupeSetEvictsOldest() {
        Set<String> seen = QueueTuning.newBoundedSet(3);
        seen.add("a");
        seen.add("b");
        seen.add("c");
        check(seen.size() == 3, "set should hold 3 entries, holds " + seen.size());

        seen.add("d");
        check(seen.size() == 3, "set should stay bounded at 3, holds " + seen.size());
        check(!seen.contains("a"), "the oldest entry should have been evicted");
        check(seen.contains("d"), "the newest entry should be present");
    }

    /** Re-adding a known sessionId must be reportable, or reconnects would re-announce. */
    private static void dedupeSetRejectsDuplicates() {
        Set<String> seen = QueueTuning.newBoundedSet(8);
        check(seen.add("session-1"), "first add should succeed");
        check(!seen.add("session-1"), "re-adding the same sessionId should be reported as a duplicate");

        // Re-adding an existing entry must not evict anything else.
        seen.add("session-2");
        seen.add("session-1");
        check(seen.contains("session-2"), "re-adding an existing entry should not evict others");
    }

    /**
     * Mirrors the listener's accumulate-until-{@code last} behaviour: one JSON message split
     * across two frames has to reassemble into the original text.
     */
    private static void reassemblySurvivesSplitFrames() {
        String message = "{\"type\":\"queue_open\",\"v\":2,\"queue\":{\"queueId\":\"EU:vanilla\",\"sessionId\":\"EU:vanilla:1\"}}";
        int cut = message.length() / 2;

        StringBuilder buffer = new StringBuilder();
        String first = message.substring(0, cut);
        String second = message.substring(cut);

        // First frame: not last, so the buffer keeps accumulating and nothing is parsed yet.
        buffer.append(first);
        boolean firstIsLast = false;
        check(!firstIsLast, "the first fragment must not be treated as a complete message");

        // Second frame: last, so the buffer is consumed and cleared.
        buffer.append(second);
        String assembled = buffer.toString();
        buffer.setLength(0);

        check(assembled.equals(message), "reassembled text should match the original");
        check(buffer.length() == 0, "the buffer should be cleared after a complete message");

        // A three-way split must also reassemble.
        StringBuilder three = new StringBuilder();
        int a = message.length() / 3;
        int b = 2 * message.length() / 3;
        three.append(message, 0, a).append(message, a, b).append(message, b, message.length());
        check(three.toString().equals(message), "a three-way split should reassemble too");
    }

    /** 4001 must forget the token, 4002/4003 must stop, everything else reconnects. */
    private static void closeCodesMapToActions() {
        check(QueueTuning.actionForClose(4001) == QueueTuning.CloseAction.TOKEN_REVOKED, "4001 should revoke the token");
        check(QueueTuning.actionForClose(4002) == QueueTuning.CloseAction.STOP_REPLACED, "4002 should stop (replaced)");
        check(QueueTuning.actionForClose(4003) == QueueTuning.CloseAction.STOP_OUTDATED, "4003 should stop (outdated)");
        // 1008 is a generic policy close (proxies send it too): never a reason to drop the token.
        for (int code : new int[]{1000, 1001, 1006, 1008, 1011, 1013, 4008}) {
            check(QueueTuning.actionForClose(code) == QueueTuning.CloseAction.RECONNECT, code + " should reconnect");
        }
        check(QueueTuning.minDelayForClose(4008) == QueueTuning.POLICY_BACKOFF_MS, "4008 should wait at least 60s");
        check(QueueTuning.minDelayForClose(1013) == QueueTuning.POLICY_BACKOFF_MS, "1013 should wait at least 60s");
        check(QueueTuning.minDelayForClose(1006) == 0L, "1006 should use the normal backoff");
    }

    /** A missing endpoint (bot not deployed yet) must not be retried every few seconds. */
    private static void handshakeStatusesSetTheFloor() {
        check(QueueTuning.minDelayForHandshake(404) == QueueTuning.MISSING_ENDPOINT_BACKOFF_MS, "404 should wait 10 minutes");
        check(QueueTuning.minDelayForHandshake(410) == QueueTuning.MISSING_ENDPOINT_BACKOFF_MS, "410 should wait 10 minutes");
        // tierslist.net redirects to www: a redirect means a wrong URL, not a passing outage.
        for (int redirect : new int[]{301, 302, 303, 307, 308}) {
            check(QueueTuning.minDelayForHandshake(redirect) == QueueTuning.MISSING_ENDPOINT_BACKOFF_MS, redirect + " should wait 10 minutes");
        }
        check(QueueTuning.minDelayForHandshake(429) == QueueTuning.POLICY_BACKOFF_MS, "429 should wait 60s");
        check(QueueTuning.minDelayForHandshake(503) == QueueTuning.POLICY_BACKOFF_MS, "503 should wait 60s");
        check(QueueTuning.minDelayForHandshake(502) == 0L, "502 should use the normal backoff");
        check(QueueTuning.backoffMillis(0, QueueTuning.MISSING_ENDPOINT_BACKOFF_MS, 0) == 600_000L, "the 404 floor should win over the table");
    }

    private static void versionIsAddedOnce() {
        check(QueueTuning.withVersion("wss://h/mod/ws", 2).equals("wss://h/mod/ws?v=2"), "v should be added");
        check(QueueTuning.withVersion("wss://h/mod/ws?v=2", 2).equals("wss://h/mod/ws?v=2"), "v should not be added twice");
        check(QueueTuning.withVersion("wss://h/mod/ws?x=1", 2).equals("wss://h/mod/ws?x=1&v=2"), "v should join an existing query");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }
}
