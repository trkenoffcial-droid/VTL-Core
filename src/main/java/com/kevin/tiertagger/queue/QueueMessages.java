package com.kevin.tiertagger.queue;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wire format for the queue WebSocket, protocol v2 (see MOD-PROTOCOL-v2.md).
 *
 * <p>Every message is a JSON text frame carrying {@code type} and {@code v}. Unknown
 * {@code type} values are ignored rather than treated as errors, so the backend can add
 * message kinds without breaking older mod builds.</p>
 *
 * <p>The bot runs one queue per region and kit, so several can be open at once:
 * {@code queueId} ({@code "EU:sword"}) names a queue, {@code sessionId} names one opening of
 * it (a reopen mints a new one) and is what alerts are deduplicated on.</p>
 *
 * <p>Deliberately free of Minecraft types so {@code QueueProtocolCheck} can run headless.</p>
 */
public final class QueueMessages {
    private QueueMessages() {}

    /** Protocol version this build speaks. */
    public static final int VERSION = 2;

    /** The caller's own place in a queue; only sent to linked connections. */
    public record Me(String status, Integer position) {
        public boolean isWaiting() {
            return "waiting".equals(status);
        }

        public boolean isTesting() {
            return "testing".equals(status);
        }
    }

    /**
     * One open queue.
     *
     * @param queueId   {@code "<region>:<mode>"}, stable for the queue
     * @param sessionId unique per opening; a reopen produces a new one
     * @param region    one of {@code AS_ME}, {@code AU_OC}, {@code NA_SA}, {@code EU}
     * @param mode      mode id, matching {@code GameMode.VIET_STATIC_GAMEMODES}
     * @param modeName  display name, without Discord emoji
     * @param openedAt  epoch milliseconds, may be null
     * @param testers   display names of the testers running it; may be empty
     * @param waiting   players waiting when the message was sent
     * @param canJoin   for a linked connection: whether this player may join (region and
     *                  waitlist role); null on an anonymous connection
     * @param me        for a linked connection: the player's status in this queue
     */
    public record Queue(String queueId, String sessionId, String region, String mode, String modeName,
                        Long openedAt, List<String> testers, int waiting, Boolean canJoin, Me me) {
        public String displayName() {
            return modeName != null && !modeName.isBlank() ? modeName : mode;
        }
    }

    /**
     * First message after every (re)connect: what this connection may do, plus every open queue.
     * {@code ticket} is the linked player's open ticket, if any (bot V37+). {@code relink} is set
     * when the bot signed this game out on connect (bot V39: {@code ip_changed},
     * {@code account_changed} or {@code ign_changed}), with {@code relinkMessage} for the player.
     */
    public record Hello(String type, int v, List<String> features, boolean linked, String discordName,
                       Boolean verified, String region, List<Queue> queues, Ticket ticket,
                       String relink, String relinkMessage) {
        public boolean canJoin() {
            return features != null && features.contains("join");
        }
    }

    public record QueueOpen(String type, int v, Queue queue) {
    }

    public record QueueClose(String type, int v, String queueId, String sessionId) {
    }

    /** The player became first in line. */
    public record NextUp(String type, int v, String queueId, String message) {
    }

    /** A tester pulled the player: the test happens in a Discord ticket. */
    public record YourTurn(String type, int v, String queueId, String tester, String ticketUrl, String message) {
    }

    /** The player was taken out of a queue by something other than this mod. */
    public record Removed(String type, int v, String queueId, String reason, String message) {
    }

    /** Answer to join_queue / leave_queue. {@code message} is meant to be shown as-is. */
    public record Result(String type, int v, String requestId, boolean ok, String code, String message,
                         Long retryAfterMs, Queue queue) {
    }

    public record ServerError(String type, int v, String code, String message) {
    }

    /** A queue's line, testers or the player's place changed (bot V37+). */
    public record QueueUpdate(String type, int v, Queue queue) {
    }

    // --- ticket chat (bot V37+) ---

    /**
     * The player's Discord test ticket.
     *
     * @param ticketId  the Discord channel id
     * @param ticketUrl link to the channel on Discord
     */
    public record Ticket(String ticketId, String queueId, String mode, String modeName, String region,
                         List<String> testers, Long openedAt, String ticketUrl) {
        public String displayName() {
            return modeName != null && !modeName.isBlank() ? modeName : mode;
        }
    }

    /** @param role {@code player}, {@code tester}, {@code staff} or {@code bot}; {@code self} is the player */
    public record Author(String name, String role, boolean self) {
    }

    public record Attachment(String name, String url) {
    }

    /** One message of the ticket; {@code fromGame} when the player typed it in Minecraft. */
    public record TicketMessage(String id, Long at, Author author, String text, boolean fromGame, List<Attachment> attachments) {
        public long time() {
            return at == null ? 0L : at;
        }
    }

    public record TicketOpen(String type, int v, Ticket ticket) {
    }

    public record TicketChat(String type, int v, String ticketId, TicketMessage message) {
    }

    /** {@code previousTier}: the tier before the test, {@code "NONE"} for none; missing from bots before V38. */
    public record TicketResult(String tier, String mode, String previousTier) {
    }

    /** @param reason {@code result}, {@code closed} or {@code skipped}; {@code message} is shown as-is */
    public record TicketClose(String type, int v, String ticketId, String reason, String message, TicketResult result) {
    }

    /** Answer to ticket_history: the last messages of the ticket, oldest first. */
    public record TicketHistory(String type, int v, String requestId, boolean ok, String code, String message,
                                String ticketId, List<TicketMessage> messages) {
    }

    /** Answer to ticket_send; {@code sent} is the message as posted on Discord. */
    public record TicketSendResult(String type, int v, String requestId, boolean ok, String code, String message,
                                   Long retryAfterMs, String ticketId, TicketMessage sent) {
    }

    /** Response body of {@code POST /mod/link}. */
    public record LinkResponse(String token, String discordId, String discordName, String error, String message) {
    }

    /** Hosts a "your turn" ticket link may point to. Anything else is shown without a link. */
    private static final Set<String> DISCORD_HOSTS = Set.of("discord.com", "ptb.discord.com", "canary.discord.com", "discordapp.com");

    /** The ticket link as a URI when it is https on Discord itself, else null (never clickable). */
    public static URI discordTicketUri(String url) {
        if (url == null) return null;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return null;
            return DISCORD_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Human-readable region label. */
    public static String regionLabel(String region) {
        if (region == null) return "?";
        return switch (region) {
            case "AS_ME" -> "AS/ME";
            case "AU_OC" -> "AU/OC";
            case "NA_SA" -> "NA/SA";
            default -> region;
        };
    }
}
