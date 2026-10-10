package com.kevin.tiertagger.queue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kevin.tiertagger.queue.QueueMessages.Hello;
import com.kevin.tiertagger.queue.QueueMessages.NextUp;
import com.kevin.tiertagger.queue.QueueMessages.Queue;
import com.kevin.tiertagger.queue.QueueMessages.QueueClose;
import com.kevin.tiertagger.queue.QueueMessages.Removed;
import com.kevin.tiertagger.queue.QueueMessages.Result;
import com.kevin.tiertagger.queue.QueueMessages.Ticket;
import com.kevin.tiertagger.queue.QueueMessages.TicketChat;
import com.kevin.tiertagger.queue.QueueMessages.TicketClose;
import com.kevin.tiertagger.queue.QueueMessages.TicketHistory;
import com.kevin.tiertagger.queue.QueueMessages.TicketSendResult;
import com.kevin.tiertagger.queue.QueueMessages.YourTurn;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Drives the real {@link QueueClient} against a running gateway - the mock in
 * {@code 111/mock-server} or the bot itself - through every message and close code of
 * protocol v2. No game needed.
 *
 * <p>Run the mock ({@code node 111/mock-server/mock-queue-server.js}), then
 * {@code ./gradlew queueProtocolCheck}. Against another gateway:
 * {@code ./gradlew queueProtocolCheck -PqueueUrl=ws://127.0.0.1:3056/mod/ws -PdevUrl=http://127.0.0.1:3057/dev}.
 * The gateway must expose the mock's {@code /dev} control API (open, close, pull, code...).</p>
 *
 * <p>Takes about 40 seconds and must end with "53 checks passed": the server enforces a 2 second gap between requests, and the
 * 1008 step waits for a real reconnect. The ticket chat steps need bot V37 (or the mock).</p>
 */
public final class QueueProtocolCheck {
    private QueueProtocolCheck() {}

    private static final long WAIT_MS = 15_000L;
    private static int checks = 0;

    private static HttpClient http;
    private static final Gson GSON = new Gson();
    private static String wsUrl;
    private static String devUrl;
    private static final List<String> LOG = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        wsUrl = args.length > 0 ? args[0] : "ws://127.0.0.1:3056/mod/ws";
        devUrl = args.length > 1 ? args[1] : "http://127.0.0.1:3057/dev";
        String linkUrl = wsUrl.replaceFirst("^ws", "http").replaceFirst("/ws(\\?.*)?$", "/link");
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).proxy(HttpClient.Builder.NO_PROXY).build();
        System.out.println("QueueProtocolCheck against " + wsUrl);

        // --- anonymous: alerts only ---
        Recorder anonEvents = new Recorder();
        Settings anonSettings = new Settings(wsUrl, null);
        QueueClient anon = client(anonSettings, anonEvents);
        anon.start();
        Hello hello = anonEvents.next("hello", Hello.class);
        check(hello.v() == 2 && !hello.linked() && !hello.canJoin(), "anonymous hello: v2, not linked, no join");

        dev("open", Map.of("mode", "sword", "region", "EU"));
        Queue opened = anonEvents.next("open", Queue.class);
        check("EU:sword".equals(opened.queueId()) && "sword".equals(opened.mode()), "queue_open names the queue EU:sword");
        check(opened.sessionId() != null && opened.canJoin() == null && opened.me() == null, "anonymous queue_open has a session, no canJoin, no me");
        check(anon.openQueues().size() == 1, "the client tracks the open queue");

        Result notLinked = anon.join("EU:sword").get(WAIT_MS, TimeUnit.MILLISECONDS);
        check(!notLinked.ok() && "not_linked".equals(notLinked.code()), "anonymous join is refused with not_linked, got " + notLinked.code());

        // --- link ---
        HttpResponse<String> bad = post(linkUrl, linkBody("AAAAAA", "MockPlayer"), null);
        check(bad.statusCode() == 400 && bad.body().contains("/tiertagger link"), "a wrong code answers 400 and names the Discord command");
        String code = dev("code", Map.of()).get("code").getAsString();
        // bot V39: the body names the game account; mods before VTL Core 3.3.0 send only the code
        HttpResponse<String> oldBody = post(linkUrl, "{\"code\":\"" + code + "\"}", null);
        check(oldBody.statusCode() == 400 && oldBody.body().contains("update_required"), "an old mod (code only) is told to update");
        HttpResponse<String> otherName = post(linkUrl, linkBody(code, "Someone"), null);
        check(otherName.statusCode() == 400 && otherName.body().contains("name_mismatch"), "a game logged in as another name is refused (name_mismatch)");
        HttpResponse<String> linked = post(linkUrl, linkBody(code.toLowerCase(), "MockPlayer"), null);
        check(linked.statusCode() == 200, "the same code still links after those refusals (lower case accepted), got HTTP " + linked.statusCode());
        QueueMessages.LinkResponse link = GSON.fromJson(linked.body(), QueueMessages.LinkResponse.class);
        check(link.token() != null && link.token().length() >= 40 && link.discordName() != null, "link answer has a long token and the Discord name");
        check(post(linkUrl, linkBody(code, "MockPlayer"), null).statusCode() == 400, "a code works only once");

        // --- linked: reconnect with the token ---
        anonSettings.token = link.token();
        QueueClient me = anon;
        Recorder events = anonEvents;
        events.clear();
        me.restart();
        hello = events.next("hello", Hello.class);
        check(hello.linked() && hello.canJoin() && link.discordName().equals(hello.discordName()), "linked hello: join allowed, same Discord name");
        Queue fromHello = events.next("openFromHello", Queue.class);
        check(Boolean.TRUE.equals(fromHello.canJoin()) && fromHello.me() != null && "none".equals(fromHello.me().status()), "open queues come with the hello: canJoin, me=none");

        Result joined = me.join("EU:sword").get(WAIT_MS, TimeUnit.MILLISECONDS);
        check(joined.ok() && "joined".equals(joined.code()) && joined.message() != null, "join from the game works: " + joined.message());
        check(joined.queue() != null && joined.queue().me().isWaiting() && joined.queue().me().position() == 1, "join answer carries position 1");
        NextUp next = events.next("next_up", NextUp.class);
        check("EU:sword".equals(next.queueId()) && next.message() != null, "next_up arrives for position 1");

        Thread.sleep(2_100);
        Result left = me.leave("EU:sword").get(WAIT_MS, TimeUnit.MILLISECONDS);
        check(left.ok() && "left".equals(left.code()), "leave from the game works: " + left.message());

        Thread.sleep(2_100);
        check(me.join("EU:sword").get(WAIT_MS, TimeUnit.MILLISECONDS).ok(), "join again works");
        Result tooFast = me.join("EU:sword").get(WAIT_MS, TimeUnit.MILLISECONDS);
        check(!tooFast.ok() && "rate_limited".equals(tooFast.code()), "a second request within 2s is rate limited, got " + tooFast.code());

        dev("pull", Map.of("mode", "sword", "region", "EU"));
        YourTurn turn = events.next("your_turn", YourTurn.class);
        check(turn.ticketUrl() != null && turn.ticketUrl().startsWith("https://discord.com/channels/"), "your_turn carries the Discord ticket link");
        check(QueueMessages.discordTicketUri(turn.ticketUrl()) != null, "the ticket link passes the mod's Discord-only filter");
        check(me.openQueues().stream().anyMatch(q -> q.me() != null && q.me().isTesting()), "after your_turn the queue shows me as testing");

        Thread.sleep(2_100);
        Result inTicket = me.leave("EU:sword").get(WAIT_MS, TimeUnit.MILLISECONDS);
        check(!inTicket.ok() && "in_ticket".equals(inTicket.code()), "leave during a test is refused with in_ticket");

        // --- ticket chat and live queue state (bot V37) ---
        Ticket ticket = events.next("ticket_open", Ticket.class);
        check(ticket.ticketId() != null && "EU:sword".equals(ticket.queueId()) && QueueMessages.discordTicketUri(ticket.ticketUrl()) != null,
                "ticket_open names the ticket, its queue and a Discord link");
        check(me.ticketLog().snapshot().active() && ticket.ticketId().equals(me.ticketLog().snapshot().ticket().ticketId()),
                "the client's ticket log follows the open ticket");

        dev("say", Map.of("text", "Which server? play.example.net"));
        TicketChat said = events.next("ticket_message", TicketChat.class, c -> "tester".equals(c.message().author().role()));
        check(said.message().text().contains("play.example.net") && !said.message().author().self() && ticket.ticketId().equals(said.ticketId()),
                "a tester message on Discord reaches the game");
        check(me.ticketLog().snapshot().unread() >= 1 && count(me, "Which server? play.example.net") == 1, "it is in the log and counts as unread");

        TicketSendResult sent = me.sendTicket(ticket.ticketId(), "joining now").get(WAIT_MS, TimeUnit.MILLISECONDS);
        check(sent.ok() && sent.sent() != null && sent.sent().fromGame() && sent.sent().author().self(), "a message typed in the game is posted in the ticket");
        JsonObject onDiscord = dev("ticket", Map.of());
        check(onDiscord.get("last").getAsString().endsWith("(Minecraft): joining now") && onDiscord.get("mentionsBlocked").getAsBoolean(),
                "Discord shows it under the player's name, with pings off");
        Thread.sleep(800); // an echo from Discord would have arrived by now
        check(count(me, "joining now") == 1, "the game shows its own message once (no echo from Discord)");

        LOG.clear();
        me.restart();
        Hello again = events.next("hello", Hello.class);
        check(again.ticket() != null && ticket.ticketId().equals(again.ticket().ticketId()), "after a reconnect the hello names the open ticket");
        await(() -> LOG.stream().anyMatch(l -> l.contains("from history")), "after a reconnect the client fetches the ticket history");
        check(count(me, "joining now") == 1 && count(me, "Which server? play.example.net") == 1, "the history adds no duplicates");

        Thread.sleep(2_100);
        TicketHistory history = me.ticketHistory(ticket.ticketId()).get(WAIT_MS, TimeUnit.MILLISECONDS);
        check(history.ok() && history.messages().stream().anyMatch(m -> m.fromGame() && "joining now".equals(m.text()) && m.author().self()),
                "ticket history has the chat, the game message as the player's own");

        events.clear();
        dev("add", Map.of("mode", "sword", "region", "EU"));
        Queue update = events.next("queue_update", Queue.class);
        check("EU:sword".equals(update.queueId()) && update.waiting() == 1 && update.me() != null && update.me().isTesting(),
                "queue_update arrives when someone joins; I am still testing");

        dev("result", Map.of("tier", "HT3", "previous", "LT3"));
        TicketClose done = events.next("ticket_close", TicketClose.class);
        check("result".equals(done.reason()) && done.result() != null && "HT3".equals(done.result().tier()) && done.message().contains("HT3"),
                "/result closes the ticket with the tier");
        check("LT3".equals(done.result().previousTier()), "bot V38: the result says the tier before the test");
        TicketLog.Snapshot after = me.ticketLog().snapshot();
        check(after.closed() && !after.active() && count(me, "joining now") == 1, "the log keeps the transcript, closed");
        check(me.openQueues().stream().noneMatch(q -> q.me() != null && q.me().isTesting()), "after the close I am no longer testing");
        check(!me.sendTicket(ticket.ticketId(), "thanks").get(WAIT_MS, TimeUnit.MILLISECONDS).ok(), "nothing can be sent to a closed ticket");

        dev("close", Map.of("mode", "sword", "region", "EU"));
        QueueClose close = events.next("close", QueueClose.class);
        check("EU:sword".equals(close.queueId()) && fromHello.sessionId().equals(close.sessionId()), "queue_close names the same session");
        check(me.openQueues().isEmpty(), "the closed queue is forgotten");

        // --- a second game window with the same account replaces the first (4002) ---
        Recorder secondEvents = new Recorder();
        QueueClient second = client(new Settings(wsUrl, link.token()), secondEvents);
        second.start();
        secondEvents.next("hello", Hello.class);
        check(events.next("stopped", QueueClient.StopReason.class) == QueueClient.StopReason.REPLACED, "the first window stops with REPLACED (4002)");

        // --- unlink on Discord: 4001, then the token is refused at the handshake ---
        dev("unlink", Map.of());
        check(link.token().equals(secondEvents.next("revoked", String.class)), "unlink on Discord revokes this token (4001)");
        Recorder thirdEvents = new Recorder();
        QueueClient third = client(new Settings(wsUrl, link.token()), thirdEvents);
        third.start();
        check(link.token().equals(thirdEvents.next("revoked", String.class)), "a revoked token is refused at the handshake (401)");

        // --- unlink from the game: POST /mod/unlink with the token, the socket gets 4001 ---
        String unlinkUrl = linkUrl.replaceFirst("/link$", "/unlink");
        String code2 = dev("code", Map.of()).get("code").getAsString();
        HttpResponse<String> relinked = post(linkUrl, linkBody(code2, "MockPlayer"), null);
        QueueMessages.LinkResponse link2 = GSON.fromJson(relinked.body(), QueueMessages.LinkResponse.class);
        Recorder gameEvents = new Recorder();
        QueueClient game = client(new Settings(wsUrl, link2.token()), gameEvents);
        game.start();
        check(gameEvents.next("hello", Hello.class).linked(), "linking again after an unlink works");
        check(post(unlinkUrl, "", link2.token()).statusCode() == 200, "POST /mod/unlink with the token answers 200");
        check(link2.token().equals(gameEvents.next("revoked", String.class)), "the game's own socket is closed with 4001 after unlinking");
        check(post(unlinkUrl, "", link2.token()).statusCode() == 401, "the unlinked token cannot unlink again (401)");

        // --- an old protocol version is told to update (4003) ---
        Recorder oldEvents = new Recorder();
        QueueClient old = client(new Settings(wsUrl + "?v=1", null), oldEvents);
        old.start();
        check(oldEvents.next("stopped", QueueClient.StopReason.class) == QueueClient.StopReason.OUTDATED, "protocol v1 is stopped with OUTDATED (4003)");

        // --- 1008 from a proxy or the server is not a reason to drop the link ---
        Recorder dropEvents = new Recorder();
        QueueClient dropped = client(new Settings(wsUrl, null), dropEvents);
        dropped.start();
        dropEvents.next("hello", Hello.class);
        dev("drop", Map.of("code", 1008));
        dropEvents.next("hello", Hello.class); // reconnected within the normal backoff
        check(dropEvents.none("revoked") && dropEvents.none("stopped"), "1008 reconnects and keeps the token");

        // --- a missing endpoint is retried after 10 minutes, not every few seconds ---
        LOG.clear();
        QueueClient missing = client(new Settings(wsUrl.replace("/mod/ws", "/mod/missing"), null), new Recorder());
        missing.start();
        long delay = awaitReconnectDelay();
        check(delay >= QueueTuning.MISSING_ENDPOINT_BACKOFF_MS, "HTTP 404 waits at least 10 minutes, got " + delay + "ms");

        // --- bot V39: a linked game is signed out when its network changes ---
        String code3 = dev("code", Map.of()).get("code").getAsString();
        QueueMessages.LinkResponse link3 = GSON.fromJson(post(linkUrl, linkBody(code3, "MockPlayer"), null).body(), QueueMessages.LinkResponse.class);
        dev("relink-ip", Map.of());
        Recorder movedEvents = new Recorder();
        QueueClient moved = client(new Settings(wsUrl, link3.token()), movedEvents);
        moved.start();
        Hello signedOut = movedEvents.next("hello", Hello.class);
        check(!signedOut.linked() && "ip_changed".equals(signedOut.relink()) && signedOut.relinkMessage() != null,
                "a game on another network is signed out with relink=ip_changed");

        for (QueueClient c : List.of(me, second, third, game, old, dropped, missing, moved)) c.shutdown();
        System.out.println("QueueProtocolCheck: " + checks + " checks passed");
        System.exit(0);
    }

    // === plumbing ===

    /** How often {@code text} is in the client's ticket log. */
    private static long count(QueueClient client, String text) {
        return client.ticketLog().snapshot().entries().stream().filter(e -> text.equals(e.message().text())).count();
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long until = System.currentTimeMillis() + WAIT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) throw new AssertionError("FAILED: " + message);
            Thread.sleep(50);
        }
        check(true, message);
    }

    private static QueueClient client(Settings settings, Recorder recorder) {
        return new QueueClient(http, GSON, settings, recorder, new QueueClient.Log() {
            @Override
            public void info(String message) {
                LOG.add(message);
            }

            @Override
            public void warn(String message) {
                LOG.add(message);
            }

            @Override
            public void debug(String message) {
                LOG.add(message);
            }
        });
    }

    private static long awaitReconnectDelay() throws InterruptedException {
        long until = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < until) {
            for (String line : LOG) {
                var m = java.util.regex.Pattern.compile("reconnecting in (\\d+)ms").matcher(line);
                if (m.find()) return Long.parseLong(m.group(1));
            }
            Thread.sleep(50);
        }
        throw new AssertionError("FAILED: no reconnect was scheduled");
    }

    private static JsonObject dev(String action, Map<String, Object> body) throws Exception {
        HttpResponse<String> r = post(devUrl + "/" + action, GSON.toJson(body), null);
        if (r.statusCode() != 200) throw new AssertionError("FAILED: /dev/" + action + " answered " + r.statusCode() + " " + r.body());
        return GSON.fromJson(r.body(), JsonObject.class);
    }

    private static HttpResponse<String> post(String url, String json, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-VTL-Client", "QueueProtocolCheck")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + message);
        System.out.println("  ok  " + message);
    }

    private static final class Settings implements QueueClient.Settings {
        final String url;
        volatile String token;

        Settings(String url, String token) {
            this.url = url;
            this.token = token;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String url() {
            return url;
        }

        @Override
        public String token() {
            return token;
        }

        @Override
        public String clientName() {
            return "QueueProtocolCheck";
        }

        @Override
        public String playerId() {
            return PLAYER.toString();
        }
    }

    /** The game account every client of this check runs (the mock links it as MockPlayer). */
    private static final UUID PLAYER = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    /** A bot V39 link body for {@code name}, without a Mojang proof (the mock checks names only). */
    private static String linkBody(String code, String name) {
        return LinkRequest.body(code, name, PLAYER, null);
    }

    /** Collects listener events as (kind, payload) pairs, in arrival order. */
    private static final class Recorder implements QueueClient.Listener {
        private final BlockingQueue<Object[]> events = new LinkedBlockingQueue<>();
        private final List<String> seen = new CopyOnWriteArrayList<>();

        private void add(String kind, Object payload) {
            seen.add(kind);
            events.add(new Object[]{kind, payload});
        }

        void clear() {
            events.clear();
            seen.clear();
        }

        boolean none(String kind) {
            return !seen.contains(kind);
        }

        /** The next event of {@code kind}, skipping others; fails after {@link #WAIT_MS}. */
        <T> T next(String kind, Class<T> type) throws InterruptedException {
            return next(kind, type, x -> true);
        }

        /** The next event of {@code kind} that {@code match} accepts, skipping others. */
        <T> T next(String kind, Class<T> type, Predicate<T> match) throws InterruptedException {
            long until = System.currentTimeMillis() + WAIT_MS;
            while (true) {
                long left = until - System.currentTimeMillis();
                Object[] e = left > 0 ? events.poll(left, TimeUnit.MILLISECONDS) : null;
                if (e == null) throw new AssertionError("FAILED: no \"" + kind + "\" event within " + WAIT_MS + "ms (saw " + seen + ")");
                if (kind.equals(e[0]) && match.test(type.cast(e[1]))) return type.cast(e[1]);
            }
        }

        @Override
        public void onHello(Hello hello) {
            add("hello", hello);
        }

        @Override
        public void onQueueOpen(Queue queue, boolean fromHello) {
            add(fromHello ? "openFromHello" : "open", queue);
        }

        @Override
        public void onQueueClose(QueueClose close) {
            add("close", close);
        }

        @Override
        public void onNextUp(NextUp message) {
            add("next_up", message);
        }

        @Override
        public void onYourTurn(YourTurn message) {
            add("your_turn", message);
        }

        @Override
        public void onRemoved(Removed message) {
            add("removed", message);
        }

        @Override
        public void onTokenRevoked(String token) {
            add("revoked", token);
        }

        @Override
        public void onQueueUpdate(Queue queue) {
            add("queue_update", queue);
        }

        @Override
        public void onTicketOpen(Ticket ticket) {
            add("ticket_open", ticket);
        }

        @Override
        public void onTicketMessage(TicketChat chat) {
            add("ticket_message", chat);
        }

        @Override
        public void onTicketClose(TicketClose close) {
            add("ticket_close", close);
        }

        @Override
        public void onStopped(QueueClient.StopReason reason) {
            add("stopped", reason);
        }
    }
}
