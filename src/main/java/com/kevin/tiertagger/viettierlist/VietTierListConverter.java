package com.kevin.tiertagger.viettierlist;

import com.kevin.tiertagger.model.PlayerInfo;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Translates the raw {@link VietTierListResponse} into the mod's existing
 * {@link PlayerInfo} model so the rest of the mod can stay tierlist-agnostic.
 */
public final class VietTierListConverter {
    private VietTierListConverter() {}

    /**
     * Convert a Viet tierlist response into the mod's standard PlayerInfo record.
     * Returns {@code null} if the response or its inner player is null.
     */
    public static PlayerInfo toPlayerInfo(VietTierListResponse response) {
        if (response == null || response.player() == null) {
            return null;
        }
        VietTierListResponse.Player p = response.player();

        return new PlayerInfo(
                uuidForUserId(p.userId()),
                p.playerName(),
                toRankings(response),
                p.region(),
                p.points(),
                /* overall = */ p.rank(),
                /* badges   = */ Collections.emptyList(),
                /* combatMaster = */ false
        );
    }

    /**
     * Convert the {@code allTiers} array into a {@code Map<modeId, Ranking>}
     * keyed by mode id, as expected by the mod's existing rendering code.
     */
    public static Map<String, PlayerInfo.Ranking> toRankings(VietTierListResponse response) {
        if (response == null || response.player() == null || response.player().allTiers() == null) {
            return Collections.emptyMap();
        }

        Map<String, PlayerInfo.Ranking> map = new LinkedHashMap<>();
        List<VietTierListResponse.TierEntry> entries = response.player().allTiers();

        for (VietTierListResponse.TierEntry entry : entries) {
            if (entry == null || entry.mode() == null) continue;
            PlayerInfo.Ranking ranking = toRanking(entry);
            if (ranking != null) {
                map.put(entry.mode(), ranking);
            }
        }
        return map;
    }

    /**
     * Convert a single {@link VietTierListResponse.TierEntry} into a
     * {@link PlayerInfo.Ranking}. Returns {@code null} if the tier is null/blank
     * (the API uses {@code tier: null} to mean "unranked in this mode").
     */
    public static PlayerInfo.Ranking toRanking(VietTierListResponse.TierEntry entry) {
        if (entry.tier() == null || entry.tier().isBlank()) {
            return null;
        }

        // The tier string is e.g. "HT4" or "LT4". Parse the prefix and number.
        String t = entry.tier().trim().toUpperCase();
        int pos;
        if (t.startsWith("HT")) {
            pos = 0;
            t = t.substring(2);
        } else if (t.startsWith("LT")) {
            pos = 1;
            t = t.substring(2);
        } else {
            // unknown format; treat as low
            pos = 1;
        }

        int tierNum;
        try {
            tierNum = Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }

        return new PlayerInfo.Ranking(
                tierNum,
                pos,
                /* peakTier = */ null,
                /* peakPos  = */ null,
                /* attained = */ 0L,
                /* retired  = */ false
        );
    }

    /**
     * Viet tierlist has no Minecraft UUID — it tracks players by an internal
     * Discord-style user id. We synthesize a stable v4 UUID from a hash of the
     * userId so it can be used as a cache key.
     */
    public static String uuidForUserId(String userId) {
        if (userId == null) return new UUID(0L, 0L).toString();
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(userId.getBytes());
            // Set version (4) and variant (10xx) bits
            hash[6] = (byte) ((hash[6] & 0x0F) | 0x40);
            hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++)  msb = (msb << 8) | (hash[i]  & 0xFF);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (hash[i]  & 0xFF);
            return new UUID(msb, lsb).toString();
        } catch (NoSuchAlgorithmException e) {
            return new UUID(0L, userId.hashCode()).toString();
        }
    }
}
