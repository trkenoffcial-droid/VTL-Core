package com.kevin.tiertagger.hub;

import com.kevin.tiertagger.compat.Players;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.config.TierTaggerConfig;
import com.kevin.tiertagger.hub.widget.HubButton;
import com.kevin.tiertagger.queue.QueueMessages;
import com.kevin.tiertagger.queue.QueueMessages.Hello;
import com.kevin.tiertagger.queue.QueueTokenStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9
import net.minecraft.client.input.KeyEvent;
//? if >=1.21.9
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The VTL Hub: one full-screen place for queues, players and settings, opened from the title
 * screen, the pause menu, the R key or {@code /vtl}.
 */
public class VtlHubScreen extends Screen {
    public static final int TOP = 26;
    public static final int BOTTOM = 14;

    private final Screen parent;
    private HubTab.Id current;
    private HubTab tab;
    private final Map<HubTab.Id, HubButton> tabButtons = new EnumMap<>(HubTab.Id.class);
    private final long openedAt = Util.getMillis();
    private Supplier<PlayerSkin> ownSkin;
    private boolean compact;
    private final com.kevin.tiertagger.hub.fx.HubEffects effects = new com.kevin.tiertagger.hub.fx.HubEffects();

    public VtlHubScreen(Screen parent, HubTab.Id start) {
        super(Component.translatable("vtl.hub.title"));
        this.parent = parent instanceof VtlHubScreen hub ? hub.parent : parent;
        this.current = start;
    }

