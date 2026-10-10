package com.kevin.tiertagger.queue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kevin.tiertagger.queue.QueueMessages.Hello;
import com.kevin.tiertagger.queue.QueueMessages.NextUp;
import com.kevin.tiertagger.queue.QueueMessages.Queue;
import com.kevin.tiertagger.queue.QueueMessages.QueueClose;
import com.kevin.tiertagger.queue.QueueMessages.QueueOpen;
import com.kevin.tiertagger.queue.QueueMessages.QueueUpdate;
import com.kevin.tiertagger.queue.QueueMessages.Removed;
import com.kevin.tiertagger.queue.QueueMessages.Result;
import com.kevin.tiertagger.queue.QueueMessages.Ticket;
import com.kevin.tiertagger.queue.QueueMessages.TicketChat;
import com.kevin.tiertagger.queue.QueueMessages.TicketClose;
import com.kevin.tiertagger.queue.QueueMessages.TicketHistory;
import com.kevin.tiertagger.queue.QueueMessages.TicketOpen;
import com.kevin.tiertagger.queue.QueueMessages.TicketSendResult;
import com.kevin.tiertagger.queue.QueueMessages.YourTurn;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Connection core for the queue WebSocket, protocol v2.
 *
 * <p>Owns the socket, the reconnect state machine, the list of open queues, the player's
 * ticket chat ({@link TicketLog}) and pending requests. It uses no Minecraft types: {@link QueueWebSocket} adapts the
 * {@link Listener} callbacks to toasts and chat, and {@code QueueProtocolCheck} drives this
 * class headless against the mock server.</p>
 *
 * <p>Threading: {@link WebSocket.Listener} callbacks are serialized by the JDK, so the frame
 * buffer needs no lock. {@link Listener} methods run on the socket thread - an implementation
 * that touches game state must hop to the client thread itself.</p>
 */
public final class QueueClient {
    /** Where to connect and as whom; read again on every connect. */
    public interface Settings {
        boolean enabled();

        String url();

        /** The linked token, or null for an anonymous (alerts only) connection. */
        String token();

        /** Sent as {@code X-VTL-Client}, e.g. {@code VTL-TierTagger/2.5.0 mc/1.21.11}. */
        String clientName();

        /**
         * The game's Minecraft UUID, sent as {@code X-VTL-Player} (bot V39 signs a link out when
         * the game runs another account than the one it linked). Null sends nothing.
         */
        default String playerId() {
            return null;
        }
    }

    public enum StopReason {
        /** Another game instance connected with the same account. */
        REPLACED,
        /** The bot does not speak this protocol version. */
        OUTDATED
    }

    /** Events, all delivered on the socket thread. */
    public interface Listener {
        void onHello(Hello hello);

        /** A queue opened; {@code fromHello} is true for queues listed in a hello. */
        void onQueueOpen(Queue queue, boolean fromHello);

        void onQueueClose(QueueClose close);

        void onNextUp(NextUp message);

        void onYourTurn(YourTurn message);

        void onRemoved(Removed message);

        /** {@code token} was revoked or rejected; the client stopped. */
        void onTokenRevoked(String token);

        /** A queue's line, testers or the player's place changed (bot V37+). */
        default void onQueueUpdate(Queue queue) {
        }

        /** A tester pulled the player and the ticket opened (bot V37+); not called for a hello. */
        default void onTicketOpen(Ticket ticket) {
        }

        /** A new message in the player's ticket, already added to {@link #ticketLog()}. */
        default void onTicketMessage(TicketChat chat) {
        }

        /** The ticket ended (result, closed, skipped); the log keeps the transcript. */
        default void onTicketClose(TicketClose close) {
        }

        void onStopped(StopReason reason);
    }

    /** Minimal logger so this class needs no logging library. */
    public interface Log {
        void info(String message);

        void warn(String message);

        void debug(String message);
    }

