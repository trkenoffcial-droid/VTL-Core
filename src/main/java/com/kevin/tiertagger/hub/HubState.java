package com.kevin.tiertagger.hub;

import com.kevin.tiertagger.queue.QueueMessages.Queue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What the VTL Hub shows for each kit, worked out from the open queues the bot reported.
 *
 * <p>Pure decisions, no drawing and no Minecraft types, so {@code HubStateCheck} covers them
 * without a game. The hub, the HUD and the quick join all read from here.</p>
 */
public final class HubState {
    private HubState() {}

    public enum Status {
        /** No queue open for this kit. */
        CLOSED,
        /** At least one queue open that the player may join (or could not be told otherwise). */
        OPEN,
        /** Open, but not for this player (region or waitlist role). */
        NOT_OPEN_TO_YOU,
        /** The player is in line. */
        WAITING,
        /** The player is being tested. */
        TESTING,
        /** This version of the game cannot play the kit (Spear Mace before 1.21.11). */
        LOCKED
    }

    /** The big button under the carousel. */
    public enum Action { LINK, JOIN, LEAVE, OPEN_TICKET, NO_QUEUE, OFFLINE, JOIN_DISABLED, LOCKED }

    /**
     * One card of the carousel.
     *
     * @param queues     every open queue of this kit (one per region), for the region switch
     * @param chosen     the queue the hub talks about, null when closed
     * @param waiting    players waiting in {@code chosen}
     * @param myPosition the player's place in line while waiting, else null
     */
    public record KitView(Kit kit, Status status, List<Queue> queues, Queue chosen, int waiting, Integer myPosition) {
    }

    /** Always one view per kit, in carousel order; queues of unknown modes are ignored. */
    public static List<KitView> kits(Collection<Queue> open, String myRegion) {
        return kits(open, myRegion, kit -> true);
    }

    /**
     * Same, with the kits this game can play: the others are {@link Status#LOCKED}, unless the
     * player is already waiting or tested in them (so they can still see and leave the line).
     */
    public static List<KitView> kits(Collection<Queue> open, String myRegion, Predicate<Kit> playable) {
        List<KitView> views = new ArrayList<>(Kit.values().length);
        for (Kit kit : Kit.values()) {
            List<Queue> mine = open.stream()
                    .filter(q -> q != null && kit == Kit.byId(q.mode()))
                    .toList();
            KitView view = view(kit, mine, myRegion);
            if (!playable.test(kit) && view.status() != Status.WAITING && view.status() != Status.TESTING) {
                view = new KitView(kit, Status.LOCKED, mine, null, 0, null);
            }
            views.add(view);
        }
        return views;
    }

    /** The same card for one queue chosen by hand (the region switch). */
    public static KitView withChosen(KitView view, Queue chosen) {
        return describe(view.kit(), view.queues(), chosen);
    }

    private static KitView view(Kit kit, List<Queue> queues, String myRegion) {
        return describe(kit, queues, choose(queues, myRegion));
    }

    private static KitView describe(Kit kit, List<Queue> queues, Queue chosen) {
        if (chosen == null) return new KitView(kit, Status.CLOSED, queues, null, 0, null);
        Status status;
        if (isTesting(chosen)) status = Status.TESTING;
        else if (isWaiting(chosen)) status = Status.WAITING;
        else if (Boolean.FALSE.equals(chosen.canJoin())) status = Status.NOT_OPEN_TO_YOU;
        else status = Status.OPEN;
        Integer position = status == Status.WAITING ? chosen.me().position() : null;
        return new KitView(kit, status, queues, chosen, chosen.waiting(), position);
    }

    /**
     * Which queue of a kit to show: the one the player is tested or waits in; else one they can
     * join, in their region first, then the busiest; else their region; else the busiest.
     */
    private static Queue choose(List<Queue> queues, String myRegion) {
        if (queues.isEmpty()) return null;
        Optional<Queue> involved = queues.stream().filter(HubState::isTesting).findFirst()
                .or(() -> queues.stream().filter(HubState::isWaiting).findFirst());
        if (involved.isPresent()) return involved.get();

        Comparator<Queue> preference = Comparator
                .comparing((Queue q) -> Boolean.FALSE.equals(q.canJoin()))      // joinable first
                .thenComparing(q -> !regionMatches(q.region(), myRegion))       // then my region
                .thenComparing(Comparator.comparingInt(Queue::waiting).reversed()); // then busiest
        return queues.stream().min(preference).orElse(null);
    }

    public static Action action(KitView view, boolean connected, boolean linked, boolean joinAllowed) {
        if (view.status() == Status.LOCKED) return Action.LOCKED;
        if (!connected) return Action.OFFLINE;
        return switch (view.status()) {
            case TESTING -> Action.OPEN_TICKET;
            case WAITING -> Action.LEAVE;
            case CLOSED -> Action.NO_QUEUE;
            case LOCKED -> Action.LOCKED;
            case OPEN, NOT_OPEN_TO_YOU -> !linked ? Action.LINK : !joinAllowed ? Action.JOIN_DISABLED : Action.JOIN;
        };
    }

    /**
     * The card to start on: the kit the player is in, else the first kit they can join, else the
     * first kit open to someone else, else Sword.
     */
    public static int defaultSelection(List<KitView> views) {
        for (int i = 0; i < views.size(); i++) {
            Status s = views.get(i).status();
            if (s == Status.WAITING || s == Status.TESTING) return i;
        }
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i).status() == Status.OPEN) return i;
        }
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i).status() != Status.CLOSED && views.get(i).status() != Status.LOCKED) return i;
        }
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i).kit() == Kit.SWORD) return i;
        }
        return 0;
    }

    /** {@code "EU"} matches {@code "EU"}; a bare part like {@code "AS"} matches {@code "AS_ME"}. */
    static boolean regionMatches(String queueRegion, String myRegion) {
        if (queueRegion == null || myRegion == null) return false;
        if (queueRegion.equalsIgnoreCase(myRegion)) return true;
        for (String part : queueRegion.split("_")) {
            if (part.equalsIgnoreCase(myRegion)) return true;
        }
        return false;
    }

    private static boolean isWaiting(Queue q) {
        return q.me() != null && q.me().isWaiting();
    }

    private static boolean isTesting(Queue q) {
        return q.me() != null && q.me().isTesting();
    }
}
