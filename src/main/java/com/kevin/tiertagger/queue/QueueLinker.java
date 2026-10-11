package com.kevin.tiertagger.queue;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.queue.QueueMessages.LinkResponse;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Links this install to a Discord account: the player gets a one-time code from the bot
 * ({@code /vtl link} on Discord) and the mod exchanges it for a long-lived token.
 *
 * <p>The token is the only credential the mod holds. It is created at runtime, kept in its own
 * file ({@link QueueTokenStore}) and never shown or logged.</p>
 */
public final class QueueLinker {
    private QueueLinker() {}

    /** True while a link request is in flight (the server then closes the old socket). */
    private static volatile boolean linking;

    public static boolean isLinking() {
        return linking;
    }

    /** Redeems {@code code}; on success stores the token and reconnects as the linked player. */
    public static void link(String code) {
        String url = TierTagger.getManager().getConfig().getQueueLinkUrl();
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("X-VTL-Client", QueueWebSocket.clientHeader());
        } catch (IllegalArgumentException | NullPointerException e) {
            TierTagger.getLogger().warn("Invalid queue link URL: {}", url);
            fail(Component.translatable("tiertagger.queue.link.failed"));
            return;
        }

        // Bot V39 links only the Minecraft account the player verified on Discord: the body says
        // which account this game runs, with a Mojang session proof when it is premium.
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        String name = user.getName();
        UUID uuid = user.getProfileId();

        linking = true;
        // joinServer is a blocking call to Mojang; keep it off the render thread.
        CompletableFuture.supplyAsync(() -> proveSession(mc, user, code))
                .thenCompose(nonce -> TierTagger.getClient().sendAsync(
                        request.POST(HttpRequest.BodyPublishers.ofString(LinkRequest.body(code, name, uuid, nonce))).build(),
                        HttpResponse.BodyHandlers.ofString()))
                .thenAccept(response -> handleLinkResponse(url, response.statusCode(), response.body(),
                        response.headers().firstValue("location").orElse(null)))
                .exceptionally(t -> {
                    TierTagger.getLogger().warn("Queue link request to {} failed: {}", url, t.toString());
                    fail(Component.translatable("tiertagger.queue.link.failed"));
                    return null;
                })
                .whenComplete((v, t) -> linking = false);
    }

    /**
     * Proves the game's Mojang session for this link, the same call a client makes to join an
     * online-mode server. Returns the nonce the proof is made for, or null without a premium
     * session (cracked launcher) or when Mojang refuses. The access token only goes to Mojang.
     */
    private static String proveSession(Minecraft mc, User user, String code) {
        String nonce = LinkRequest.newNonce();
        try {
            mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), LinkRequest.serverId(code, nonce));
            return nonce;
        } catch (Exception e) {
            TierTagger.getLogger().debug("Linking without a Mojang session proof: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static void handleLinkResponse(String url, int status, String body, String redirect) {
        LinkResponse parsed = null;
        try {
            parsed = TierTagger.GSON.fromJson(body, LinkResponse.class);
        } catch (RuntimeException ignored) {
            // not JSON - handled below
        }

        if (status == 200 && parsed != null && parsed.token() != null && !parsed.token().isBlank()) {
            String token = parsed.token();
            String name = parsed.discordName();
            QueueTokenStore.set(token);
            TierTagger.getQueue().restart();
            QueueWebSocket.runOnClientThread(() -> QueueNotifier.result(true, name == null
                    ? Component.translatable("tiertagger.queue.link.ok")
                    : Component.translatable("tiertagger.queue.link.okAs", name)));
            return;
        }

        // The body never contains a token here, so it is safe to log a snippet of it.
        TierTagger.getLogger().warn("Queue link POST {} answered HTTP {}{}: {}", url, status,
                redirect == null ? "" : " (redirects to " + redirect + ")",
                body == null || body.isEmpty() ? "<empty>" : body.length() > 200 ? body.substring(0, 200) : body);
        if (parsed != null && parsed.message() != null && !parsed.message().isBlank()) {
            fail(Component.literal(parsed.message()));
        } else if (status == 400) {
            fail(Component.translatable("tiertagger.queue.link.invalid"));
        } else {
            fail(Component.translatable("tiertagger.queue.link.failed"));
        }
    }

    /**
     * Signs this install out: tells the server to revoke the token (best effort), forgets it
     * locally and reconnects anonymously, so queue alerts keep working.
     */
    public static void unlink() {
        String token = QueueTokenStore.get();
        QueueTokenStore.set(null);
        TierTagger.getQueue().restart();
        QueueWebSocket.runOnClientThread(() -> QueueNotifier.info(Component.translatable("tiertagger.queue.unlink.ok")));
        if (token == null) return;

        String url = unlinkUrl(TierTagger.getManager().getConfig().getQueueLinkUrl());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("X-VTL-Client", QueueWebSocket.clientHeader())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            TierTagger.getClient().sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(r -> TierTagger.getLogger().info("Queue unlink answered HTTP {}", r.statusCode()))
                    .exceptionally(t -> {
                        TierTagger.getLogger().warn("Queue unlink request failed: {}", t.toString());
                        return null;
                    });
        } catch (IllegalArgumentException e) {
            TierTagger.getLogger().warn("Invalid queue unlink URL: {}", url);
        }
    }

    /** {@code .../mod/link} -> {@code .../mod/unlink}. */
    static String unlinkUrl(String linkUrl) {
        if (linkUrl == null) return null;
        return linkUrl.endsWith("/link") ? linkUrl.substring(0, linkUrl.length() - "/link".length()) + "/unlink" : linkUrl + "/unlink";
    }

    private static void fail(Component message) {
        QueueWebSocket.runOnClientThread(() -> QueueNotifier.result(false, message));
    }
}
