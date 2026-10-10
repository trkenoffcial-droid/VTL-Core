package com.kevin.tiertagger.queue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.UUID;

/**
 * Runnable self-check for the link request VTL Core sends (bot V39): the Mojang proof id must
 * match the bot's {@code linkServerId}, and the body must carry the game profile. Pure Java,
 * no game: part of {@code ./gradlew hubStateCheck}.
 */
public final class LinkRequestCheck {
    private LinkRequestCheck() {}

    private static int checks = 0;

    /** node -e "require('crypto').createHash('sha1').update('vtl-link:ABC234:AAAAAAAAAAAAAAAAAAAAAA').digest('hex')" */
    private static final String SERVER_ID_VECTOR = "61d9ed47a2be2d04356b8e1dc1e3fedad2ce18c3";
    private static final UUID PLAYER = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    public static void main(String[] args) {
        serverIdMatchesTheBot();
        bodyCarriesTheProfileAndProof();
        bodyWithoutProof();
        noncesAreFreshAndUrlSafe();
        System.out.println("LinkRequestCheck: " + checks + " checks passed");
    }

    private static void serverIdMatchesTheBot() {
        check(SERVER_ID_VECTOR.equals(LinkRequest.serverId("ABC234", "AAAAAAAAAAAAAAAAAAAAAA")), "serverId equals the bot's SHA-1 vector");
        check(SERVER_ID_VECTOR.equals(LinkRequest.serverId("abc-234", "AAAAAAAAAAAAAAAAAAAAAA")), "the code is normalized like the bot does (case, dashes)");
        check(LinkRequest.serverId("ABC234", "B").matches("[0-9a-f]{40}"), "serverId is 40 lowercase hex");
    }

    private static void bodyCarriesTheProfileAndProof() {
        JsonObject body = JsonParser.parseString(LinkRequest.body("abc-234", "PlayerOne", PLAYER, "AAAAAAAAAAAAAAAAAAAAAA")).getAsJsonObject();
        check("ABC234".equals(body.get("code").getAsString()), "code upper-cased without dashes");
        JsonObject player = body.getAsJsonObject("player");
        check("PlayerOne".equals(player.get("name").getAsString()), "player name");
        check("01234567-89ab-cdef-0123-456789abcdef".equals(player.get("uuid").getAsString()), "player uuid, dashed lowercase");
        check("AAAAAAAAAAAAAAAAAAAAAA".equals(body.getAsJsonObject("proof").get("nonce").getAsString()), "proof nonce");
    }

    private static void bodyWithoutProof() {
        JsonObject body = JsonParser.parseString(LinkRequest.body("ABC234", "CrackOne", PLAYER, null)).getAsJsonObject();
        check(body.has("proof") && body.get("proof").isJsonNull(), "no proof is sent as proof: null");
    }

    private static void noncesAreFreshAndUrlSafe() {
        String a = LinkRequest.newNonce();
        String b = LinkRequest.newNonce();
        check(a.matches("[A-Za-z0-9_-]{22}"), "nonce is 22 base64url characters (16 bytes)");
        check(!a.equals(b), "every nonce is new");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("LinkRequestCheck failed: " + message);
    }
}
