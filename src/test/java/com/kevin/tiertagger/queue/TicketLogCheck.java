package com.kevin.tiertagger.queue;

import com.kevin.tiertagger.queue.QueueMessages.Author;
import com.kevin.tiertagger.queue.QueueMessages.Ticket;
import com.kevin.tiertagger.queue.QueueMessages.TicketClose;
import com.kevin.tiertagger.queue.QueueMessages.TicketMessage;
import com.kevin.tiertagger.queue.QueueMessages.TicketResult;
import com.kevin.tiertagger.queue.TicketLog.Entry;
import com.kevin.tiertagger.queue.TicketLog.Snapshot;
import com.kevin.tiertagger.queue.TicketLog.State;

import java.util.List;

/**
 * Runnable self-check for the ticket chat log the TICKET tab shows: order, duplicates,
 * unread count, messages being sent, and what a closed ticket keeps. Pure Java, no game:
 * part of {@code ./gradlew hubStateCheck}.
 */
public final class TicketLogCheck {
    private TicketLogCheck() {}

    private static int checks = 0;

    public static void main(String[] args) {
        openStartsEmpty();
        unreadCountsOnlyOthers();
        duplicateIdsIgnored();
        otherTicketIgnored();
        historyMergesInTimeOrder();
        pendingThenSent();
        pendingThenFailed();
        noPendingWithoutOpenTicket();
        closeKeepsTranscript();
        newTicketStartsFresh();
        sameTicketAgainKeepsMessages();
        boundedLog();
        versionMovesOnChange();
        System.out.println("TicketLogCheck: " + checks + " checks passed");
    }

    // --- builders ---

    private static Ticket ticket(String id) {
        return new Ticket(id, "EU:sword", "sword", "Sword", "EU", List.of("SwordTester"), 1L, "https://discord.com/channels/1/" + id);
    }

    private static TicketMessage msg(String id, long at, String role, boolean self, String text) {
        return new TicketMessage(id, at, new Author(role.equals("player") ? "Me" : "SwordTester", role, self), text, false, List.of());
    }

    private static TicketMessage tester(String id, long at, String text) {
        return msg(id, at, "tester", false, text);
    }

    private static List<String> texts(Snapshot s) {
        return s.entries().stream().map(e -> e.message().text()).toList();
    }

    // --- checks ---

    private static void openStartsEmpty() {
        TicketLog log = new TicketLog();
        check(!log.snapshot().active(), "no ticket before open");
        log.open(ticket("T1"));
        Snapshot s = log.snapshot();
        check(s.active() && "T1".equals(s.ticket().ticketId()), "open makes T1 the active ticket");
        check(s.entries().isEmpty() && s.unread() == 0 && !s.closed(), "a new ticket has no messages, nothing unread");
    }

    private static void unreadCountsOnlyOthers() {
        TicketLog log = new TicketLog();
        log.open(ticket("T1"));
        log.add("T1", tester("a", 100, "which server?"));
        check(log.snapshot().unread() == 1, "a tester message is unread");
        log.add("T1", msg("b", 110, "player", true, "from my phone"));
        check(log.snapshot().unread() == 1, "my own message (even typed on Discord) is never unread");
        log.markRead();
        check(log.snapshot().unread() == 0, "markRead clears the count");
    }

    private static void duplicateIdsIgnored() {
        TicketLog log = new TicketLog();
        log.open(ticket("T1"));
        check(log.add("T1", tester("a", 100, "hi")), "first copy is added");
        check(!log.add("T1", tester("a", 100, "hi")), "second copy is ignored");
        log.history("T1", List.of(tester("a", 100, "hi"), tester("b", 120, "ready?")));
        check(texts(log.snapshot()).equals(List.of("hi", "ready?")), "history does not duplicate a message already shown");
        check(log.snapshot().unread() == 1, "only the live message counted as unread");
    }

    private static void otherTicketIgnored() {
        TicketLog log = new TicketLog();
        log.open(ticket("T1"));
        check(!log.add("T2", tester("a", 100, "wrong ticket")), "a message for another ticket is ignored");
        log.history("T2", List.of(tester("b", 100, "wrong")));
        check(log.snapshot().entries().isEmpty(), "history for another ticket is ignored");
    }

    private static void historyMergesInTimeOrder() {
        TicketLog log = new TicketLog();
        log.open(ticket("T1"));
        log.add("T1", tester("c", 300, "third"));
        log.history("T1", List.of(tester("a", 100, "first"), tester("b", 200, "second")));
        check(texts(log.snapshot()).equals(List.of("first", "second", "third")), "history slots in before newer live messages");
        check(log.snapshot().unread() == 1, "history never counts as unread");
    }

