package com.kevin.tiertagger.queue;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.config.TierTaggerConfig;
import com.kevin.tiertagger.queue.QueueMessages.Queue;
import com.kevin.tiertagger.queue.QueueMessages.Result;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * The queue part of {@code /tiertagger}:
 * <ul>
 *     <li>{@code link <code>} / {@code unlink}</li>
 *     <li>{@code queues}: every open queue, with [Join] / [Leave] buttons</li>
 *     <li>{@code join [mode] [region]} / {@code leave [mode] [region]}</li>
 * </ul>
 *
 * <p>The bot runs one queue per region and kit, so a bare {@code join} only goes straight
 * through when one queue fits; otherwise the player gets clickable choices. The bot decides
 * whether a join is allowed and its answer is shown as-is - the checks here only pick the
 * queue.</p>
 */
public final class QueueCommands {
    private QueueCommands() {}

    /** Bot-side names players know from Discord, mapped to the ids the protocol uses. */
    private static final Map<String, String> MODE_ALIASES = Map.of(
            "crystal", "vanilla",
            "neth", "nethop",
            "diapot", "pot",
            "axeshield", "axe");

    private static final List<String> REGIONS = List.of("AS_ME", "AU_OC", "NA_SA", "EU");

    private static final SuggestionProvider<FabricClientCommandSource> JOIN_MODES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(modesOf(q -> true), builder);
    private static final SuggestionProvider<FabricClientCommandSource> LEAVE_MODES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(modesOf(QueueCommands::isWaiting), builder);
    private static final SuggestionProvider<FabricClientCommandSource> REGIONS_FOR_MODE =
            (ctx, builder) -> SharedSuggestionProvider.suggest(regionsFor(ctx), builder);

    public static LiteralArgumentBuilder<FabricClientCommandSource> addTo(LiteralArgumentBuilder<FabricClientCommandSource> root) {
        return root
                .executes(ctx -> help())
                .then(literal("link")
                        .executes(ctx -> linkUsage())
                        .then(argument("code", StringArgumentType.word())
                                .executes(ctx -> link(StringArgumentType.getString(ctx, "code")))))
                .then(literal("unlink")
                        .executes(ctx -> unlink()))
                .then(literal("queues")
                        .executes(ctx -> list()))
                .then(literal("join")
                        .executes(ctx -> join(null, null))
                        .then(argument("mode", StringArgumentType.word()).suggests(JOIN_MODES)
                                .executes(ctx -> join(StringArgumentType.getString(ctx, "mode"), null))
                                .then(argument("region", StringArgumentType.word()).suggests(REGIONS_FOR_MODE)
                                        .executes(ctx -> join(StringArgumentType.getString(ctx, "mode"), StringArgumentType.getString(ctx, "region"))))))
                .then(literal("leave")
                        .executes(ctx -> leave(null, null))
                        .then(argument("mode", StringArgumentType.word()).suggests(LEAVE_MODES)
                                .executes(ctx -> leave(StringArgumentType.getString(ctx, "mode"), null))
                                .then(argument("region", StringArgumentType.word()).suggests(REGIONS_FOR_MODE)
                                        .executes(ctx -> leave(StringArgumentType.getString(ctx, "mode"), StringArgumentType.getString(ctx, "region"))))));
    }

    // === help ===

    /** {@code /tiertagger} on its own: what the subcommands are. */
    private static int help() {
        QueueNotifier.info(tr("help"));
        return 1;
    }

    /** {@code /tiertagger link} without a code: where the code comes from. */
    private static int linkUsage() {
        QueueNotifier.info(tr("link.usage"));
        return 1;
    }

    // === link ===

    private static int link(String code) {
        if (!code.matches("[A-Za-z0-9]{6}")) {
            QueueNotifier.result(false, tr("link.invalidFormat"));
            return 0;
        }
        QueueNotifier.info(tr("link.sending"));
        QueueLinker.link(code);
        return 1;
    }

    private static int unlink() {
        if (!QueueTokenStore.isLinked()) {
            QueueNotifier.info(tr("unlink.notLinked"));
            return 0;
        }
        QueueLinker.unlink();
        return 1;
    }