    private final HttpClient http;
    private final Gson gson;
    private final Settings settings;
    private final Listener listener;
    private final Log log;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tiertagger-queue-ws");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger attempt = new AtomicInteger(0);
    private final Map<String, Queue> openQueues = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Result>> pending = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<TicketSendResult>> pendingSends = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<TicketHistory>> pendingHistory = new ConcurrentHashMap<>();
    private final TicketLog tickets = new TicketLog();

    private volatile Hello hello;
    private volatile long lastFrameAt;
    private volatile long connectedAt;
    /** Bumped on every connect; callbacks from an older socket compare against it. */
    private final AtomicInteger generation = new AtomicInteger();

    public QueueClient(HttpClient http, Gson gson, Settings settings, Listener listener, Log log) {
        this.http = http;
        this.gson = gson;
        this.settings = settings;
        this.listener = listener;
        this.log = log;
    }

    // === state ===

    public boolean isConnected() {
        WebSocket ws = socket.get();
        return ws != null && !ws.isOutputClosed();
    }

    /** The last hello, or null before the first one (or after a disconnect). */
    public Hello hello() {
        return hello;
    }

    /** Every queue the server reported open, as seen by this connection. */
    public Collection<Queue> openQueues() {
        return List.copyOf(openQueues.values());
    }

    /** The chat of the player's current (or last) ticket. */
    public TicketLog ticketLog() {
        return tickets;
    }

    // === lifecycle ===

    /** Connects if enabled. Safe to call repeatedly - a second call while running does nothing. */
    public void start() {
        if (!settings.enabled()) return;
        if (running.getAndSet(true)) return;
        attempt.set(0);
        connect();
    }

    /** Disconnects and stops retrying. */
    public void stop() {
        running.set(false);
        attempt.set(0);
        reconnecting.set(false);
        generation.incrementAndGet();
        WebSocket ws = socket.getAndSet(null);
        if (ws != null) ws.abort();
        forgetConnection("disconnected");
    }

    /** Re-reads settings and reconnects. */
    public void restart() {
        stop();
        start();
    }