    /** Opens the hub on {@code tab} from any thread, returning to whatever screen was open. */
    public static void open(HubTab.Id tab) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new VtlHubScreen(mc.screen, tab)));
    }

    public Screen parent() {
        return parent;
    }

    /** The tab on screen. */
    public HubTab.Id current() {
        return current;
    }

    /** The tab object on screen (DevShots reads its state). */
    public HubTab tab() {
        return tab;
    }

    /** The player's own skin, looked up once per hub. */
    public Supplier<PlayerSkin> ownSkin() {
        if (ownSkin == null) ownSkin = Players.skin(Minecraft.getInstance().getGameProfile(), true);
        return ownSkin;
    }

    public float time() {
        return (Util.getMillis() - openedAt) / 1000f;
    }

    /** Whether the window is too small for the decorative extras. */
    public boolean compact() {
        return compact;
    }

    // --- widget access for tabs ---

    public <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget) {
        return this.addRenderableWidget(widget);
    }

    /** Puts the keyboard focus on {@code widget} (e.g. a search box) when the tab opens. */
    public void focus(GuiEventListener widget) {
        this.setInitialFocus(widget);
    }

    public void switchTo(HubTab.Id id) {
        if (id == current && tab != null) return;
        if (tab != null) tab.removed();
        current = id;
        tab = null;
        this.rebuildWidgets();
    }

    /** Rebuilds the current tab (after linking, a setting that changes the layout, ...). */
    public void refresh() {
        this.rebuildWidgets();
    }

    @Override
    protected void init() {
        com.kevin.tiertagger.compat.ItemComponents.ensureBound(); // 26.1+: items need components, even on the title screen
        this.compact = this.width < 460 || this.height < 280;
        if (ownSkin == null) ownSkin = Players.skin(Minecraft.getInstance().getGameProfile(), true);

        if (tab != null) tab.removed();
        tab = switch (current) {
            case PLAY -> new PlayTab();
            case TICKET -> new TicketTab();
            case PLAYERS -> new PlayersTab();
            case SETTINGS -> new SettingsTab();
        };

        tabButtons.clear();
        int x = compact ? 30 : 88;
        for (HubTab.Id id : HubTab.Id.values()) {
            Component label = tabLabel(id);
            // room for "TICKET (99)" so the button does not grow when messages come in
            Component widest = id == HubTab.Id.TICKET ? Component.translatable("vtl.hub.tab.ticketUnread", 99) : label;
            int w = Math.max(54, this.font.width(widest) + 18);
            HubButton b = new HubButton(x, 4, w, TOP - 8, label, HubButton.Style.TAB, () -> switchTo(id));
            b.setSelected(id == current);
            tabButtons.put(id, this.addRenderableWidget(b));
            x += w + 2;
        }
        this.addRenderableWidget(new HubButton(this.width - 22, 4, 18, TOP - 8, Component.literal("✕"), HubButton.Style.DANGER, this::onClose));

        tab.init(this, 0, TOP, this.width, this.height - TOP - BOTTOM);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        //? if >=1.21.6
        drawBackground(g);
    }

    private void drawBackground(GuiGraphics g) {
        HubBackground.render(g, this.width, this.height, TOP, BOTTOM, time(), tab != null && tab.wantsStage());
        effects.render(g, this.width, this.height, TOP, BOTTOM, time());
        g.fill(0, 0, this.width, TOP, HubTheme.BAR);
        g.fill(0, TOP - 1, this.width, TOP, 0xFF2C1940);
        g.fill(0, this.height - BOTTOM, this.width, this.height, HubTheme.BAR);
        g.fill(0, this.height - BOTTOM, this.width, this.height - BOTTOM + 1, 0xFF2C1940);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        //? if <1.21.6 {
        /*// before 1.21.6 Screen#render drew the background itself, over the stage drawn before it
        drawBackground(g);
        *///?}
        if (tab != null) tab.renderBehind(g, mouseX, mouseY, delta);
        super.render(g, mouseX, mouseY, delta);
        renderTopBar(g);
        renderBottomBar(g);
        if (tab != null) tab.renderFront(g, mouseX, mouseY, delta);
    }

    private void renderTopBar(GuiGraphics g) {
        g.fill(4, 3, 24, 23, HubTheme.BLACK);
        HubTheme.image(g, HubTheme.ICON, 5, 4, 18, 18, 128, 128);
        g.renderOutline(4, 3, 20, 20, HubTheme.GOLD);
        if (!compact) HubTheme.image(g, HubTheme.WORDMARK, 28, 6, 54, 18, 720, 240);

        // right side, right to left: [close] name+face, region, status
        int right = this.width - 28;
        Hello hello = TierTagger.getQueue().hello();
        boolean linked = QueueTokenStore.isLinked();
        String name = linked && hello != null && hello.discordName() != null ? hello.discordName() : Minecraft.getInstance().getUser().getName();
        // Discord names can be long; keep clear of the tab buttons on small windows
        Component nameText = Component.literal(HubTheme.ellipsize(this.font, name, compact ? 64 : 140));
        int nameW = this.font.width(nameText);
        g.drawString(this.font, nameText, right - nameW, 9, HubTheme.TEXT, true);
        right -= nameW + 4;
        PlayerFaceRenderer.draw(g, ownSkin.get(), right - 12, 7, 12);
        right -= 18;

        if (!compact && linked && hello != null && hello.region() != null) {
            Component region = Component.literal(QueueMessages.regionLabel(hello.region()));
            int w = this.font.width(region) + 10;
            HubTheme.frame(g, right - w, 6, w, 14, 0xFF1C1129, 0xFF57407A);
            g.drawString(this.font, region, right - w + 5, 9, HubTheme.TEXT, false);
            right -= w + 4;
        }

        Status status = status();
        Component label = Component.translatable(status.key);
        int w = this.font.width(label) + 16;
        HubTheme.frame(g, right - w, 6, w, 14, HubTheme.argb(status.color, 0x2E), HubTheme.argb(status.color, 0x90));
        g.fill(right - w + 5, 11, right - w + 9, 15, status.color | 0xFF000000);
        g.drawString(this.font, label, right - w + 12, 9, status.color | 0xFF000000, false);
    }

    private void renderBottomBar(GuiGraphics g) {
        int y = this.height - BOTTOM + 3;
        Status status = status();
        TierTaggerConfig config = TierTagger.getManager().getConfig();
        int open = TierTagger.getQueue().openQueues().size();
        Component left = Component.translatable(status.footerKey);
        int x = 6;
        g.drawString(this.font, left, x, y, status.color | 0xFF000000, false);
        x += this.font.width(left) + 12;
        if (status == Status.LIVE) {
            Component queues = Component.translatable(open == 1 ? "vtl.hub.queues_open.one" : "vtl.hub.queues_open", open);
            g.drawString(this.font, queues, x, y, HubTheme.DIM, false);
            x += this.font.width(queues) + 12;
        }
        if (!compact) {
            Component region = Component.translatable(config.getQueueRegion().getTranslationKey());
            Component mode = "all".equalsIgnoreCase(config.getQueueMode()) ? Component.translatable("vtl.hub.all_kits")
                    : Component.literal(kitLabel(config.getQueueMode()));
            g.drawString(this.font, Component.translatable("vtl.hub.alerts", region, mode), x, y, HubTheme.DIM, false);
        }
        // The mod's name only; Mod Menu shows its version.
        Component brand = Component.translatable("vtl.hub.brand");
        g.drawString(this.font, brand, this.width - 6 - this.font.width(brand), y, HubTheme.DIM, false);
    }

    static String kitLabel(String modeId) {
        Kit kit = Kit.byId(modeId);
        return kit != null ? kit.label() : modeId;
    }

    enum Status {
        LIVE("vtl.hub.status.live", "vtl.hub.footer.live", 0x5DFF8A),
        CONNECTING("vtl.hub.status.connecting", "vtl.hub.footer.connecting", 0xFFC83D),
        OFF("vtl.hub.status.off", "vtl.hub.footer.off", 0x9D8FB0);

        final String key;
        final String footerKey;
        final int color;

        Status(String key, String footerKey, int color) {
            this.key = key;
            this.footerKey = footerKey;
            this.color = color;
        }
    }

    static Status status() {
        if (!TierTagger.getManager().getConfig().isQueueNotify()) return Status.OFF;
        return TierTagger.getQueue().isConnected() && TierTagger.getQueue().hello() != null ? Status.LIVE : Status.CONNECTING;
    }

    @Override
    public void tick() {
        if (tab != null) tab.tick();
        HubButton ticket = tabButtons.get(HubTab.Id.TICKET);
        if (ticket != null) ticket.setMessage(tabLabel(HubTab.Id.TICKET));
    }

    /** Tab names; TICKET shows how many messages are unread while another tab is open. */
    private Component tabLabel(HubTab.Id id) {
        if (id == HubTab.Id.TICKET && current != HubTab.Id.TICKET) {
            int unread = TierTagger.getQueue().ticketLog().snapshot().unread();
            if (unread > 0) return Component.translatable("vtl.hub.tab.ticketUnread", Math.min(unread, 99));
        }
        return Component.translatable(id.translationKey);
    }

    //? if >=1.21.9 {
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (tab != null && tab.keyPressed(event.key())) return true;
        return super.keyPressed(event);
    }
    //?} else {
    /*@Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (tab != null && tab.keyPressed(key)) return true;
        return super.keyPressed(key, scanCode, modifiers);
    }
    *///?}

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (tab != null && tab.mouseScrolled(mouseX, mouseY, horizontal, vertical)) return true;
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    //? if >=1.21.9 {
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        return tab != null && tab.mouseClicked(event.x(), event.y(), event.button());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (tab != null && tab.mouseDragged(event.x(), event.y(), dragX, dragY)) return true;
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = tab != null && tab.mouseReleased(event.x(), event.y());
        return super.mouseReleased(event) || handled;
    }
    //?} else {
    /*@Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        return tab != null && tab.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (tab != null && tab.mouseDragged(mouseX, mouseY, dragX, dragY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = tab != null && tab.mouseReleased(mouseX, mouseY);
        return super.mouseReleased(mouseX, mouseY, button) || handled;
    }
    *///?}

    @Override
    public void removed() {
        if (tab != null) tab.removed();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