    // === list ===

    private static int list() {
        QueueWebSocket queue = TierTagger.getQueue();
        if (!queue.isConnected() || queue.hello() == null) {
            QueueNotifier.warn(tr(TierTagger.getManager().getConfig().isQueueNotify() ? "join.offline" : "queues.off"));
            return 0;
        }
        List<Queue> open = sorted(queue.openQueues());
        if (open.isEmpty()) {
            QueueNotifier.info(tr("queues.none"));
            return 1;
        }
        QueueNotifier.info(tr("queues.header", open.size()));
        boolean buttons = QueueTokenStore.isLinked() && queue.hello().canJoin();
        for (Queue q : open) QueueNotifier.line(queueLine(q, buttons));
        if (!QueueTokenStore.isLinked()) QueueNotifier.info(tr("queues.linkHint"));
        return 1;
    }

    /** {@code  • Sword EU - Tester: TesterBot - 3 waiting [Join]} */
    private static Component queueLine(Queue q, boolean buttons) {
        MutableComponent line = Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(q.displayName()).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(" " + QueueMessages.regionLabel(q.region())).withStyle(ChatFormatting.AQUA));
        if (q.testers() != null && !q.testers().isEmpty()) {
            line.append(separator()).append(tr("queues.tester", String.join(", ", q.testers())).withStyle(ChatFormatting.GRAY));
        }
        line.append(separator());
        if (q.me() != null && q.me().isTesting()) {
            line.append(tr("queues.testing").withStyle(ChatFormatting.GREEN));
        } else if (isWaiting(q)) {
            line.append(q.me().position() == null
                    ? tr("queues.inQueue").withStyle(ChatFormatting.YELLOW)
                    : tr("queues.position", q.me().position()).withStyle(ChatFormatting.YELLOW));
        } else {
            line.append(tr("queues.waiting", q.waiting()).withStyle(ChatFormatting.GRAY));
        }

