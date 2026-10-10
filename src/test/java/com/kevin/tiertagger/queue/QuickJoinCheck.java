package com.kevin.tiertagger.queue;

import com.kevin.tiertagger.queue.QueueMessages.Me;
import com.kevin.tiertagger.queue.QueueMessages.Queue;

import java.util.List;

/**
 * Runnable self-check for the J quick join: which announced queue a key press joins, and when it
 * must do nothing. Pure Java, no game: part of {@code ./gradlew hubStateCheck}.
 */
public final class QuickJoinCheck {
    private QuickJoinCheck() {}

    private static int checks = 0;
    private static long now = 1_000_000L;

    public static void main(String[] args) {
        freshAlertIsTaken();
        expiresAfter30s();
        takenOnlyOnce();
        notWhileScreenOpen();
        newerAlertReplacesOlder();
        clearForgets();
        System.out.println("QuickJoinCheck: " + checks + " checks passed");
    }

    private static Queue q(String id) {
        return new Queue(id, id + ":1", id.split(":")[0], id.split(":")[1], id.split(":")[1], 1L, List.of(), 0, true, new Me("none", null));
    }

    private static QuickJoin fresh() {
        return new QuickJoin(() -> now);
    }

    private static void freshAlertIsTaken() {
        QuickJoin j = fresh();
        j.offer(q("EU:sword"));
        check(j.take(false).map(Queue::queueId).orElse("").equals("EU:sword"), "a fresh alert is joined");
    }

    private static void expiresAfter30s() {
        QuickJoin j = fresh();
        j.offer(q("EU:sword"));
        now += 30_000;
        check(j.take(false).isPresent(), "still joinable at exactly 30 s");
        j.offer(q("EU:sword"));
        now += 30_001;
        check(j.take(false).isEmpty(), "too late after 30 s");
    }

    private static void takenOnlyOnce() {
        QuickJoin j = fresh();
        j.offer(q("EU:mace"));
        check(j.take(false).isPresent(), "first press joins");
        check(j.take(false).isEmpty(), "a second press does nothing");
    }

    private static void notWhileScreenOpen() {
        QuickJoin j = fresh();
        j.offer(q("AS_ME:pot"));
        check(j.take(true).isEmpty(), "nothing while a screen (chat, edit box) is open");
        check(j.take(false).isPresent(), "the alert is still there once the screen closes");
    }

    private static void newerAlertReplacesOlder() {
        QuickJoin j = fresh();
        j.offer(q("EU:sword"));
        now += 5_000;
        j.offer(q("NA_SA:uhc"));
        check(j.take(false).map(Queue::queueId).orElse("").equals("NA_SA:uhc"), "J joins the latest announced queue");
    }

    private static void clearForgets() {
        QuickJoin j = fresh();
        j.offer(q("EU:smp"));
        j.clear();
        check(j.take(false).isEmpty(), "cleared alerts are not joined");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + message);
    }
}
