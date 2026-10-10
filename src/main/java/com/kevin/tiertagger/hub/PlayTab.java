package com.kevin.tiertagger.hub;

import com.kevin.tiertagger.TierCache;
import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.hub.HubState.Action;
import com.kevin.tiertagger.hub.HubState.KitView;
import com.kevin.tiertagger.hub.HubState.Status;
import com.kevin.tiertagger.hub.widget.HubButton;
import com.kevin.tiertagger.hub.widget.KitCarousel;
import com.kevin.tiertagger.hub.widget.PlayerView;
import com.kevin.tiertagger.model.PlayerInfo;
import com.kevin.tiertagger.queue.QueueLinkScreen;
import com.kevin.tiertagger.queue.QueueMessages;
import com.kevin.tiertagger.queue.QueueMessages.Hello;
import com.kevin.tiertagger.queue.QueueMessages.Queue;
import com.kevin.tiertagger.queue.QueueMessages.Result;
import com.kevin.tiertagger.queue.QueueMessages.YourTurn;
import com.kevin.tiertagger.queue.QueueTokenStore;
import com.kevin.tiertagger.queue.QueueWebSocket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * PLAY: the player on the stage, the kit carousel and the one big button (join, leave, open
 * ticket, link). What it shows comes from {@link HubState}; this class only lays it out.
 */
public class PlayTab implements HubTab {
    private VtlHubScreen screen;
    private int ax, ay, aw, ah;
    private KitCarousel carousel;
    private HubButton action;
    private HubButton regionButton;
    private final PlayerView player = new PlayerView();
    private static final ItemStack VAULT = new ItemStack(Items.VAULT);
    private int stageX, stageY, stageW, stageH;
    private int infoX, infoY, infoW, infoH;

    private List<KitView> views = List.of();
    private int selected;
    private boolean userPicked;
    private final Map<Kit, String> regionOverride = new EnumMap<>(Kit.class);
    private boolean pending;
    private Component message;
    private int messageColor;
    private long messageUntil;

    @Override
    public boolean wantsStage() {
        return true;
    }

    @Override
    public void init(VtlHubScreen screen, int x, int y, int w, int h) {
        this.screen = screen;
        this.ax = x;
        this.ay = y;
        this.aw = w;
        this.ah = h;

        // stage: name + tiers on top, player below, centred
        stageH = Math.round(h * 0.40f);
        stageW = Math.max(40, Math.round(stageH * 0.55f));
        stageX = x + (w - stageW) / 2;
        stageY = y + Math.round(h * 0.13f);
        player.init(screen, stageX, stageY, stageW, stageH);

        // carousel under the stage
        int carouselTop = stageY + stageH + 4;
        int actionH = screen.compact() ? 20 : 24;
        int carouselH = Math.max(40, y + h - carouselTop - actionH - 18);
        int carouselW = Math.min(w - 60, 720);
        carousel = screen.add(new KitCarousel(x + (w - carouselW) / 2, carouselTop, carouselW, carouselH, i -> {
            userPicked = true;
            selected = i;
        }));
        screen.add(new HubButton(carousel.getX() - 22, carouselTop + carouselH / 2 - 9, 16, 18, Component.literal("‹"), HubButton.Style.GHOST, () -> step(-1)));
        screen.add(new HubButton(carousel.getX() + carouselW + 6, carouselTop + carouselH / 2 - 9, 16, 18, Component.literal("›"), HubButton.Style.GHOST, () -> step(1)));

        // the big button and, beside it, the info box
        int actionW = screen.compact() ? 130 : 170;
        infoW = screen.compact() ? 0 : Math.min(230, (w - actionW) / 2 - 20);
        int rowW = actionW + (infoW > 0 ? infoW + 10 : 0);
        int rowX = x + (w - rowW) / 2;
        int rowY = y + h - actionH - 10;
        action = screen.add(new HubButton(rowX, rowY, actionW, actionH, Component.empty(), HubButton.Style.PRIMARY, this::press)
                .textScale(screen.compact() ? 1f : 1.25f));
        infoX = rowX + actionW + 10;
        infoY = rowY;
        infoH = actionH;
        regionButton = screen.add(new HubButton(infoX + infoW - 46, infoY + 3, 42, infoH - 6, Component.empty(), HubButton.Style.GHOST, this::nextRegion));
        regionButton.visible = false;

        refresh();
    }

