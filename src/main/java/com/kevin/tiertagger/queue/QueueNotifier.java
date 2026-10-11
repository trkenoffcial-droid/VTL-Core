package com.kevin.tiertagger.queue;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.queue.QueueMessages.Queue;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.net.URI;
import java.util.Locale;

/**
 * Puts queue events in front of the player: toasts for things that need attention now, a chat
 * line for the record (with buttons where there is something to do).
 *
 * <p>All methods must be called on the client thread - {@link QueueWebSocket} hops there first.</p>
 */
public final class QueueNotifier {
    private QueueNotifier() {}

    public static MutableComponent prefix() {
        return Component.literal("[VTL Core] ").withStyle(ChatFormatting.DARK_AQUA);
    }

    /** A queue opened. {@code joinButton} adds a clickable [Join] to the chat line. */
    public static void queueOpen(Queue queue, boolean joinButton) {
        String region = QueueMessages.regionLabel(queue.region());
        Component title = Component.translatable("tiertagger.queue.toast.title", queue.displayName(), region);
        String testers = queue.testers() == null || queue.testers().isEmpty() ? "-" : String.join(", ", queue.testers());
        Component body = Component.translatable("tiertagger.queue.toast.body", testers, queue.waiting());
        var key = com.kevin.tiertagger.TierTagger.quickJoinKey();
        toast(title, joinButton && key != null && !key.isUnbound()
                ? Component.translatable("vtl.toast.pressToJoin", body, key.getTranslatedKeyMessage())
                : body);
        playSound(Sound.OPEN);

        MutableComponent line = prefix()
                .append(Component.translatable("tiertagger.queue.chat.open", queue.displayName(), region).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY))
                .append(body.copy().withStyle(ChatFormatting.GRAY));
        if (joinButton) line.append(Component.literal(" ")).append(joinButton(queue));
        chat(line);
    }

    /** "YOUR TURN" across the middle of the screen, wherever the player is. */
    public static void yourTurnTitle(QueueMessages.YourTurn turn) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) return;
        String[] id = turn.queueId() == null ? new String[0] : turn.queueId().split(":", 2);
        com.kevin.tiertagger.hub.Kit kit = id.length == 2 ? com.kevin.tiertagger.hub.Kit.byId(id[1]) : null;
        String where = (kit != null ? kit.label() : "") + (id.length == 2 ? " · " + QueueMessages.regionLabel(id[0]) : "");
        mc.gui.setTimes(5, 70, 20);
        mc.gui.setTitle(Component.translatable("vtl.play.yourTurn").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        mc.gui.setSubtitle(turn.tester() == null || where.isEmpty()
                ? Component.literal(where)
                : Component.translatable("vtl.title.yourTurnSub", where, turn.tester()));
    }

    /** The tester (or staff) wrote in the ticket while the TICKET tab is not on screen. */
    public static void ticketMessage(String author, String text) {
        String body = text == null ? "" : text.replace('\n', ' ');
        if (body.length() > 70) body = body.substring(0, 69) + "…";
        var key = TierTagger.openHubKey();
        Component line = key != null && !key.isUnbound()
                ? Component.translatable("vtl.toast.ticketReply", body, key.getTranslatedKeyMessage())
                : Component.literal(body);
        toast(Component.translatable("vtl.toast.ticketFrom", author == null ? "Discord" : author), line);
        playSound(Sound.MESSAGE);
    }

    /** A result came in while another screen was open. */
    public static void resultWaiting() {
        var key = TierTagger.openHubKey();
        Component body = key != null && !key.isUnbound()
                ? Component.translatable("vtl.toast.resultWaiting", key.getTranslatedKeyMessage())
                : Component.translatable("vtl.toast.resultWaitingNoKey");
        toast(Component.translatable("vtl.toast.testComplete"), body);
        playSound(Sound.YOUR_TURN);
    }

    /** The ticket ended: the result, or why it closed. */
    public static void ticketClosed(QueueMessages.TicketClose close) {
        boolean complete = "result".equals(close.reason());
        Component title = Component.translatable(complete ? "vtl.toast.testComplete" : "vtl.toast.ticketClosed");
        Component body = close.message() != null && !close.message().isBlank()
                ? Component.literal(close.message())
                : Component.translatable("vtl.ticket.ended");
        toast(title, body);
        if (complete) playSound(Sound.YOUR_TURN);
    }

    /** The player is first in line. */
    public static void nextUp(String message) {
        toast(Component.translatable("tiertagger.queue.nextUp.title"), Component.literal(message));
        playSound(Sound.NEXT_UP);
        chat(prefix().append(Component.literal(message).withStyle(ChatFormatting.YELLOW)));
    }

    /** A tester pulled the player: point them at the Discord ticket. */
    public static void yourTurn(String message, String ticketUrl) {
        toast(Component.translatable("tiertagger.queue.yourTurn.title"), Component.literal(message));
        playSound(Sound.YOUR_TURN);
        MutableComponent line = prefix().append(Component.literal(message).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        URI uri = QueueMessages.discordTicketUri(ticketUrl);
        if (uri != null) {
            line.append(Component.literal(" ")).append(Component.translatable("tiertagger.queue.yourTurn.open")
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent.OpenUrl(uri))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(uri.toString())))));
        }
        chat(line);
    }

    /** Outcome of something the player asked for, or a notice from the server. */
    public static void result(boolean ok, Component message) {
        chat(prefix().append(message.copy().withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED)));
    }

    public static void info(Component message) {
        chat(prefix().append(message.copy().withStyle(ChatFormatting.GRAY)));
    }

    public static void warn(Component message) {
        chat(prefix().append(message.copy().withStyle(ChatFormatting.GOLD)));
    }

    /** The bot signed this game out (network, account or verified name changed): toast + chat once. */
    public static void relink(String message) {
        Component body = Component.literal(message == null || message.isBlank() ? "Link your Discord account again." : message);
        toast(Component.translatable("vtl.link.signedOut"), body);
        warn(body.copy().append(Component.literal(" ")).append(Component.translatable("vtl.link.again")));
    }

    /** A chat line without the prefix, for lists under a prefixed header. */
    public static void line(Component message) {
        chat(message);
    }

    /** [Join]: runs {@code /vtl join <mode> <region>} for exactly this queue. */
    public static MutableComponent joinButton(Queue queue) {
        return button("join", queue, ChatFormatting.GREEN);
    }

    /** [Leave]: runs {@code /vtl leave <mode> <region>}. */
    public static MutableComponent leaveButton(Queue queue) {
        return button("leave", queue, ChatFormatting.RED);
    }

    private static MutableComponent button(String action, Queue queue, ChatFormatting color) {
        String command = "/vtl " + action + " " + queue.mode().toLowerCase(Locale.ROOT) + " " + queue.region();
        Component hover = Component.translatable("tiertagger.queue.button." + action + ".hover", queue.displayName(), QueueMessages.regionLabel(queue.region()));
        return Component.translatable("tiertagger.queue.button." + action)
                .withStyle(style -> style.withColor(color).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    private enum Sound { OPEN, NEXT_UP, YOUR_TURN, MESSAGE }

    private static void playSound(Sound sound) {
        if (!TierTagger.getManager().getConfig().isQueueSound()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        // 0-100 in the settings; 100 is twice the old fixed 0.25, so alerts are easier to hear.
        float volume = TierTagger.getManager().getConfig().getAlertVolume() / 100f * 0.5f;
        if (volume <= 0f) return;
        switch (sound) {
            case OPEN -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 1.0F, volume));
            case NEXT_UP -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.5F, volume));
            case YOUR_TURN -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, volume));
            case MESSAGE -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.8F, volume * 0.8f));
        }
    }

    private static void toast(Component title, Component body) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        // A fresh id per toast: SystemToastId doesn't override equals, and reusing one would make
        // two events close together overwrite each other.
        SystemToast.add(mc.getToastManager(), new SystemToast.SystemToastId(), title, body);
    }

    private static void chat(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) return;
        mc.gui.getChat().addMessage(message);
    }
}
