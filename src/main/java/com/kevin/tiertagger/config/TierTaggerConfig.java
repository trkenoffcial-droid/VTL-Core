package com.kevin.tiertagger.config;

import com.google.gson.internal.LinkedTreeMap;
import com.kevin.tiertagger.TierCache;
import com.kevin.tiertagger.model.GameMode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TierTaggerConfig implements Serializable {
    private boolean enabled = true;
    private String gameMode = "vanilla";
    private boolean showRetired = true;
    private HighestMode highestMode = HighestMode.NOT_FOUND;
    private boolean showIcons = true;
    private boolean playerList = true;
    private int retiredColor = 0xa2d6ff;
    // note: this is a GSON internal class. this *might* break in the future
    private LinkedTreeMap<String, Integer> tierColors = defaultColors();

    // === queue notifications ===

    private boolean queueNotify = true;
    private QueueRegion queueRegion = QueueRegion.ALL;
    /** {@code "all"} or one of the ids in {@link GameMode#VIET_STATIC_GAMEMODES}. */
    private String queueMode = "all";
    private boolean queueSound = true;
    /** When linked: only alert for queues this player can join (region and waitlist role). */
    private boolean queueOnlyJoinable = true;
    private String queueUrl = DEFAULT_QUEUE_URL;
    private String queueLinkUrl = DEFAULT_QUEUE_LINK_URL;

    /**
     * <p>Legacy: builds before protocol v2 kept the link token here. It now lives in its own file
     * (QueueTokenStore) so sharing a config folder never shares the token; this field is only
     * read once to move an old token over, then cleared.</p>
     */
    private String queueToken = null;

    // === VTL Hub and HUD ===

    private boolean hudEnabled = true;
    private HudCorner hudCorner = HudCorner.TOP_RIGHT;
    /** Volume of the queue sounds, 0-100. */
    private int alertVolume = 100;
    /** The bundled "Block-Accurate: 3D Item Models" pack (teyokuma); off unless the player turns it on. */
    private boolean threeDItems = false;
    /** Moving sparks, drifting crystals and pulsing light in the hub; off for slow PCs. */
    private boolean hubEffects = true;

    // === internal stuff ===

    /**
     * <p>the field was renamed to do a little trolling and force it setting to the default value in players' config</p>
     * <p>previous name(s): {@code baseUrl}</p>
     */
    private String apiUrl = "https://www.tierslist.net/api";

    // www: tierslist.net answers with a redirect to www.tierslist.net, and neither the link
    // request nor the WebSocket handshake follows redirects.
    public static final String DEFAULT_QUEUE_URL = "wss://www.tierslist.net/mod/ws?v=2";
    public static final String DEFAULT_QUEUE_LINK_URL = "https://www.tierslist.net/mod/link";
    /** Defaults of earlier builds (v1, and 2.5.0 without www); a config still holding them gets the new ones. */
    public static final List<String> OLD_QUEUE_URLS = List.of("wss://tierslist.net/ws/queue", "wss://tierslist.net/mod/ws?v=2", "wss://tierslist.net/mod/ws");
    public static final List<String> OLD_QUEUE_LINK_URLS = List.of("https://tierslist.net/link", "https://tierslist.net/mod/link");

    /**
     * The link endpoint on the same server as a queue socket URL:
     * {@code wss://host/mod/ws?v=2} -> {@code https://host/mod/link}.
     */
    public static String linkUrlFor(String queueUrl) {
        if (queueUrl == null) return DEFAULT_QUEUE_LINK_URL;
        String url = queueUrl.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
        int query = url.indexOf('?');
        if (query >= 0) url = url.substring(0, query);
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url.endsWith("/ws") ? url.substring(0, url.length() - 3) + "/link" : url + "/link";
    }

    /**
     * Puts back a default for every value a hand-edited or imported config left empty or out of
     * range, so the rest of the mod never sees a null setting.
     */
    public void normalize() {
        if (gameMode == null) gameMode = "vanilla";
        if (highestMode == null) highestMode = HighestMode.NOT_FOUND;
        if (tierColors == null) tierColors = defaultColors();
        if (queueRegion == null) queueRegion = QueueRegion.ALL;
        if (queueMode == null) queueMode = "all";
        if (hudCorner == null) hudCorner = HudCorner.TOP_RIGHT;
        alertVolume = Math.max(0, Math.min(100, alertVolume));
        if (apiUrl == null) apiUrl = "https://www.tierslist.net/api";
        migrateQueueUrls();
    }

    /** Moves a config off earlier default URLs. Returns true when something changed. */
    public boolean migrateQueueUrls() {
        boolean changed = false;
        if (this.queueUrl == null || OLD_QUEUE_URLS.contains(this.queueUrl)) {
            this.queueUrl = DEFAULT_QUEUE_URL;
            changed = true;
        }
        if (this.queueLinkUrl == null || OLD_QUEUE_LINK_URLS.contains(this.queueLinkUrl)) {
            this.queueLinkUrl = DEFAULT_QUEUE_LINK_URL;
            changed = true;
        }
        return changed;
    }

    public GameMode getGameMode() {
        Optional<GameMode> opt = TierCache.findMode(this.gameMode);
        if (opt.isPresent()) {
            return opt.get();
        } else {
            GameMode first = TierCache.getGamemodes().getFirst();
            if (!first.isNone()) this.gameMode = first.id();
            return first;
        }
    }

    private static LinkedTreeMap<String, Integer> defaultColors() {
        LinkedTreeMap<String, Integer> colors = new LinkedTreeMap<>();
        colors.put("HT1", 0xe8ba3a);
        colors.put("LT1", 0xd5b355);
        colors.put("HT2", 0xc4d3e7);
        colors.put("LT2", 0xa0a7b2);
        colors.put("HT3", 0xf89f5a);
        colors.put("LT3", 0xc67b42);
        colors.put("HT4", 0x81749a);
        colors.put("LT4", 0x655b79);
        colors.put("HT5", 0x8f82a8);
        colors.put("LT5", 0x655b79);

        return colors;
    }

    /**
     * <p>The queue-eligible modes, pinned to {@link GameMode#VIET_STATIC_GAMEMODES} rather than to
     * {@code TierCache.getGamemodes()}: the latter returns whatever tierlist the player has
     * selected (MCTiers ids when MCTiers is active), while the bot only ever emits these ids.</p>
     */
    public static final List<String> QUEUE_MODES = Stream.concat(
            Stream.of("all"),
            GameMode.VIET_STATIC_GAMEMODES.stream().map(GameMode::id)
    ).toList();

    /** True when {@code value} is "all" or matches the configured value. */
    public static boolean matchesFilter(String configured, String value) {
        return "all".equalsIgnoreCase(configured) || configured.equalsIgnoreCase(value);
    }

    public boolean matchesQueueFilter(String region, String mode) {
        return matchesFilter(this.queueRegion.getName(), region) && matchesFilter(this.queueMode, mode);
    }

    @Getter
    @AllArgsConstructor
    public enum QueueRegion implements Translatable {
        ALL("all", "tiertagger.queue.region.all"),
        AS_ME("AS_ME", "tiertagger.queue.region.as_me"),
        AU_OC("AU_OC", "tiertagger.queue.region.au_oc"),
        NA_SA("NA_SA", "tiertagger.queue.region.na_sa"),
        EU("EU", "tiertagger.queue.region.eu"),
        ;

        private final String name;
        private final String translationKey;
    }

    public enum HudCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** An option value with a translation key for its label. */
    public interface Translatable {
        String getTranslationKey();
    }

    @Getter
    @AllArgsConstructor
    public enum HighestMode implements Translatable {
        NEVER("never", "tiertagger.highest.never"),
        NOT_FOUND("not_found", "tiertagger.highest.not_found"),
        ALWAYS("always", "tiertagger.highest.always"),
        ;

        private final String name;
        private final String translationKey;
    }
}