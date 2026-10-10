package com.kevin.tiertagger.hub;

import com.kevin.tiertagger.hub.HubState.Action;
import com.kevin.tiertagger.hub.HubState.KitView;
import com.kevin.tiertagger.hub.HubState.Status;
import com.kevin.tiertagger.queue.QueueMessages.Me;
import com.kevin.tiertagger.queue.QueueMessages.Queue;

import java.util.List;

/**
 * Runnable self-check for what the VTL Hub shows for each kit and which button it offers.
 * Pure Java, no game: {@code ./gradlew hubStateCheck}.
 */
public final class HubStateCheck {
    private HubStateCheck() {}

    private static int checks = 0;

    public static void main(String[] args) {
        allEightKitsInOrder();
        closedKitIsClosed();
        openKitShowsWaiting();
        twoRegionsPrefersMine();
        twoRegionsWithoutRegionPrefersMoreWaiting();
        joinableBeatsMyRegionWhenMineIsNotOpenToMe();
        waitingWinsOverRegion();
        testingWins();
        canJoinFalseIsNotOpenToYou();
        anonymousIsOpen();
        actionsTable();
        queueClosedWhileWaitingFallsBack();
        unknownModeIgnored();
        defaultSelection();
        System.out.println("HubStateCheck: " + checks + " checks passed");
    }

    // --- builders ---

    private static Queue q(String region, String mode, int waiting, Boolean canJoin, Me me) {
        return new Queue(region + ":" + mode, region + ":" + mode + ":1", region, mode, mode, 1L, List.of("Tester"), waiting, canJoin, me);
    }

    private static Queue q(String region, String mode, int waiting) {
        return q(region, mode, waiting, true, new Me("none", null));
    }

    private static KitView view(List<KitView> views, Kit kit) {
        return views.stream().filter(v -> v.kit() == kit).findFirst().orElseThrow();
    }

    // --- checks ---

    private static void allEightKitsInOrder() {
        List<KitView> views = HubState.kits(List.of(), null);
        check(views.size() == 8, "always 8 kits, got " + views.size());
        check(views.stream().map(v -> v.kit().id()).toList().equals(List.of("axe", "vanilla", "mace", "sword", "nethop", "pot", "uhc", "smp")),
                "carousel order is Axe, Vanilla, Mace, Sword, NethOP, Pot, UHC, SMP");
        check(Kit.byId("nethop") == Kit.NETHOP && Kit.byId("NethOP") == Kit.NETHOP && Kit.byId("bogus") == null, "Kit.byId is case-insensitive and null for unknown ids");
        check("NethOP".equals(Kit.NETHOP.label()) && Kit.SWORD.color() == 0xFF8A3D, "labels and colours match the spec");
    }

    private static void closedKitIsClosed() {
        KitView v = view(HubState.kits(List.of(q("EU", "sword", 3)), "EU"), Kit.MACE);
        check(v.status() == Status.CLOSED && v.chosen() == null && v.queues().isEmpty(), "a kit with no open queue is CLOSED");
    }

    private static void openKitShowsWaiting() {
        KitView v = view(HubState.kits(List.of(q("EU", "sword", 3)), "EU"), Kit.SWORD);
        check(v.status() == Status.OPEN && v.waiting() == 3 && v.myPosition() == null, "an open kit shows its waiting count");
    }

    private static void twoRegionsPrefersMine() {
        List<Queue> open = List.of(q("AS_ME", "sword", 9), q("EU", "sword", 1));
        check("EU".equals(view(HubState.kits(open, "EU"), Kit.SWORD).chosen().region()), "the player's own region is chosen first");
        check("AS_ME".equals(view(HubState.kits(open, "AS_ME"), Kit.SWORD).chosen().region()), "AS_ME player gets the AS_ME queue");
        check(view(HubState.kits(open, "EU"), Kit.SWORD).queues().size() == 2, "both regions stay listed for the region switch");
    }

    private static void twoRegionsWithoutRegionPrefersMoreWaiting() {
        List<Queue> open = List.of(q("NA_SA", "mace", 2), q("EU", "mace", 5));
        check("EU".equals(view(HubState.kits(open, null), Kit.MACE).chosen().region()), "with no known region the busier queue is chosen");
    }

    private static void joinableBeatsMyRegionWhenMineIsNotOpenToMe() {
        List<Queue> open = List.of(q("EU", "pot", 2, false, new Me("none", null)), q("AS_ME", "pot", 1, true, new Me("none", null)));
        KitView v = view(HubState.kits(open, "EU"), Kit.POT);
        check("AS_ME".equals(v.chosen().region()) && v.status() == Status.OPEN, "a queue the player can join beats their region's closed-to-them queue");
    }

