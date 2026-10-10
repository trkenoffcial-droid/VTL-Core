package com.kevin.tiertagger.queue;

import com.kevin.tiertagger.queue.QueueMessages.Author;
import com.kevin.tiertagger.queue.QueueMessages.Ticket;
import com.kevin.tiertagger.queue.QueueMessages.TicketClose;
import com.kevin.tiertagger.queue.QueueMessages.TicketMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The chat of the player's current Discord ticket, as the TICKET tab shows it.
 *
 * <p>Messages are kept in time order without duplicates (a live relay, the history and the
 * answer to a send can all carry the same Discord message). A closed ticket keeps its
 * transcript until the next one opens. Written from the socket thread, read from the render
 * thread through {@link #snapshot()}.</p>
 */
public final class TicketLog {
    public static final int MAX_MESSAGES = 300;

    public enum State { SENT, PENDING, FAILED }

    /** One line of the chat; {@code error} is set for a FAILED message. */
    public record Entry(TicketMessage message, State state, String error, long seq) {
    }

    /** What the screen needs, copied so it can be drawn without holding the lock. */
    public record Snapshot(Ticket ticket, boolean closed, TicketClose close, List<Entry> entries, int unread, long version) {
        /** A ticket is open and messages can be sent. */
        public boolean active() {
            return ticket != null && !closed;
        }
    }

    private static final Comparator<Entry> ORDER = Comparator.comparingLong((Entry e) -> e.message().time()).thenComparingLong(Entry::seq);

    private Ticket ticket;
    private boolean closed;
    private TicketClose close;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();
    private int unread;
    private long seq;
    private long version;
    private int localIds;

    /** A ticket opened (or a hello named it again after a reconnect). */
    public synchronized void open(Ticket t) {
        if (t == null || t.ticketId() == null) return;
        boolean same = ticket != null && t.ticketId().equals(ticket.ticketId()) && !closed;
        if (!same) {
            entries.clear();
            ids.clear();
            unread = 0;
            closed = false;
            close = null;
        }
        ticket = t;
        version++;
    }

    /** A live message; returns whether it was new. Messages from anyone but the player count as unread. */
    public synchronized boolean add(String ticketId, TicketMessage m) {
        if (!accepts(ticketId) || m == null || m.id() == null || ids.contains(m.id())) return false;
        insert(m, State.SENT, null);
        if (m.author() == null || !m.author().self()) unread++;
        version++;
        return true;
    }

    /** Older messages fetched after a (re)connect; never unread. */
    public synchronized void history(String ticketId, List<TicketMessage> messages) {
        if (ticket == null || !ticket.ticketId().equals(ticketId) || messages == null) return;
        boolean changed = false;
        for (TicketMessage m : messages) {
            if (m == null || m.id() == null || ids.contains(m.id())) continue;
            insert(m, State.SENT, null);
            changed = true;
        }
        if (changed) version++;
    }

    /** Shows a message the player is sending; returns its local id, or null when there is nothing to send to. */
    public synchronized String pending(String text, long now, String selfName) {
        if (ticket == null || closed) return null;
        String localId = "local-" + (++localIds);
        insert(new TicketMessage(localId, now, new Author(selfName, "player", true), text, true, List.of()), State.PENDING, null);
        version++;
        return localId;
    }

    /** The server posted the message: its copy replaces the pending one. */
    public synchronized void sent(String localId, TicketMessage sent) {
        int i = indexOf(localId);
        if (i < 0) return;
        Entry old = entries.get(i);
        ids.remove(localId);
        if (sent == null || sent.id() == null || ids.contains(sent.id())) {
            entries.remove(i);
        } else {
            entries.set(i, new Entry(sent, State.SENT, null, old.seq()));
            ids.add(sent.id());
            entries.sort(ORDER);
        }
        version++;
    }

    /** The server refused the message; it stays, marked with the reason. */
    public synchronized void failed(String localId, String error) {
        int i = indexOf(localId);
        if (i < 0) return;
        Entry old = entries.get(i);
        entries.set(i, new Entry(old.message(), State.FAILED, error, old.seq()));
        version++;
    }

    public synchronized void close(TicketClose c) {
        if (c == null || ticket == null || closed || !ticket.ticketId().equals(c.ticketId())) return;
        closed = true;
        close = c;
        version++;
    }

    public synchronized void markRead() {
        if (unread == 0) return;
        unread = 0;
        version++;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(ticket, closed, close, List.copyOf(entries), unread, version);
    }

    // --- helpers ---

    private boolean accepts(String ticketId) {
        return ticket != null && !closed && ticket.ticketId().equals(ticketId);
    }

    private void insert(TicketMessage m, State state, String error) {
        entries.add(new Entry(m, state, error, ++seq));
        ids.add(m.id());
        entries.sort(ORDER);
        while (entries.size() > MAX_MESSAGES) ids.remove(entries.remove(0).message().id());
    }

    private int indexOf(String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).message().id().equals(id)) return i;
        }
        return -1;
    }
}
