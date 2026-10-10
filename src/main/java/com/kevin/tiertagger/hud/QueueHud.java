package com.kevin.tiertagger.hud;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.config.TierTaggerConfig;
import com.kevin.tiertagger.hub.HubState;
import com.kevin.tiertagger.hub.HubState.KitView;
import com.kevin.tiertagger.hub.HubState.Status;
import com.kevin.tiertagger.hub.HubTheme;
import com.kevin.tiertagger.hub.WaitClock;
import com.kevin.tiertagger.queue.QueueMessages;
import com.kevin.tiertagger.queue.QueueMessages.Hello;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.List;

/**
 * The small queue box in a corner of the game screen: the player's place in line while waiting,
 * "your turn" while tested, otherwise how many queues are open for their alert filter.
 */
public final class QueueHud implements HudElement {
    private static final int PAD = 4;
    private static final int ICON = 12;

    public static void register() {
        HudElementRegistry.addLast(HubTheme.vtl("queue_hud"), new QueueHud());
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        TierTaggerConfig config = TierTagger.getManager().getConfig();
        if (!config.isHudEnabled() || !config.isQueueNotify() || mc.options.hideGui || mc.screen != null) return;

        Component text = text(config);
        if (text == null) return;

        var font = mc.font;
        int w = ICON + 4 + font.width(text) + PAD * 2;
        int h = ICON + PAD;
        int screenW = g.guiWidth(), screenH = g.guiHeight();
        int x = switch (config.getHudCorner()) {
            case TOP_LEFT, BOTTOM_LEFT -> 4;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenW - w - 4;
        };
        int y = switch (config.getHudCorner()) {
            case TOP_LEFT, TOP_RIGHT -> 4;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenH - h - 40;
        };
        g.fill(x, y, x + w, y + h, 0xC00C0612);
        g.fill(x, y, x + 2, y + h, HubTheme.GOLD);
        HubTheme.image(g, HubTheme.ICON, x + PAD, y + PAD / 2, ICON, ICON, 128, 128);
        g.drawString(font, text, x + PAD + ICON + 4, y + (h - 8) / 2, HubTheme.TEXT, true);
    }

    /** What the box says, or null to show nothing. */
    private static Component text(TierTaggerConfig config) {
        Hello hello = TierTagger.getQueue().hello();
        if (hello == null || !TierTagger.getQueue().isConnected()) return null;
        List<KitView> views = HubState.kits(TierTagger.getQueue().openQueues(), hello.region());
        for (KitView v : views) {
            if (v.chosen() == null) continue;
            String where = v.kit().label() + " · " + QueueMessages.regionLabel(v.chosen().region());
            if (v.status() == Status.TESTING) {
                var line = Component.literal(where + "  ").append(Component.translatable("vtl.play.yourTurn").withStyle(s -> s.withColor(0x5DFF8A)));
                int unread = TierTagger.getQueue().ticketLog().snapshot().unread();
                if (unread > 0) line.append(Component.literal(" · ").append(Component.translatable("vtl.hud.unread", unread)).withStyle(s -> s.withColor(0xFFC83D)));
                return line;
            }
            if (v.status() == Status.WAITING) {
                String pos = "#" + (v.myPosition() == null ? "?" : v.myPosition());
                String time = WaitClock.format(WaitClock.waited(v.chosen().sessionId(), Util.getMillis()));
                return Component.literal(where + "  ").append(Component.literal(pos).withStyle(s -> s.withColor(0xFFC83D)))
                        .append(Component.literal(" · " + time).withStyle(s -> s.withColor(0x9D8FB0)));
            }
        }
        long open = TierTagger.getQueue().openQueues().stream()
                .filter(q -> q.region() != null && q.mode() != null && config.matchesQueueFilter(q.region(), q.mode()))
                .count();
        if (open == 0) return null;
        return Component.translatable(open == 1 ? "vtl.hub.queues_open.one" : "vtl.hub.queues_open", open);
    }
}