    private static void pendingThenSent() {
        TicketLog log = new TicketLog();
        log.open(ticket("T1"));
        String local = log.pending("joining now", 500L, "Me");
        Entry e = log.snapshot().entries().get(0);
        check(local != null && e.state() == State.PENDING && e.message().author().self() && "joining now".equals(e.message().text()),
                "a message being sent shows at once, marked pending");
        log.sent(local, new TicketMessage("d1", 510L, new Author("Me", "player", true), "joining now", true, List.of()));
        e = log.snapshot().entries().get(0);
        check(e.state() == State.SENT && "d1".equals(e.message().id()) && log.snapshot().entries().size() == 1, "the server's copy replaces the pending one");
        check(!log.add("T1", new TicketMessage("d1", 510L, new Author("Me", "player", true), "joining now", true, List.of())),
                "a relay of the same Discord message is not shown twice");
    }

    private static void pendingThenFailed() {
        TicketLog log = new TicketLog();
        log.open(ticket("T1"));
        String local = log.pending("spam", 500L, "Me");
        log.failed(local, "You are sending messages too fast. Wait a moment.");
        Entry e = log.snapshot().entries().get(0);
        check(e.state() == State.FAILED && e.error().startsWith("You are sending"), "a refused message stays, marked failed with the reason");
    }

    private static void noPendingWithoutOpenTicket() {
        TicketLog log = new TicketLog();
        check(log.pending("hi", 1L, "Me") == null, "nothing to send to before a ticket opens");
        log.open(ticket("T1"));
        log.close(new TicketClose("ticket_close", 2, "T1", "closed", "Your ticket was closed.", null));
        check(log.pending("hi", 1L, "Me") == null, "nothing to send to after it closed");
    }

    private static void closeKeepsTranscript() {
        TicketLog log = new TicketLog();
        log.open(ticket("T1"));
        log.add("T1", tester("a", 100, "gg"));
        log.close(new TicketClose("ticket_close", 2, "T2", "closed", "x", null));
        check(!log.snapshot().closed(), "closing another ticket does nothing");
        log.close(new TicketClose("ticket_close", 2, "T1", "result", "Test complete: HT3 in Sword.", new TicketResult("HT3", "sword", "LT3")));
        Snapshot s = log.snapshot();
        check(s.closed() && !s.active() && "HT3".equals(s.close().result().tier()), "closed with the result");
        check(texts(s).equals(List.of("gg")), "the transcript stays readable");
        check(!log.add("T1", tester("b", 200, "late")), "no new messages after the close");
    }

    private static void newTicketStartsFresh() {
        TicketLog log = new TicketLog();
        log.open(ticket("T1"));
        log.add("T1", tester("a", 100, "old"));
        log.close(new TicketClose("ticket_close", 2, "T1", "closed", "x", null));
        log.open(ticket("T2"));
        Snapshot s = log.snapshot();
        check(s.active() && "T2".equals(s.ticket().ticketId()) && s.entries().isEmpty() && !s.closed(), "the next ticket starts empty");
    }

    private static void sameTicketAgainKeepsMessages() {
        TicketLog log = new TicketLog();
        log.open(ticket("T1"));
        log.add("T1", tester("a", 100, "hi"));
        log.open(ticket("T1")); // hello after a reconnect names the same ticket
        check(texts(log.snapshot()).equals(List.of("hi")), "the same ticket again keeps its messages");
    }

    private static void boundedLog() {
        TicketLog log = new TicketLog();
        log.open(ticket("T1"));
        for (int i = 0; i < TicketLog.MAX_MESSAGES + 5; i++) log.add("T1", tester("m" + i, i, "n" + i));
        Snapshot s = log.snapshot();
        check(s.entries().size() == TicketLog.MAX_MESSAGES, "the log keeps at most " + TicketLog.MAX_MESSAGES + " messages");
        check("n5".equals(s.entries().get(0).message().text()), "the oldest ones go first");
    }

    private static void versionMovesOnChange() {
        TicketLog log = new TicketLog();
        long v0 = log.snapshot().version();
        log.open(ticket("T1"));
        long v1 = log.snapshot().version();
        log.add("T1", tester("a", 1, "x"));
        long v2 = log.snapshot().version();
        log.add("T1", tester("a", 1, "x"));
        check(v1 > v0 && v2 > v1 && log.snapshot().version() == v2, "version moves on every change and only then");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("TicketLogCheck failed: " + message);
    }
}
