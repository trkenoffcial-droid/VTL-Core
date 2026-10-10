package com.kevin.tiertagger.queue;

import com.kevin.tiertagger.queue.QueueMessages.Queue;

import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Remembers the last queue alert the player could join, for the quick join key (J).
 *
 * <p>The key only ever joins what was just announced, within {@link #WINDOW_MS}, once, and never
 * while a screen (chat, a text box) is open. It is a shortcut for the [Join] button, not an
 * automatic join.</p>
 */
public final class QuickJoin {
    public static final long WINDOW_MS = 30_000L;

    private final LongSupplier clock;
    private Queue offered;
    private long offeredAt;

    public QuickJoin(LongSupplier clock) {
        this.clock = clock;
    }

    /** A joinable queue was just announced. */
    public synchronized void offer(Queue queue) {
        this.offered = queue;
        this.offeredAt = clock.getAsLong();
    }

    /** The queue to join for this key press, if any; it is used up. */
    public synchronized Optional<Queue> take(boolean screenOpen) {
        if (offered == null || screenOpen) return Optional.empty();
        Queue queue = offered;
        offered = null;
        if (clock.getAsLong() - offeredAt > WINDOW_MS) return Optional.empty();
        return Optional.of(queue);
    }

    public synchronized void clear() {
        offered = null;
    }
}