    private static void waitingWinsOverRegion() {
        List<Queue> open = List.of(q("EU", "sword", 4), q("AS_ME", "sword", 3, true, new Me("waiting", 2)));
        KitView v = view(HubState.kits(open, "EU"), Kit.SWORD);
        check(v.status() == Status.WAITING && "AS_ME".equals(v.chosen().region()) && v.myPosition() == 2, "the queue the player waits in wins, with their position");
    }

    private static void testingWins() {
        List<Queue> open = List.of(q("EU", "uhc", 1, true, new Me("testing", null)));
        KitView v = view(HubState.kits(open, "EU"), Kit.UHC);
        check(v.status() == Status.TESTING && v.myPosition() == null, "being tested shows TESTING");
    }

    private static void canJoinFalseIsNotOpenToYou() {
        KitView v = view(HubState.kits(List.of(q("NA_SA", "mace", 0, false, new Me("none", null))), "EU"), Kit.MACE);
        check(v.status() == Status.NOT_OPEN_TO_YOU, "canJoin false means NOT_OPEN_TO_YOU");
    }

    private static void anonymousIsOpen() {
        KitView v = view(HubState.kits(List.of(q("EU", "smp", 2, null, null)), null), Kit.SMP);
        check(v.status() == Status.OPEN && v.myPosition() == null, "an anonymous connection (canJoin null, no me) sees OPEN");
    }

    private static void actionsTable() {
        List<KitView> views = HubState.kits(List.of(
                q("EU", "sword", 3),
                q("EU", "mace", 1, true, new Me("waiting", 1)),
                q("EU", "uhc", 1, true, new Me("testing", null)),
                q("NA_SA", "pot", 0, false, new Me("none", null))), "EU");
        KitView open = view(views, Kit.SWORD), waiting = view(views, Kit.MACE), testing = view(views, Kit.UHC),
                closed = view(views, Kit.AXE), notMine = view(views, Kit.POT);
        check(HubState.action(open, false, true, true) == Action.OFFLINE, "offline wins over everything");
        check(HubState.action(open, true, false, true) == Action.LINK, "open but not linked -> LINK");
        check(HubState.action(open, true, true, true) == Action.JOIN, "open and linked -> JOIN");
        check(HubState.action(open, true, true, false) == Action.JOIN_DISABLED, "staff turned joining from the game off -> JOIN_DISABLED");
        check(HubState.action(waiting, true, true, true) == Action.LEAVE, "waiting -> LEAVE");
        check(HubState.action(testing, true, true, true) == Action.OPEN_TICKET, "testing -> OPEN_TICKET");
        check(HubState.action(closed, true, true, true) == Action.NO_QUEUE, "closed -> NO_QUEUE");
        check(HubState.action(closed, true, false, true) == Action.NO_QUEUE, "closed beats LINK");
        check(HubState.action(notMine, true, true, true) == Action.JOIN, "not open to you still offers JOIN; the bot answers why not");
    }

    private static void queueClosedWhileWaitingFallsBack() {
        List<KitView> before = HubState.kits(List.of(q("EU", "sword", 2, true, new Me("waiting", 1))), "EU");
        check(view(before, Kit.SWORD).status() == Status.WAITING, "waiting before the close");
        List<KitView> after = HubState.kits(List.of(), "EU");
        check(after.stream().allMatch(v -> v.status() == Status.CLOSED), "after the close every kit is CLOSED");
        check(after.get(HubState.defaultSelection(after)).kit() == Kit.SWORD, "selection falls back to Sword");
    }

    private static void unknownModeIgnored() {
        List<KitView> views = HubState.kits(List.of(q("EU", "spear", 4), q("EU", null, 1)), "EU");
        check(views.size() == 8 && views.stream().allMatch(v -> v.status() == Status.CLOSED), "unknown or missing modes are ignored");
    }

    private static void defaultSelection() {
        List<KitView> waiting = HubState.kits(List.of(q("EU", "vanilla", 1), q("EU", "pot", 2, true, new Me("waiting", 2))), "EU");
        check(waiting.get(HubState.defaultSelection(waiting)).kit() == Kit.POT, "the kit the player waits in is selected first");
        List<KitView> open = HubState.kits(List.of(q("EU", "uhc", 1), q("EU", "vanilla", 1)), "EU");
        check(open.get(HubState.defaultSelection(open)).kit() == Kit.VANILLA, "else the first open kit in carousel order");
        List<KitView> notMineFirst = HubState.kits(List.of(q("AS_ME", "vanilla", 0, false, new Me("none", null)), q("EU", "sword", 1)), "EU");
        check(notMineFirst.get(HubState.defaultSelection(notMineFirst)).kit() == Kit.SWORD,
                "a kit I can join beats an earlier kit that is not open to me");
        List<KitView> onlyNotMine = HubState.kits(List.of(q("NA_SA", "mace", 0, false, new Me("none", null))), "EU");
        check(onlyNotMine.get(HubState.defaultSelection(onlyNotMine)).kit() == Kit.MACE,
                "with nothing joinable, an open kit (not open to me) still beats a closed Sword");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + message);
    }
}
