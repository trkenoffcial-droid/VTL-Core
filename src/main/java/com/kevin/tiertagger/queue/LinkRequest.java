package com.kevin.tiertagger.queue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * The body of {@code POST /mod/link} (bot V39). The game says which Minecraft account it runs
 * and, for a premium session, proves it: before posting, the game calls Mojang's joinServer
 * with {@link #serverId}, and the bot asks Mojang's hasJoined for the same id. The access
 * token only ever goes to Mojang. Pure Java (Gson only), so it is checked without the game.
 */
public final class LinkRequest {
    private LinkRequest() {}

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A link code the way the bot reads it: upper case, no spaces or dashes. */
    public static String normalizeCode(String code) {
        return code == null ? "" : code.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
    }

    /** SHA-1 hex of {@code vtl-link:<code>:<nonce>}, the same as the bot's {@code linkServerId}. */
    public static String serverId(String code, String nonce) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(("vtl-link:" + normalizeCode(code) + ":" + nonce).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is missing from this Java runtime", e);
        }
    }

    /** 16 random bytes, base64url without padding (22 characters). */
    public static String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The JSON body; {@code nonce} is null when the session could not be proven (cracked). */
    public static String body(String code, String name, UUID uuid, String nonce) {
        JsonObject player = new JsonObject();
        player.addProperty("name", name);
        player.addProperty("uuid", uuid == null ? null : uuid.toString().toLowerCase(Locale.ROOT));
        JsonObject body = new JsonObject();
        body.addProperty("code", normalizeCode(code));
        body.add("player", player);
        if (nonce == null) {
            body.add("proof", JsonNull.INSTANCE);
        } else {
            JsonObject proof = new JsonObject();
            proof.addProperty("nonce", nonce);
            body.add("proof", proof);
        }
        return GSON.toJson(body);
    }
}
