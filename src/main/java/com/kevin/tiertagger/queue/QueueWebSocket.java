package com.kevin.tiertagger.queue;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.queue.QueueMessages.Hello;
import com.kevin.tiertagger.queue.QueueMessages.NextUp;
import com.kevin.tiertagger.queue.QueueMessages.Queue;
import com.kevin.tiertagger.queue.QueueMessages.QueueClose;
import com.kevin.tiertagger.queue.QueueMessages.Removed;
import com.kevin.tiertagger.queue.QueueMessages.Result;
import com.kevin.tiertagger.queue.QueueMessages.YourTurn;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The queue connection as the game sees it: {@link QueueClient} does the networking, this class
 * turns its events into toasts and chat lines on the client thread and applies the player's
 * filters.
 *
 * <p>Alerts work without linking (anonymous connection). A linked install also gets its own
 * status, "you are next" / "your turn", and can join or leave queues.</p>
 */
public final class QueueWebSocket implements QueueClient.Listener {
    /** sessionIds already announced. 512 openings is far more than any hello could still list. */
    private static final Set<String> NOTIFIED = QueueTuning.newBoundedSet(512);

    /** The last alert the player can join with the quick join key. */
    public static final QuickJoin QUICK_JOIN = new QuickJoin(System::currentTimeMillis);

    private final QueueClient client;

    public QueueWebSocket() {
        this.client = new QueueClient(TierTagger.getClient(), TierTagger.GSON, new QueueClient.Settings() {
            @Override
            public boolean enabled() {
                return TierTagger.getManager().getConfig().isQueueNotify();
            }

            @Override
            public String url() {
                return TierTagger.getManager().getConfig().getQueueUrl();
            }

            @Override
            public String token() {
                return QueueTokenStore.get();
            }

            @Override
            public String clientName() {
                return clientHeader();
            }

            @Override
            public String playerId() {
                var user = Minecraft.getInstance().getUser();
                return user == null || user.getProfileId() == null ? null : user.getProfileId().toString();
            }
        }, this, new QueueClient.Log() {
            @Override
            public void info(String message) {
                TierTagger.getLogger().info(message);
            }

            @Override
            public void warn(String message) {
                TierTagger.getLogger().warn(message);
            }

            @Override
            public void debug(String message) {
                TierTagger.getLogger().debug(message);
            }
        });
    }

    /** {@code VTL-TierTagger/<mod version> mc/<game version>}, sent with every request. */
    public static String clientHeader() {
        String modVersion = FabricLoader.getInstance().getModContainer("vtl")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        return "VTL-Core/" + modVersion + " mc/" + SharedConstants.getCurrentVersion().name();
    }

    // === lifecycle ===

    public void start() {
        client.start();
    }

    public void stop() {
        client.stop();
    }