    // --- state ---

    private void refresh() {
        Hello hello = TierTagger.getQueue().hello();
        List<KitView> fresh = HubState.kits(TierTagger.getQueue().openQueues(), hello == null ? null : hello.region());
        // the region the player picked by hand, while that queue is still open
        fresh = fresh.stream().map(v -> {
            String wanted = regionOverride.get(v.kit());
            if (wanted == null || v.status() == Status.WAITING || v.status() == Status.TESTING) return v;
            return v.queues().stream().filter(q -> wanted.equals(q.queueId())).findFirst()
                    .map(q -> HubState.withChosen(v, q)).orElse(v);
        }).toList();
        views = fresh;
        if (!userPicked || views.stream().anyMatch(v -> v.status() == Status.WAITING || v.status() == Status.TESTING)) {
            selected = HubState.defaultSelection(views);
        }
        carousel.setViews(views, selected);
        player.setKit(views.get(selected).kit());

        KitView view = views.get(selected);
        Action a = currentAction(view);
        action.setMessage(Component.translatable("vtl.play.action." + a.name().toLowerCase(Locale.ROOT)));
        action.setStyle(switch (a) {
            case LEAVE -> HubButton.Style.DANGER;
            case OPEN_TICKET -> HubButton.Style.SUCCESS;
            default -> HubButton.Style.PRIMARY;
        });
        action.active = !pending && (a == Action.LINK || a == Action.JOIN || a == Action.LEAVE || a == Action.OPEN_TICKET);

        boolean manyRegions = infoW > 0 && view.queues().size() > 1 && view.status() != Status.WAITING && view.status() != Status.TESTING;
        regionButton.visible = manyRegions;
        if (manyRegions) regionButton.setMessage(Component.literal(QueueMessages.regionLabel(view.chosen().region()) + " ⇄"));
    }

    private Action currentAction(KitView view) {
        Hello hello = TierTagger.getQueue().hello();
        boolean connected = TierTagger.getQueue().isConnected() && hello != null;
        boolean linked = QueueTokenStore.isLinked();
        return HubState.action(view, connected, linked, hello != null && hello.canJoin());
    }

    @Override
    public void tick() {
        refresh();
    }

    private void step(int direction) {
        userPicked = true;
        selected = Math.floorMod(selected + direction, views.size());
        refresh();
    }

    private void nextRegion() {
        KitView view = views.get(selected);
        List<Queue> queues = view.queues();
        if (queues.size() < 2) return;
        int at = queues.indexOf(view.chosen());
        regionOverride.put(view.kit(), queues.get((at + 1) % queues.size()).queueId());
        refresh();
    }

    // --- the big button ---

    private void press() {
        KitView view = views.get(selected);
        switch (currentAction(view)) {
            case LINK -> Minecraft.getInstance().setScreen(new QueueLinkScreen(screen));
            case JOIN -> send(TierTagger.getQueue().join(view.chosen().queueId()));
            case LEAVE -> send(TierTagger.getQueue().leave(view.chosen().queueId()));
            // chat in the game when the bot supports it (V37), else the ticket on Discord
            case OPEN_TICKET -> {
                if (TierTagger.getQueue().ticketLog().snapshot().ticket() != null) screen.switchTo(HubTab.Id.TICKET);
                else openTicket();
            }
            default -> {
                // disabled states do nothing
            }
        }
    }

    private void send(CompletableFuture<Result> request) {
        pending = true;
        refresh();
        request.thenAccept(result -> Minecraft.getInstance().execute(() -> {
            pending = false;
            show(QueueWebSocket.describe(result), result.ok() ? HubTheme.GREEN : HubTheme.RED);
            refresh();
        }));
    }

