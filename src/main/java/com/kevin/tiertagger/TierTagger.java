package com.kevin.tiertagger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kevin.tiertagger.config.TierTaggerConfig;
import com.kevin.tiertagger.config.VtlConfig;
import com.kevin.tiertagger.hub.HubTab;
import com.kevin.tiertagger.hub.VtlHubScreen;
import com.kevin.tiertagger.menu.MenuButtons;
import com.kevin.tiertagger.queue.QueueNotifier;
import com.kevin.tiertagger.queue.QueueWebSocket;
import com.kevin.tiertagger.model.GameMode;
import com.kevin.tiertagger.model.PlayerInfo;
import com.kevin.tiertagger.queue.QueueCommands;
import com.kevin.tiertagger.queue.QueueTokenStore;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import lombok.Getter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class TierTagger implements ModInitializer {
    /** Legacy command root and name of the pre-3.0 config file; still accepted. */
    public static final String MOD_ID = "tiertagger";
    /** Main command root. */
    public static final String COMMAND = "vtl";

    public static final Gson GSON = new GsonBuilder().create();

    @Getter
    private static final VtlConfig manager = new VtlConfig(FabricLoader.getInstance().getConfigDir());
    @Getter
    private static final Logger logger = LoggerFactory.getLogger(TierTagger.class);
    /**
     * Shared HTTP client. Loopback addresses bypass the system proxy: dev/test servers
     * (e.g. the mock queue server on 127.0.0.1) live there, and a corporate/VPN proxy in
     * front of them answers with errors like HTTP 405 instead of forwarding.
     */
    @Getter
    private static final HttpClient client = HttpClient.newBuilder()
            // HTTP/1.1 only: the default HTTP/2 client sends cleartext requests with
            // `Upgrade: h2c` headers, which makes Node's shared HTTP+WS server route them
            // to the websocket upgrader instead of the request handler (HTTP 405).
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(new ProxySelector() {
                private final ProxySelector system = ProxySelector.getDefault();

                @Override
                public List<Proxy> select(URI uri) {
                    String host = uri == null ? null : uri.getHost();
                    if (isLoopback(host)) {
                        return List.of(Proxy.NO_PROXY);
                    }
                    if (system == null) return List.of(Proxy.NO_PROXY);
                    try {
                        return system.select(uri);
                    } catch (IllegalArgumentException e) {
                        return List.of(Proxy.NO_PROXY);
                    }
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                    if (system != null) system.connectFailed(uri, address, failure);
                }
            })
            .build();

    /** Loopback must never go through a proxy (dev/test servers live there). */
    private static boolean isLoopback(String host) {
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("::1")
                || host.endsWith(".localhost"));
    }

    @Getter
    private static final QueueWebSocket queue = new QueueWebSocket();

    // === version checker stuff ===
    // The update check asked Modrinth about the original TierTagger project, so it would have
    // told VTL players to replace this build with one that has no VTL support. It stays off
    // until VTL has its own release channel; the title screen prompt then never shows.
    @Getter
    private static final Version latestVersion = null;

    @Override
    public void onInitialize() {
        TierCache.init();

        // Literal children win over the "player" argument for an exact token match, so a player
        // literally named "link", "unlink", "join", "leave" or "queues" can no longer be looked up
        // by name. Acceptable: those names are far rarer than the subcommands are useful.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            dispatcher.register(commandTree(COMMAND));
            dispatcher.register(commandTree(MOD_ID));
        });

        openHubKey = registerKey(new KeyMapping("vtl.key.openHub", GLFW.GLFW_KEY_R, KEY_CATEGORY), mc -> {
            // straight to the chat while a ticket is open or has unread messages
            if (mc.screen != null) return;
            var result = QueueWebSocket.takePendingResult();
            if (result != null) {
                QueueWebSocket.showResult(result);
                return;
            }
            var ticket = queue.ticketLog().snapshot();
            VtlHubScreen.open(ticket.active() || ticket.unread() > 0 ? HubTab.Id.TICKET : HubTab.Id.PLAY);
        });
        quickJoinKey = registerKey(new KeyMapping("vtl.key.quickJoin", GLFW.GLFW_KEY_J, KEY_CATEGORY), mc ->
                QueueWebSocket.QUICK_JOIN.take(mc.screen != null).ifPresent(q -> {
                    QueueNotifier.info(Component.translatable("tiertagger.queue.join.sending", q.displayName(),
                            com.kevin.tiertagger.queue.QueueMessages.regionLabel(q.region())));
                    queue.join(q.queueId()).thenAccept(result -> QueueWebSocket.runOnClientThread(
                            () -> QueueNotifier.result(result.ok(), QueueWebSocket.describe(result))));
                }));
        MenuButtons.register();
        com.kevin.tiertagger.hub.ItemPack.register();
        com.kevin.tiertagger.hud.QueueHud.register();
        com.kevin.tiertagger.dev.DevShots.register();
        com.kevin.tiertagger.dev.Trailer.register();

        registerKey(new KeyMapping("tiertagger.keybind.gamemode", GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY),
                mc -> {
                    GameMode next = TierCache.findNextMode(manager.getConfig().getGameMode());
                    manager.getConfig().setGameMode(next.id());

                    if (mc.player != null) {
                        Component message = Component.literal("Displayed gamemode: ").append(next.asStyled(false));
                        mc.player.displayClientMessage(message, true);
                    }
                });

        // Builds before protocol v2 used other default URLs and kept the link token in the
        // config file; move both over once.
        TierTaggerConfig config = manager.getConfig();
        boolean migrated = config.migrateQueueUrls();
        migrated |= QueueTokenStore.migrateFromConfig();
        if (migrated) manager.saveConfig();

        queue.start();
    }

    /** Controls category "VTL" for every key of the mod. */
    private static KeyMapping openHubKey;
    private static KeyMapping quickJoinKey;

    public static KeyMapping openHubKey() {
        return openHubKey;
    }

    public static KeyMapping quickJoinKey() {
        return quickJoinKey;
    }

    public static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("vtl", "main"));

    /** Names of the players on the current server, for tab completion. */
    private static final SuggestionProvider<FabricClientCommandSource> PLAYER_NAMES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder);

    /** The same command tree under {@code /vtl} and {@code /tiertagger}. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> commandTree(String root) {
        LiteralArgumentBuilder<FabricClientCommandSource> tree = QueueCommands.addTo(literal(root))
                .then(literal("menu").executes(ctx -> openHub()))
                .then(argument("player", StringArgumentType.word()).suggests(PLAYER_NAMES)
                        .executes(TierTagger::displayTierInfo));
        // /vtl on its own opens the hub; /tiertagger on its own keeps listing the commands.
        return COMMAND.equals(root) ? tree.executes(ctx -> openHub()) : tree;
    }

    private static int openHub() {
        VtlHubScreen.open(HubTab.Id.PLAY);
        return 1;
    }

    /** Registers a key in Controls and runs {@code action} on the client thread each time it is pressed. */
    public static KeyMapping registerKey(KeyMapping key, Consumer<Minecraft> action) {
        KeyBindingHelper.registerKeyBinding(key);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (key.consumeClick()) action.accept(mc);
        });
        return key;
    }

    /**
     * Append a tier prefix to a player's name component. Dispatches by UUID
     * (default) or by name (Viet tierlist).
     */
    public static Component appendTier(UUID uuid, String name, Component text) {
        Optional<PlayerInfo.NamedRanking> tier;
        if (TierCache.isNameLookupActive()) {
            tier = getPlayerTierByName(name);
        } else {
            tier = getPlayerTier(uuid);
        }
        return appendTierInternal(tier, text);
    }

    /** Backwards-compatible UUID-only entry point used by existing call sites. */
    public static Component appendTier(UUID uuid, Component text) {
        return appendTierInternal(getPlayerTier(uuid), text);
    }

    private static Component appendTierInternal(Optional<PlayerInfo.NamedRanking> entry, Component text) {
        MutableComponent following = entry
                .map(e -> {
                    Component tierText = getRankingText(e.ranking(), false);

                    if (manager.getConfig().isShowIcons() && e.mode() != null && e.mode().icon().isPresent()) {
                        return Component.literal(e.mode().icon().get().toString()).append(tierText);
                    } else {
                        return tierText.copy();
                    }
                })
                .orElse(null);

        if (following != null) {
            following.append(Component.literal(" | ").withStyle(ChatFormatting.GRAY));
            return following.append(text);
        }

        return text;
    }

    public static Optional<PlayerInfo.NamedRanking> getPlayerTier(UUID uuid) {
        GameMode mode = manager.getConfig().getGameMode();

        return TierCache.getPlayerRankings(uuid)
                .map(rankings -> {
                    PlayerInfo.Ranking ranking = rankings.get(mode.id());
                    Optional<PlayerInfo.NamedRanking> highest = PlayerInfo.getHighestRanking(rankings);
                    TierTaggerConfig.HighestMode highestMode = manager.getConfig().getHighestMode();

                    if (ranking == null) {
                        if (highestMode != TierTaggerConfig.HighestMode.NEVER && highest.isPresent()) {
                            return highest.get();
                        } else {
                            return null;
                        }
                    } else {
                        if (highestMode == TierTaggerConfig.HighestMode.ALWAYS && highest.isPresent()) {
                            return highest.get();
                        } else {
                            return ranking.asNamed(mode);
                        }
                    }
                });
    }

    /**
     * Name-keyed variant of {@link #getPlayerTier(UUID)} for tierlists
     * (Viet tierlist) that don't expose a UUID endpoint.
     */
    public static Optional<PlayerInfo.NamedRanking> getPlayerTierByName(String name) {
        if (name == null) return Optional.empty();
        GameMode mode = manager.getConfig().getGameMode();

        return TierCache.getPlayerRankingsByName(name)
                .map(rankings -> {
                    PlayerInfo.Ranking ranking = rankings.get(mode.id());
                    Optional<PlayerInfo.NamedRanking> highest = PlayerInfo.getHighestRanking(rankings);
                    TierTaggerConfig.HighestMode highestMode = manager.getConfig().getHighestMode();

                    if (ranking == null) {
                        if (highestMode != TierTaggerConfig.HighestMode.NEVER && highest.isPresent()) {
                            return highest.get();
                        } else {
                            return null;
                        }
                    } else {
                        if (highestMode == TierTaggerConfig.HighestMode.ALWAYS && highest.isPresent()) {
                            return highest.get();
                        } else {
                            return ranking.asNamed(mode);
                        }
                    }
                });
    }

    private static MutableComponent getTierText(int tier, int pos, boolean retired) {
        StringBuilder text = new StringBuilder();
        if (retired) text.append("R");
        text.append(pos == 0 ? "H" : "L").append("T").append(tier);

        int color = TierTagger.getTierColor(text.toString());
        return Component.literal(text.toString()).withStyle(s -> s.withColor(color));
    }

    public static Component getRankingText(PlayerInfo.Ranking ranking, boolean showPeak) {
        if (ranking.retired() && ranking.peakTier() != null && ranking.peakPos() != null) {
            return getTierText(ranking.peakTier(), ranking.peakPos(), true);
        } else {
            MutableComponent tierText = getTierText(ranking.tier(), ranking.pos(), false);

            if (showPeak && ranking.comparablePeak() < ranking.comparableTier()) {
                // warning caused by potential NPE by unboxing of peak{Tier,Pos} which CANNOT happen, see impl of comparablePeak
                // noinspection DataFlowIssue
                tierText.append(Component.literal(" (peak: ").withStyle(s -> s.withColor(ChatFormatting.GRAY)))
                        .append(getTierText(ranking.peakTier(), ranking.peakPos(), false))
                        .append(Component.literal(")").withStyle(s -> s.withColor(ChatFormatting.GRAY)));
            }

            return tierText;
        }
    }

    private static int displayTierInfo(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "player");

        if (TierCache.isNameLookupActive()) {
            // Viet tierlist: only has a name-based search endpoint, so always go through TierCache.searchPlayer
            ctx.getSource().sendFeedback(Component.literal("[VTL Core] Searching..."));
            TierCache.searchPlayer(name)
                    .thenAccept(p -> deliverSearchResult(ctx, name, p))
                    .exceptionally(t -> {
                        deliverSearchError(ctx, name);
                        return null;
                    });
            return 0;
        }

        Optional<Map<String, PlayerInfo.Ranking>> rankings = ctx.getSource().getWorld().players().stream()
                .filter(p -> p.getScoreboardName().equalsIgnoreCase(name) || p.getStringUUID().equalsIgnoreCase(name))
                .findFirst()
                .map(Entity::getUUID)
                .flatMap(TierCache::getPlayerRankings);

        if (rankings.isPresent()) {
            ctx.getSource().sendFeedback(printPlayerInfo(name, rankings.get()));
        } else {
            ctx.getSource().sendFeedback(Component.literal("[VTL Core] Searching..."));
            TierCache.searchPlayer(name)
                    .thenAccept(p -> deliverSearchResult(ctx, name, p))
                    .exceptionally(t -> {
                        deliverSearchError(ctx, name);
                        return null;
                    });
        }

        return 0;
    }

    private static void deliverSearchResult(CommandContext<FabricClientCommandSource> ctx, String name, PlayerInfo player) {
        Minecraft.getInstance().execute(() -> {
            if (player == null || player.rankings() == null) {
                ctx.getSource().sendError(Component.literal("Could not find player " + name));
                return;
            }
            ctx.getSource().sendFeedback(printPlayerInfo(name, player.rankings()));
        });
    }

    private static void deliverSearchError(CommandContext<FabricClientCommandSource> ctx, String name) {
        Minecraft.getInstance().execute(() -> ctx.getSource().sendError(Component.literal("Could not find player " + name)));
    }

    private static Component printPlayerInfo(String name, Map<String, PlayerInfo.Ranking> rankings) {
        if (rankings == null || rankings.isEmpty()) {
            return Component.literal(name + " does not have any tiers.");
        } else {
            MutableComponent text = Component.empty().append("=== Rankings for " + name + " ===");

            rankings.forEach((m, r) -> {
                if (m == null) return;
                GameMode mode = TierCache.findModeOrUgly(m);
                Component tierText = getRankingText(r, true);
                text.append(Component.literal("\n").append(mode.asStyled(true)).append(": ").append(tierText));
            });

            return text;
        }
    }

    public static int getTierColor(String tier) {
        if (tier.startsWith("R")) {
            return manager.getConfig().getRetiredColor();
        } else {
            return manager.getConfig().getTierColors().getOrDefault(tier, 0xD3D3D3);
        }
    }

    /** Always false while the update check is off (see {@link #latestVersion}). */
    public static boolean isObsolete() {
        return false;
    }

}
