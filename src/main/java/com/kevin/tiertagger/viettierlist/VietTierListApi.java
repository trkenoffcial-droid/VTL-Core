package com.kevin.tiertagger.viettierlist;

import com.kevin.tiertagger.TierTagger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the Viet tierlist (tierslist.net) API.
 * <p>The only known endpoint is {@code /api/search-player?username={name}}, which
 * returns the full profile (including all per-gamemode tiers) in a single call.
 */
public final class VietTierListApi {
    private VietTierListApi() {}

    /**
     * Search for a player by Minecraft username.
     *
     * @param client HTTP client
     * @param name   Minecraft username (case-insensitive on the server side)
     * @return a future with the response, or {@code null} if the request failed
     */
    public static CompletableFuture<VietTierListResponse> search(HttpClient client, String name) {
        String base = TierTagger.getManager().getConfig().getApiUrl();
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        String endpoint = base + "/search-player?username=" + encoded;

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).GET().build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(s -> TierTagger.GSON.fromJson(s, VietTierListResponse.class))
                .handle((resp, t) -> {
                    if (t != null) {
                        TierTagger.getLogger().warn("Error searching Viet tierlist for {}", name, t);
                        return null;
                    }
                    return resp;
                });
    }
}