    /** Re-reads config and reconnects - used by the config widgets and after (un)linking. */
    public void restart() {
        client.restart();
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    /** What the server told this connection, or null while not connected. */
    public Hello hello() {
        return client.hello();
    }

    public Collection<Queue> openQueues() {
        return client.openQueues();
    }

    public CompletableFuture<Result> join(String queueId) {
        return client.join(queueId);
    }

    public CompletableFuture<Result> leave(String queueId) {
        return client.leave(queueId);
    }

    /** The chat of the player's current (or last) ticket. */
    public TicketLog ticketLog() {
        return client.ticketLog();
    }

    public CompletableFuture<QueueMessages.TicketSendResult> sendTicket(String ticketId, String text) {
        return client.sendTicket(ticketId, text);
    }

    // === events (socket thread) ===

    @Override
    public void onHello(Hello hello) {
        // The open queues it lists arrive as onQueueOpen(fromHello = true).
        if (hello != null && hello.relink() != null && QueueTokenStore.isLinked()) {
            // Bot V39 signed this game out (network, account or verified name changed); the token
            // is gone on the server, so forget it and tell the player to link again.
            QueueTokenStore.set(null);
            String message = hello.relinkMessage();
            runOnClientThread(() -> QueueNotifier.relink(message));
        }
    }

    @Override
    public void onQueueOpen(Queue queue, boolean fromHello) {
        if (queue == null || queue.sessionId() == null || queue.mode() == null || queue.region() == null) return;
        var config = TierTagger.getManager().getConfig();
        if (!config.matchesQueueFilter(queue.region(), queue.mode())) return;
        boolean linked = QueueTokenStore.isLinked();
        // Linked players see the queues they can actually join, like the Discord ping.
        if (linked && config.isQueueOnlyJoinable() && Boolean.FALSE.equals(queue.canJoin())) return;
        // Already waiting or being tested in it: nothing new to announce.
        if (queue.me() != null && (queue.me().isWaiting() || queue.me().isTesting())) return;
        if (!NOTIFIED.add(queue.sessionId())) return;

        Hello hello = client.hello();
        boolean joinButton = linked && hello != null && hello.canJoin() && !Boolean.FALSE.equals(queue.canJoin());
        if (joinButton) QUICK_JOIN.offer(queue);
        runOnClientThread(() -> QueueNotifier.queueOpen(queue, joinButton));
    }

    @Override
    public void onQueueClose(QueueClose close) {
        // Nothing to show; a reopen has a new sessionId and is announced again.
    }

    @Override
    public void onNextUp(NextUp message) {
        if (message == null || message.message() == null) return;
        runOnClientThread(() -> QueueNotifier.nextUp(message.message()));
    }

    /** The last "your turn" message, so the hub can offer its ticket link. */
    private static volatile YourTurn lastYourTurn;

    public static YourTurn lastYourTurn() {
        return lastYourTurn;
    }

    @Override
    public void onYourTurn(YourTurn message) {
        if (message == null || message.message() == null) return;
        lastYourTurn = message;
        QUICK_JOIN.clear();
        runOnClientThread(() -> {
            QueueNotifier.yourTurn(message.message(), message.ticketUrl());
            QueueNotifier.yourTurnTitle(message);
        });
    }

    @Override
    public void onRemoved(Removed message) {
        if (message == null || message.message() == null) return;
        runOnClientThread(() -> QueueNotifier.warn(Component.literal(message.message())));
    }

    @Override
    public void onTicketMessage(QueueMessages.TicketChat chat) {
        var m = chat.message();
        // the player's own lines and the bot's notices need no toast; the tab shows them
        if (m == null || m.author() == null || m.author().self() || "bot".equals(m.author().role())) return;
        runOnClientThread(() -> {
            if (!com.kevin.tiertagger.hub.TicketTab.isShowing()) QueueNotifier.ticketMessage(m.author().name(), m.text());
        });
    }

    /** The player's tier in the ticket's kit when it opened, for bots that do not send it (before V38). */
    private static volatile String tierGuess;
    /** A result that arrived while another screen was open; R shows it. */
    private static volatile com.kevin.tiertagger.hub.VtlResultScreen.Data pendingResult;

    @Override
    public void onTicketOpen(QueueMessages.Ticket ticket) {
        tierGuess = ownTier(ticket.mode());
    }

    /** {@code "HT3"}, {@code "NONE"} when the tierlist has none for this kit, null when it is not loaded. */
    private static String ownTier(String mode) {
        try {
            var mc = Minecraft.getInstance();
            var rankings = com.kevin.tiertagger.TierCache.getPlayerRankings(mc.getGameProfile().id(), mc.getUser().getName());
            if (rankings.isEmpty()) return null;
            var r = rankings.get().get(mode);
            return r == null ? "NONE" : (r.pos() == 0 ? "H" : "L") + "T" + r.tier();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void onTicketClose(QueueMessages.TicketClose close) {
        var ticket = client.ticketLog().snapshot().ticket();
        boolean isResult = "result".equals(close.reason()) && ticket != null && close.ticketId().equals(ticket.ticketId());
        if (!isResult) {
            runOnClientThread(() -> QueueNotifier.ticketClosed(close));
            return;
        }
        var result = close.result();
        String previous = result != null && result.previousTier() != null ? result.previousTier() : tierGuess;
        String tester = ticket.testers() == null || ticket.testers().isEmpty() ? null : ticket.testers().get(0);
        var data = new com.kevin.tiertagger.hub.VtlResultScreen.Data(com.kevin.tiertagger.hub.Kit.byId(ticket.mode()), ticket.region(), tester,
                previous, result == null ? null : result.tier());
        runOnClientThread(() -> showResult(data));
    }

    /** Opens the result now when nothing else is on screen (or the hub is), else waits for R. */
    public static void showResult(com.kevin.tiertagger.hub.VtlResultScreen.Data data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null || mc.screen instanceof com.kevin.tiertagger.hub.VtlHubScreen) {
            pendingResult = null;
            mc.setScreen(new com.kevin.tiertagger.hub.VtlResultScreen(mc.screen, data));
        } else {
            pendingResult = data;
            QueueNotifier.resultWaiting();
        }
    }

    /** The result waiting for R, cleared by taking it. */
    public static com.kevin.tiertagger.hub.VtlResultScreen.Data takePendingResult() {
        var data = pendingResult;
        pendingResult = null;
        return data;
    }

    @Override
    public void onTokenRevoked(String token) {
        // The link is gone (unlinked on Discord, or linked on another install). Forget that
        // token - only that one: a re-link may have stored a new token in the meantime - and
        // keep the alerts going.
        boolean cleared = QueueTokenStore.clearIf(token);
        if (cleared && !QueueLinker.isLinking()) {
            runOnClientThread(() -> QueueNotifier.warn(Component.translatable("tiertagger.queue.tokenRejected")));
        }
        client.start();
    }

    @Override
    public void onStopped(QueueClient.StopReason reason) {
        String key = reason == QueueClient.StopReason.OUTDATED ? "tiertagger.queue.outdated" : "tiertagger.queue.replaced";
        runOnClientThread(() -> QueueNotifier.warn(Component.translatable(key)));
    }

    // === helpers ===

    /** Toasts and chat touch client state; socket callbacks run on the HTTP executor. */
    public static void runOnClientThread(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isRunning()) return;
        mc.execute(task);
    }

    /** Text for a join/leave answer: the server's own message, or ours for local failures. */
    public static Component describe(Result result) {
        if (result.message() != null && !result.message().isBlank()) return Component.literal(result.message());
        String code = result.code() == null ? "failed" : result.code();
        return switch (code) {
            case "offline", "disconnected" -> Component.translatable("tiertagger.queue.join.offline");
            case "timeout" -> Component.translatable("tiertagger.queue.join.timeout");
            default -> Component.translatable("tiertagger.queue.join.failed");
        };
    }
}