    private void openTicket() {
        YourTurn turn = QueueWebSocket.lastYourTurn();
        URI uri = turn == null ? null : QueueMessages.discordTicketUri(turn.ticketUrl());
        if (uri == null) {
            show(Component.translatable("vtl.play.ticketOnDiscord"), HubTheme.GOLD);
            return;
        }
        Util.getPlatform().openUri(uri);
    }

    private void show(Component text, int color) {
        message = text;
        messageColor = color;
        messageUntil = Util.getMillis() + 6000;
    }

    // --- input ---

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_LEFT) {
            step(-1);
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_RIGHT) {
            step(1);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return button == 0 && player.mouseClicked(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, double dragX, double dragY) {
        return player.mouseDragged(dragX);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY) {
        return player.mouseReleased();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseY < carousel.getY() - 20 || mouseY > carousel.getY() + carousel.getHeight()) return false;
        step(vertical > 0 ? -1 : 1);
        return true;
    }

    // --- drawing ---

    @Override
    public void renderBehind(GuiGraphics g, int mouseX, int mouseY, float delta) {
        var font = Minecraft.getInstance().font;
        Minecraft mc = Minecraft.getInstance();
        String name = mc.getUser().getName();
        int cx = ax + aw / 2;
        int nameY = ay + Math.round(ah * 0.025f);
        HubTheme.centered(g, font, Component.literal(name.toUpperCase(Locale.ROOT)), cx, nameY, 0xFFFFFFFF, screen.compact() ? 1.25f : 1.75f);
        if (!screen.compact()) drawTierLine(g, name, cx, nameY + 17);

        // a real vault behind the player's body, bobbing slowly; big enough to read as the backdrop
        int vault = Math.round(stageH * 0.62f);
        float bob = (float) Math.sin(Util.getMillis() / 1000.0 * 1.3) * 2f;
        HubTheme.glow(g, cx, stageY + stageH * 2 / 5, vault, vault, HubTheme.argb(0xC27BFF, 0x60));
        g.pose().pushMatrix();
        g.pose().translate(cx - vault / 2f, stageY + stageH * 0.10f + bob);
        g.pose().scale(vault / 16f, vault / 16f);
        g.renderItem(VAULT, 0, 0);
        g.pose().popMatrix();
        g.fill(cx - vault / 2, stageY, cx + vault / 2 + 1, stageY + stageH, 0x40000000);

        // pedestal glow under the feet
        HubTheme.glow(g, cx, stageY + stageH - 2, stageW, 6, HubTheme.argb(0xC27BFF, 0xB0));
        player.render(g, mouseX, mouseY);
    }

    private void drawTierLine(GuiGraphics g, String name, int cx, int y) {
        var font = Minecraft.getInstance().font;
        UUID uuid = Minecraft.getInstance().getGameProfile().id();
        var rankings = TierCache.getPlayerRankings(uuid, name);
        if (rankings.isEmpty() || rankings.get().isEmpty()) return;
        List<Map.Entry<String, PlayerInfo.Ranking>> best = rankings.get().entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null && !e.getValue().retired())
                .sorted(Comparator.comparingInt(e -> e.getValue().comparableTier()))
                .limit(3).toList();
        if (best.isEmpty()) return;
        List<MutableComponent> chips = best.stream().map(e -> Component.empty()
                .append(TierTagger.getRankingText(e.getValue(), false))
                .append(Component.literal(" " + TierCache.findModeOrUgly(e.getKey()).title()).withStyle(s -> s.withColor(0xD9C6F0)))).toList();
        int total = chips.stream().mapToInt(c -> font.width(c) + 10).sum() + (chips.size() - 1) * 4;
        int x = cx - total / 2;
        for (MutableComponent chip : chips) {
            int w = font.width(chip) + 10;
            HubTheme.frame(g, x, y, w, 13, 0xCC1E0E2C, 0xFF4A2D66);
            g.drawString(font, chip, x + 5, y + 3, 0xFFFFFFFF, false);
            x += w + 4;
        }
    }

    @Override
    public void renderFront(GuiGraphics g, int mouseX, int mouseY, float delta) {
        var font = Minecraft.getInstance().font;
        if (views.isEmpty()) return;
        KitView view = views.get(selected);

        if (view.status() == Status.WAITING && view.chosen() != null) {
            int cx = ax + aw / 2;
            int y = stageY + stageH - 26;
            Component pos = Component.literal("#" + (view.myPosition() == null ? "?" : view.myPosition()));
            HubTheme.glow(g, cx, y + 10, 40, 22, HubTheme.argb(0xFFC83D, 0x50));
            HubTheme.centered(g, font, pos, cx, y, HubTheme.GOLD, 2.5f);
            int ahead = view.myPosition() == null ? 0 : Math.max(0, view.myPosition() - 1);
            Component line = Component.translatable("vtl.play.waitingLine", view.kit().label().toUpperCase(Locale.ROOT),
                    QueueMessages.regionLabel(view.chosen().region()), ahead,
                    WaitClock.format(WaitClock.waited(view.chosen().sessionId(), Util.getMillis())));
            HubTheme.centered(g, font, line, cx, y + 23, 0xFFE6D8F5, 0.85f);
        } else if (view.status() == Status.TESTING) {
            int y = stageY + stageH / 2 - 16;
            // dark band first so the words read over the player behind them
            g.fill(ax, y, ax + aw, y + 34, 0xC80A1610);
            g.fillGradient(ax, y, ax + aw, y + 34, 0x305DFF8A, 0x105DFF8A);
            g.fill(ax, y, ax + aw, y + 1, 0x995DFF8A);
            g.fill(ax, y + 33, ax + aw, y + 34, 0x995DFF8A);
            HubTheme.centered(g, font, Component.translatable("vtl.play.yourTurn"), ax + aw / 2, y + 5, 0xFFFFFFFF, 2f);
            YourTurn turn = QueueWebSocket.lastYourTurn();
            String tester = turn != null && turn.tester() != null ? turn.tester() : String.join(", ", view.chosen().testers());
            HubTheme.centered(g, font, Component.translatable("vtl.play.yourTurnSub", view.kit().label(),
                    QueueMessages.regionLabel(view.chosen().region()), tester), ax + aw / 2, y + 23, 0xFFD9FFE4, 0.85f);
        }

        if (infoW > 0 && view.chosen() != null) {
            HubTheme.frame(g, infoX, infoY, infoW, infoH, HubTheme.PANEL_SOLID, HubTheme.BLACK);
            Queue q = view.chosen();
            Component title = Component.literal(view.kit().label() + " · " + QueueMessages.regionLabel(q.region())).withStyle(s -> s.withBold(true));
            int textW = infoW - 10 - (regionButton.visible ? 48 : 0);
            g.drawString(font, title, infoX + 6, infoY + 3, HubTheme.TEXT, false);
            String testers = q.testers() == null || q.testers().isEmpty() ? "-" : String.join(", ", q.testers());
            long minutes = q.openedAt() == null ? -1 : Math.max(0, (System.currentTimeMillis() - q.openedAt()) / 60000);
            Component detail = minutes < 0
                    ? Component.translatable("vtl.play.info", testers, q.waiting())
                    : Component.translatable("vtl.play.infoOpen", testers, minutes, q.waiting());
            String clipped = HubTheme.ellipsize(font, detail.getString(), textW);
            g.drawString(font, clipped, infoX + 6, infoY + infoH - 11, HubTheme.DIM, false);
        }

        if (message != null && Util.getMillis() < messageUntil) {
            HubTheme.centered(g, font, message, ax + aw / 2, action.getY() - 12, messageColor, 1f);
        }
    }
}