        if (buttons) {
            if (isWaiting(q)) {
                line.append(Component.literal(" ")).append(QueueNotifier.leaveButton(q));
            } else if (q.me() == null || !q.me().isTesting()) {
                line.append(Component.literal(" ")).append(Boolean.FALSE.equals(q.canJoin())
                        ? tr("queues.notEligible").withStyle(ChatFormatting.DARK_GRAY)
                        : QueueNotifier.joinButton(q));
            }
        }
        return line;
    }

    private static MutableComponent separator() {
        return Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY);
    }

    // === join / leave ===

    private static int join(String mode, String region) {
        QueueWebSocket queue = ready();
        if (queue == null) return 0;

        List<Queue> matches = matching(queue.openQueues(), mode, region, q -> true);
        if (matches.isEmpty()) {
            QueueNotifier.result(false, mode == null ? tr("join.no_queue") : tr("join.no_queue_mode", modeLabel(mode)));
            return 0;
        }
        List<Queue> joinable = matches.stream()
                .filter(q -> !Boolean.FALSE.equals(q.canJoin()) && !isWaiting(q) && !(q.me() != null && q.me().isTesting()))
                .toList();
        // One clear choice: go. One queue but not joinable: send anyway, the bot says why.
        Queue target = joinable.size() == 1 ? joinable.getFirst() : matches.size() == 1 ? matches.getFirst() : null;
        if (target == null) {
            choose(joinable.isEmpty() ? matches : joinable, true);
            return 1;
        }
        send(queue, target, true);
        return 1;
    }

    private static int leave(String mode, String region) {
        QueueWebSocket queue = ready();
        if (queue == null) return 0;

        List<Queue> waiting = matching(queue.openQueues(), mode, region, QueueCommands::isWaiting);
        if (waiting.isEmpty()) {
            QueueNotifier.result(false, tr("leave.none"));
            return 0;
        }
        if (waiting.size() > 1) {
            choose(waiting, false);
            return 1;
        }
        send(queue, waiting.getFirst(), false);
        return 1;
    }

    /** The connection, when it can take a join/leave; otherwise tells the player why not. */
    private static QueueWebSocket ready() {
        if (!QueueTokenStore.isLinked()) {
            QueueNotifier.result(false, tr("join.not_linked"));
            return null;
        }
        QueueWebSocket queue = TierTagger.getQueue();
        if (!queue.isConnected() || queue.hello() == null) {
            QueueNotifier.result(false, tr(TierTagger.getManager().getConfig().isQueueNotify() ? "join.offline" : "queues.off"));
            return null;
        }
        if (!queue.hello().canJoin()) {
            QueueNotifier.result(false, tr("join.disabled"));
            return null;
        }
        return queue;
    }

    private static void choose(List<Queue> options, boolean join) {
        QueueNotifier.info(tr(join ? "choose.join" : "choose.leave"));
        for (Queue q : sorted(options)) QueueNotifier.line(queueLine(q, true));
    }

    private static void send(QueueWebSocket queue, Queue target, boolean join) {
        String region = QueueMessages.regionLabel(target.region());
        QueueNotifier.info(tr(join ? "join.sending" : "leave.sending", target.displayName(), region));
        CompletableFuture<Result> answer = join ? queue.join(target.queueId()) : queue.leave(target.queueId());
        answer.thenAccept(result -> QueueWebSocket.runOnClientThread(
                () -> QueueNotifier.result(result.ok(), QueueWebSocket.describe(result))));
    }

    // === matching ===

    private static boolean isWaiting(Queue q) {
        return q.me() != null && q.me().isWaiting();
    }

    private static List<Queue> matching(Collection<Queue> open, String mode, String region, Predicate<Queue> extra) {
        String wantMode = normalizeMode(mode);
        String wantRegion = normalizeRegion(region);
        return open.stream()
                .filter(q -> q.mode() != null && q.region() != null)
                .filter(q -> wantMode == null || q.mode().equalsIgnoreCase(wantMode))
                .filter(q -> wantRegion == null || q.region().equalsIgnoreCase(wantRegion))
                .filter(extra)
                .toList();
    }

    static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) return null;
        String m = mode.trim().toLowerCase(Locale.ROOT);
        return MODE_ALIASES.getOrDefault(m, m);
    }

    /** Accepts {@code EU}, {@code as_me}, or just the first part ({@code as}, {@code na}). */
    static String normalizeRegion(String region) {
        if (region == null || region.isBlank()) return null;
        String r = region.trim().toUpperCase(Locale.ROOT);
        for (String known : REGIONS) {
            if (known.equals(r) || known.startsWith(r + "_")) return known;
        }
        return r;
    }

    private static Component modeLabel(String mode) {
        String id = normalizeMode(mode);
        return Component.literal(id == null ? "" : id);
    }

    /** Open queues in the order of the mode filter list, then by region. */
    private static List<Queue> sorted(Collection<Queue> queues) {
        List<String> order = TierTaggerConfig.QUEUE_MODES;
        return queues.stream()
                .filter(q -> q.mode() != null && q.region() != null)
                .sorted(Comparator.<Queue>comparingInt(q -> {
                            int i = order.indexOf(q.mode().toLowerCase(Locale.ROOT));
                            return i < 0 ? Integer.MAX_VALUE : i;
                        })
                        .thenComparing(q -> REGIONS.indexOf(q.region())))
                .toList();
    }

    private static List<String> modesOf(Predicate<Queue> filter) {
        return TierTagger.getQueue().openQueues().stream()
                .filter(q -> q.mode() != null)
                .filter(filter)
                .map(q -> q.mode().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> regionsFor(CommandContext<FabricClientCommandSource> ctx) {
        String mode;
        try {
            mode = normalizeMode(StringArgumentType.getString(ctx, "mode"));
        } catch (IllegalArgumentException e) {
            mode = null;
        }
        String wanted = mode;
        return TierTagger.getQueue().openQueues().stream()
                .filter(q -> q.region() != null && q.mode() != null)
                .filter(q -> wanted == null || q.mode().equalsIgnoreCase(wanted))
                .map(Queue::region)
                .distinct()
                .toList();
    }

    private static MutableComponent tr(String key, Object... args) {
        return Component.translatable("tiertagger.queue." + key, args);
    }
}
