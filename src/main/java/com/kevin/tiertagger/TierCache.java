package com.kevin.tiertagger;

import com.kevin.tiertagger.model.GameMode;
import com.kevin.tiertagger.model.PlayerInfo;
import com.kevin.tiertagger.model.TierList;
import com.kevin.tiertagger.viettierlist.VietTierListApi;
import com.kevin.tiertagger.viettierlist.VietTierListConverter;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class TierCache {
    private static final List<GameMode> GAMEMODES = new ArrayList<>();

    /** UUID-keyed cache for tierlists that expose a /v2/profile/{uuid}/... API (MCTiers, SubTiers). */
    private static final Map<UUID, Optional<Map<String, PlayerInfo.Ranking>>> TIERS = new ConcurrentHashMap<>();

    /** Name-keyed cache for tierlists that only expose a search-by-username API (Viet tierlist). */
    private static final Map<String, Optional<Map<String, PlayerInfo.Ranking>>> TIERS_BY_NAME = new ConcurrentHashMap<>();

    public static void init() {
        try {
            GAMEMODES.clear();
            GAMEMODES.addAll(GameMode.fetchGamemodes(TierTagger.getClient()).get());
            TierTagger.getLogger().info("Found {} tierlists: {}", GAMEMODES.size(), GAMEMODES.stream().map(GameMode::id).toList());
        } catch (ExecutionException e) {
            TierTagger.getLogger().error("Failed to load gamemodes!", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static List<GameMode> getGamemodes() {
        if (GAMEMODES.isEmpty()) {
            return Collections.singletonList(GameMode.NONE);
        } else {
            return GAMEMODES;
        }
    }

    /**
     * Look up a player's tier rankings, dispatching to the correct cache/API
     * based on the active tierlist.
     */
    public static Optional<Map<String, PlayerInfo.Ranking>> getPlayerRankings(UUID uuid, String name) {
        if (isNameLookupActive()) {
            if (name == null) return Optional.empty();
            return getPlayerRankingsByName(name);
        }
        return getPlayerRankings(uuid);
    }

    /** UUID-keyed lookup (MCTiers / SubTiers path). */
    public static Optional<Map<String, PlayerInfo.Ranking>> getPlayerRankings(UUID uuid) {
        return TIERS.computeIfAbsent(uuid, u -> {
            if (uuid.version() == 4) {
                PlayerInfo.getRankings(TierTagger.getClient(), uuid).thenAccept(info -> TIERS.put(uuid, Optional.ofNullable(info)));
            }

            return Optional.empty();
        });
    }

    /**
     * Name-keyed lookup (Viet tierlist path). The first call for a name hits
     * the network; subsequent calls return the cached value.
     */
    public static Optional<Map<String, PlayerInfo.Ranking>> getPlayerRankingsByName(String name) {
        final String key = name.toLowerCase(Locale.ROOT);
        return TIERS_BY_NAME.computeIfAbsent(key, n -> {
            VietTierListApi.search(TierTagger.getClient(), name)
                    .thenApply(resp -> resp == null
                            ? Optional.<Map<String, PlayerInfo.Ranking>>empty()
                            : Optional.of(VietTierListConverter.toRankings(resp)))
                    .thenAccept(opt -> TIERS_BY_NAME.put(key, opt))
                    .exceptionally(t -> {
                        TierTagger.getLogger().warn("Viet tierlist lookup failed for {}", name, t);
                        TIERS_BY_NAME.put(key, Optional.empty());
                        return null;
                    });
            return Optional.empty();
        });
    }

    public static CompletableFuture<PlayerInfo> searchPlayer(String query) {
        if (isNameLookupActive()) {
            return VietTierListApi.search(TierTagger.getClient(), query)
                    .thenApply(resp -> {
                        PlayerInfo info = VietTierListConverter.toPlayerInfo(resp);
                        if (info != null && info.rankings() != null) {
                            TIERS_BY_NAME.put(query.toLowerCase(Locale.ROOT), Optional.of(info.rankings()));
                        }
                        return info;
                    });
        }
        return PlayerInfo.search(TierTagger.getClient(), query).thenApply(p -> {
            if (p == null || p.uuid() == null) {
                return null;
            }
            UUID uuid = parseUUID(p.uuid());
            if (p.rankings() != null) {
                TIERS.put(uuid, Optional.of(p.rankings()));
            }
            return p;
        });
    }

    public static void clearCache() {
        TIERS.clear();
        TIERS_BY_NAME.clear();
    }

    public static GameMode findNextMode(GameMode current) {
        if (GAMEMODES.isEmpty()) {
            return GameMode.NONE;
        } else {
            return GAMEMODES.get((GAMEMODES.indexOf(current) + 1) % GAMEMODES.size());
        }
    }

    public static Optional<GameMode> findMode(String id) {
        return GAMEMODES.stream().filter(m -> m.id().equalsIgnoreCase(id)).findFirst();
    }

    public static GameMode findModeOrUgly(String id) {
        return findMode(id).orElseGet(() -> new GameMode(id, id));
    }

    public static boolean isNameLookupActive() {
        return TierList.findByUrl(TierTagger.getManager().getConfig().getApiUrl())
                .map(TierList::usesNameLookup)
                .orElse(false);
    }

    private static UUID parseUUID(String uuid) {
        try {
            return UUID.fromString(uuid);
        } catch (Exception e) {
            long mostSignificant = Long.parseUnsignedLong(uuid.substring(0, 16), 16);
            long leastSignificant = Long.parseUnsignedLong(uuid.substring(16), 16);
            return new UUID(mostSignificant, leastSignificant);
        }
    }

    private TierCache() {
    }
}