    /** Stops for good; used when the game shuts down. */
    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }

    // === requests ===

    /** Asks to join {@code queueId}. Completes with the server's answer, or a local failure. */
    public CompletableFuture<Result> join(String queueId) {
        return request("join_queue", queueId);
    }

    /** Asks to leave {@code queueId} (only possible while still waiting). */
    public CompletableFuture<Result> leave(String queueId) {
        return request("leave_queue", queueId);
    }

    private CompletableFuture<Result> request(String type, String queueId) {
        JsonObject message = new JsonObject();
        message.addProperty("type", type);
        message.addProperty("queueId", queueId);
        return send(message, pending, QueueClient::localFailure);
    }

    /** Longest message the server accepts from the game. */
    public static final int MAX_TICKET_TEXT = 500;

    /**
     * Posts {@code text} in the player's ticket. It shows in {@link #ticketLog()} at once as
     * pending, then as sent or failed (with the server's reason).
     */
    public CompletableFuture<TicketSendResult> sendTicket(String ticketId, String text) {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty() || clean.length() > MAX_TICKET_TEXT) {
            return CompletableFuture.completedFuture(localSendFailure(ticketId, "bad_text"));
        }
        Hello h = hello;
        String self = h != null && h.discordName() != null ? h.discordName() : "You";
        String localId = tickets.pending(clean, System.currentTimeMillis(), self);
        if (localId == null) return CompletableFuture.completedFuture(localSendFailure(ticketId, "closed"));

        JsonObject message = new JsonObject();
        message.addProperty("type", "ticket_send");
        message.addProperty("ticketId", ticketId);
        message.addProperty("text", clean);
        return send(message, pendingSends, code -> localSendFailure(ticketId, code)).thenApply(r -> {
            if (r.ok()) tickets.sent(localId, r.sent());
            else tickets.failed(localId, r.message() != null ? r.message() : r.code());
            return r;
        });
    }

    /** Fetches the last messages of the ticket and merges them into {@link #ticketLog()}. */
    public CompletableFuture<TicketHistory> ticketHistory(String ticketId) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "ticket_history");
        message.addProperty("ticketId", ticketId);
        return send(message, pendingHistory, code -> new TicketHistory("local", QueueMessages.VERSION, null, false, code, null, ticketId, List.of()));
    }

    /** Sends a request and completes with its answer, or with {@code failure(code)} when none comes. */
    private <R> CompletableFuture<R> send(JsonObject message, Map<String, CompletableFuture<R>> waiting, Function<String, R> failure) {
        WebSocket ws = socket.get();
        if (ws == null || ws.isOutputClosed()) {
            return CompletableFuture.completedFuture(failure.apply("offline"));
        }
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<R> future = new CompletableFuture<>();
        waiting.put(requestId, future);
        message.addProperty("v", QueueMessages.VERSION);
        message.addProperty("requestId", requestId);

        ws.sendText(gson.toJson(message), true).exceptionally(t -> {
            CompletableFuture<R> f = waiting.remove(requestId);
            if (f != null) f.complete(failure.apply("offline"));
            return null;
        });
        try {
            scheduler.schedule(() -> {
                CompletableFuture<R> f = waiting.remove(requestId);
                if (f != null) f.complete(failure.apply("timeout"));
            }, QueueTuning.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
        return future;
    }

    private static TicketSendResult localSendFailure(String ticketId, String code) {
        return new TicketSendResult("local", QueueMessages.VERSION, null, false, code, null, null, ticketId, null);
    }

    /** A failure that never reached the server; {@code message} is filled in by the UI. */
    private static Result localFailure(String code) {
        return new Result("local", QueueMessages.VERSION, null, false, code, null, null, null);
    }

    // === connection ===

    private void connect() {
        if (!running.get()) return;
        final int gen = generation.incrementAndGet();
        final String token = settings.token();

        URI uri;
        try {
            uri = URI.create(QueueTuning.withVersion(settings.url(), QueueMessages.VERSION));
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("Invalid queue websocket URL: " + settings.url());
            running.set(false);
            return;
        }

        // Drop any previous socket first: the server caps connections, and a half-dead one
        // left around could push a reconnect into a rejection loop.
        WebSocket previous = socket.getAndSet(null);
        if (previous != null) previous.abort();

        log.debug("Connecting to queue websocket at " + uri);
        WebSocket.Builder builder = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .header("X-VTL-Client", settings.clientName());
        String player = settings.playerId();
        if (player != null) builder.header("X-VTL-Player", player);
        if (token != null) {
            // A header, never the URL: URLs end up in proxy access logs.
            builder.header("Authorization", "Bearer " + token);
        }
        builder.buildAsync(uri, new SocketListener(gen, token))
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((ws, error) -> {
                    if (gen != generation.get()) {
                        // Superseded while connecting (stop/restart): drop it.
                        if (ws != null) ws.abort();
                        return;
                    }
                    if (error != null) {
                        handleHandshakeFailure(error, token);
                        return;
                    }
                    socket.set(ws);
                    connectedAt = System.currentTimeMillis();
                    lastFrameAt = connectedAt;
                    if (attempt.getAndSet(0) > 0) log.info("Queue websocket reconnected");
                    armWatchdog(gen);
                });
    }

    private void handleHandshakeFailure(Throwable error, String token) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;

        if (cause instanceof WebSocketHandshakeException handshake) {
            int status = handshake.getResponse().statusCode();
            if ((status == 401 || status == 403) && token != null) {
                if (Objects.equals(token, settings.token())) {
                    log.warn("Queue websocket rejected our token (HTTP " + status + "); re-link required");
                    running.set(false);
                    listener.onTokenRevoked(token);
                } else {
                    scheduleReconnect(0L); // the token changed meanwhile: just try again
                }
                return;
            }
            if (status == 404 || (status >= 300 && status < 400)) {
                // Not a passing outage: the URL is wrong or the gateway is not published. Say so once
                // per attempt (every 10 minutes), with where a redirect points.
                String target = handshake.getResponse().headers().firstValue("location").orElse(null);
                log.warn("Queue websocket at " + settings.url() + " answered HTTP " + status
                        + (target == null ? "" : " (redirects to " + target + ")") + "; check the Server URL in the Queue settings");
            } else {
                log.debug("Queue websocket handshake refused with HTTP " + status);
            }
            scheduleReconnect(QueueTuning.minDelayForHandshake(status));
            return;
        }
        log.debug("Queue websocket connect failed: " + (cause instanceof TimeoutException ? "timeout" : cause));
        scheduleReconnect(0L);
    }

    private void scheduleReconnect(long minDelayMs) {
        if (!running.get()) return;
        // onError and onClose can both fire for one failure; only the first schedules.
        if (!reconnecting.compareAndSet(false, true)) return;
        long delay = QueueTuning.backoffMillis(attempt.getAndIncrement(), minDelayMs, QueueTuning.jitter());
        log.debug("Queue websocket reconnecting in " + delay + "ms");
        try {
            scheduler.schedule(() -> {
                reconnecting.set(false);
                connect();
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            reconnecting.set(false);
        }
    }

    private void armWatchdog(int gen) {
        try {
            scheduler.schedule(() -> checkStale(gen), QueueTuning.STALE_AFTER_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private void checkStale(int gen) {
        if (!running.get() || gen != generation.get()) return;
        WebSocket ws = socket.get();
        if (ws == null) return;
        if (System.currentTimeMillis() - lastFrameAt >= QueueTuning.STALE_AFTER_MS) {
            log.debug("Queue websocket went silent; reconnecting");
            ws.abort();
            return;
        }
        armWatchdog(gen);
    }

    /** Clears per-connection state and fails requests that can no longer be answered. */
    private void forgetConnection(String failureCode) {
        hello = null;
        openQueues.clear();
        List<CompletableFuture<Result>> waiting = new ArrayList<>(pending.values());
        pending.clear();
        waiting.forEach(f -> f.complete(localFailure(failureCode)));
        List<CompletableFuture<TicketSendResult>> sends = new ArrayList<>(pendingSends.values());
        pendingSends.clear();
        sends.forEach(f -> f.complete(localSendFailure(null, failureCode)));
        List<CompletableFuture<TicketHistory>> histories = new ArrayList<>(pendingHistory.values());
        pendingHistory.clear();
        histories.forEach(f -> f.complete(new TicketHistory("local", QueueMessages.VERSION, null, false, failureCode, null, null, List.of())));
    }

    // === receiving ===

    private final class SocketListener implements WebSocket.Listener {
        private final int gen;
        private final String token;
        private final StringBuilder frameBuffer = new StringBuilder();

        SocketListener(int gen, String token) {
            this.gen = gen;
            this.token = token;
        }

        private boolean current() {
            return gen == generation.get();
        }

        @Override
        public void onOpen(WebSocket ws) {
            lastFrameAt = System.currentTimeMillis();
            // Own the socket before asking for the first frame: the hello can arrive before
            // buildAsync completes, and requests sent while handling it (the ticket history
            // after a reconnect) would otherwise find no socket and fail as offline.
            if (current()) socket.set(ws);
            ws.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket ws, CharSequence data, boolean last) {
            lastFrameAt = System.currentTimeMillis();
            try {
                frameBuffer.append(data);
                if (!last) {
                    ws.request(1);
                    return null;
                }
                String message = frameBuffer.toString();
                frameBuffer.setLength(0);
                // Ask for the next frame first, so a slow handler cannot stall the stream.
                ws.request(1);
                if (current()) handleMessage(message);
            } catch (Throwable t) {
                // Anything thrown out of a listener callback kills the connection; a malformed
                // frame must never do that.
                log.warn("Error handling queue websocket frame: " + t);
            }
            return null;
        }

        @Override
        public CompletableFuture<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            ws.request(1); // the protocol has no binary frames
            return null;
        }

        @Override
        public CompletableFuture<?> onPing(WebSocket ws, ByteBuffer message) {
            lastFrameAt = System.currentTimeMillis();
            ws.request(1);
            return null; // the JDK answers with a pong
        }

        @Override
        public CompletableFuture<?> onPong(WebSocket ws, ByteBuffer message) {
            lastFrameAt = System.currentTimeMillis();
            ws.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (!current()) return null; // an old socket closing after a restart
            socket.compareAndSet(ws, null);
            forgetConnection("disconnected");
            if (System.currentTimeMillis() - connectedAt >= QueueTuning.HEALTHY_AFTER_MS) attempt.set(0);

            switch (QueueTuning.actionForClose(statusCode)) {
                case TOKEN_REVOKED -> {
                    // A revoke aimed at a token we no longer use (we just re-linked) is not ours.
                    if (token != null && Objects.equals(token, settings.token())) {
                        log.warn("Queue link was revoked by the server (" + statusCode + ")");
                        running.set(false);
                        listener.onTokenRevoked(token);
                    } else {
                        scheduleReconnect(0L);
                    }
                }
                case STOP_REPLACED -> {
                    log.info("Queue websocket replaced by another game instance; not reconnecting");
                    running.set(false);
                    listener.onStopped(StopReason.REPLACED);
                }
                case STOP_OUTDATED -> {
                    log.warn("The queue server does not support this mod version (" + reason + ")");
                    running.set(false);
                    listener.onStopped(StopReason.OUTDATED);
                }
                case RECONNECT -> {
                    if (running.get()) log.info("Queue websocket disconnected (" + statusCode + "); reconnecting");
                    scheduleReconnect(QueueTuning.minDelayForClose(statusCode));
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (!current()) return;
            socket.compareAndSet(ws, null);
            forgetConnection("disconnected");
            log.debug("Queue websocket error: " + error);
            scheduleReconnect(0L);
        }
    }

    private void handleMessage(String json) {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null || !root.has("type")) return;

        switch (root.get("type").getAsString()) {
            case "hello" -> {
                Hello h = gson.fromJson(json, Hello.class);
                if (h == null) return;
                hello = h;
                openQueues.clear();
                if (h.queues() != null) h.queues().forEach(q -> { if (q != null && q.queueId() != null) openQueues.put(q.queueId(), q); });
                syncTicketWithHello(h);
                listener.onHello(h);
                if (h.queues() != null) h.queues().forEach(q -> { if (q != null) listener.onQueueOpen(q, true); });
            }
            case "queue_open" -> {
                QueueOpen open = gson.fromJson(json, QueueOpen.class);
                if (open == null || open.queue() == null || open.queue().queueId() == null) return;
                openQueues.put(open.queue().queueId(), open.queue());
                listener.onQueueOpen(open.queue(), false);
            }
            case "queue_close" -> {
                QueueClose close = gson.fromJson(json, QueueClose.class);
                if (close == null) return;
                if (close.queueId() != null) openQueues.remove(close.queueId());
                listener.onQueueClose(close);
            }
            case "next_up" -> {
                NextUp next = gson.fromJson(json, NextUp.class);
                if (next == null) return;
                if (next.queueId() != null) updateMe(next.queueId(), new QueueMessages.Me("waiting", 1));
                listener.onNextUp(next);
            }
            case "your_turn" -> {
                YourTurn turn = gson.fromJson(json, YourTurn.class);
                if (turn == null) return;
                if (turn.queueId() != null) updateMe(turn.queueId(), new QueueMessages.Me("testing", null));
                listener.onYourTurn(turn);
            }
            case "removed" -> {
                Removed removed = gson.fromJson(json, Removed.class);
                if (removed == null) return;
                if (removed.queueId() != null) updateMe(removed.queueId(), new QueueMessages.Me("none", null));
                listener.onRemoved(removed);
            }
            case "join_result", "leave_result" -> {
                Result result = gson.fromJson(json, Result.class);
                if (result == null) return;
                if (result.ok() && result.queue() != null && result.queue().queueId() != null) {
                    openQueues.put(result.queue().queueId(), result.queue());
                }
                CompletableFuture<Result> future = result.requestId() == null ? null : pending.remove(result.requestId());
                if (future != null) future.complete(result);
            }
            case "queue_update" -> {
                QueueUpdate update = gson.fromJson(json, QueueUpdate.class);
                if (update == null || update.queue() == null || update.queue().queueId() == null) return;
                openQueues.put(update.queue().queueId(), update.queue());
                listener.onQueueUpdate(update.queue());
            }
            case "ticket_open" -> {
                TicketOpen open = gson.fromJson(json, TicketOpen.class);
                if (open == null || open.ticket() == null || open.ticket().ticketId() == null) return;
                tickets.open(open.ticket());
                listener.onTicketOpen(open.ticket());
            }
            case "ticket_message" -> {
                TicketChat chat = gson.fromJson(json, TicketChat.class);
                if (chat == null || chat.message() == null) return;
                if (tickets.add(chat.ticketId(), chat.message())) listener.onTicketMessage(chat);
            }
            case "ticket_close" -> {
                TicketClose close = gson.fromJson(json, TicketClose.class);
                if (close == null || close.ticketId() == null) return;
                Ticket ticket = tickets.snapshot().ticket();
                tickets.close(close);
                if (ticket != null && close.ticketId().equals(ticket.ticketId()) && ticket.queueId() != null) {
                    // the bot's queue_update says the same within seconds; do not wait for it
                    Queue q = openQueues.get(ticket.queueId());
                    if (q != null && q.me() != null && q.me().isTesting()) updateMe(ticket.queueId(), new QueueMessages.Me("none", null));
                }
                listener.onTicketClose(close);
            }
            case "ticket_history" -> {
                TicketHistory history = gson.fromJson(json, TicketHistory.class);
                if (history == null) return;
                if (history.ok()) tickets.history(history.ticketId(), history.messages());
                CompletableFuture<TicketHistory> future = history.requestId() == null ? null : pendingHistory.remove(history.requestId());
                if (future != null) future.complete(history);
            }
            case "ticket_send_result" -> {
                TicketSendResult result = gson.fromJson(json, TicketSendResult.class);
                if (result == null) return;
                CompletableFuture<TicketSendResult> future = result.requestId() == null ? null : pendingSends.remove(result.requestId());
                if (future != null) future.complete(result);
            }
            case "error" -> log.debug("Queue server reported: " + json);
            default -> {
                // Unknown types are ignored on purpose so the server can add message kinds.
            }
        }
    }

    /**
     * A hello after a (re)connect: a ticket it names is (still) open and its history is fetched;
     * a ticket the log thinks open but the hello does not name ended while we were away.
     */
    private void syncTicketWithHello(Hello h) {
        TicketLog.Snapshot before = tickets.snapshot();
        if (h.ticket() != null && h.ticket().ticketId() != null) {
            tickets.open(h.ticket());
            String id = h.ticket().ticketId();
            ticketHistory(id).thenAccept(r -> {
                if (r.ok()) log.debug("Ticket " + id + ": " + (r.messages() == null ? 0 : r.messages().size()) + " messages from history");
            });
        } else if (h.linked() && before.active()) {
            tickets.close(new TicketClose("local", QueueMessages.VERSION, before.ticket().ticketId(), "closed", null, null));
        }
    }

    private void updateMe(String queueId, QueueMessages.Me me) {
        openQueues.computeIfPresent(queueId, (id, q) -> new Queue(q.queueId(), q.sessionId(), q.region(), q.mode(),
                q.modeName(), q.openedAt(), q.testers(), q.waiting(), q.canJoin(), me));
    }
}
