package com.kevin.tiertagger.hub;

import com.kevin.tiertagger.TierCache;
import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.config.TierTaggerConfig;
import com.kevin.tiertagger.config.TierTaggerConfig.HighestMode;
import com.kevin.tiertagger.config.TierTaggerConfig.HudCorner;
import com.kevin.tiertagger.config.TierTaggerConfig.QueueRegion;
import com.kevin.tiertagger.hub.widget.HubButton;
import com.kevin.tiertagger.hub.widget.HubChoice;
import com.kevin.tiertagger.hub.widget.HubSlider;
import com.kevin.tiertagger.hub.widget.HubToggle;
import com.kevin.tiertagger.model.GameMode;
import com.kevin.tiertagger.model.TierList;
import com.kevin.tiertagger.queue.QueueLinkScreen;
import com.kevin.tiertagger.queue.QueueLinker;
import com.kevin.tiertagger.queue.QueueTokenStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Every setting of the mod, in groups, saved the moment it changes. */
public class SettingsTab implements HubTab {
    private static final int ROW = 22;
    private static final int HEADER = 20;
    private static final Pattern HEX = Pattern.compile("#?[0-9A-Fa-f]{6}");

    /** One line of a column: a section title, or a label with its control. */
    private static final class Entry {
        final int column;
        final Component label;
        final boolean header;
        AbstractWidget widget;
        Integer swatch;
        int contentY;

        Entry(int column, Component label, boolean header) {
            this.column = column;
            this.label = label;
            this.header = header;
        }
    }

    private VtlHubScreen screen;
    private final List<Entry> entries = new ArrayList<>();
    private int areaX, areaY, areaW, areaH;
    private final int[] colX = new int[2];
    private int colW, controlW;
    private int contentH;
    private double scroll;

    private EditBox urlBox;
    private String urlSeen;
    private long urlEditedAt;

    private TierTaggerConfig config() {
        return TierTagger.getManager().getConfig();
    }

    private void save() {
        TierTagger.getManager().saveConfig();
    }

    @Override
    public void init(VtlHubScreen screen, int x, int y, int w, int h) {
        this.screen = screen;
        entries.clear();
        areaX = x + 12;
        areaY = y + 8;
        areaW = w - 24;
        areaH = h - 16;
        boolean two = areaW >= 440;
        colW = two ? (areaW - 16) / 2 : Math.min(areaW, 380);
        colX[0] = two ? areaX : areaX + (areaW - colW) / 2;
        colX[1] = two ? areaX + colW + 16 : colX[0];
        controlW = Math.max(100, Math.min(170, colW * 48 / 100));
        int right = two ? 1 : 0;

        buildDisplay(0);
        buildColors(0);
        buildQueue(right);
        buildHub(right);
        buildDiscord(right);

        int[] heights = new int[2];
        for (Entry e : entries) {
            e.contentY = heights[e.column];
            heights[e.column] += e.header ? HEADER : ROW;
        }
        contentH = Math.max(heights[0], heights[1]);
        scroll = Math.min(scroll, maxScroll());
        layout();
    }

    // --- sections ---

    private void buildDisplay(int c) {
        TierTaggerConfig cfg = config();
        header(c, "vtl.settings.display");
        toggle(c, "vtl.settings.enabled", cfg.isEnabled(), cfg::setEnabled);

        List<GameMode> modes = TierCache.getGamemodes().isEmpty() ? List.of(GameMode.NONE) : TierCache.getGamemodes();
        HubChoice<GameMode> gamemode = choice(c, "vtl.settings.gamemode", modes, cfg.getGameMode(),
                m -> Component.literal(m.title()), m -> cfg.setGameMode(m.id()));
        gamemode.active = !cfg.getGameMode().isNone();

        toggle(c, "vtl.settings.retired", cfg.isShowRetired(), cfg::setShowRetired);
        choice(c, "vtl.settings.highest", Arrays.asList(HighestMode.values()), cfg.getHighestMode(),
                m -> Component.translatable(m.getTranslationKey()), cfg::setHighestMode);
        toggle(c, "vtl.settings.icons", cfg.isShowIcons(), cfg::setShowIcons);
        toggle(c, "vtl.settings.playerList", cfg.isPlayerList(), cfg::setPlayerList);

        TierList currentList = TierList.findByUrl(cfg.getApiUrl()).orElse(TierList.VIET_TIERLIST);
        choice(c, "vtl.settings.tierlist", Arrays.asList(TierList.values()), currentList,
                t -> Component.literal(t.getName()), t -> {
                    cfg.setApiUrl(t.getUrl());
                    save();
                    TierCache.init();
                    SystemToast.add(Minecraft.getInstance().getToastManager(), new SystemToast.SystemToastId(),
                            Component.translatable("vtl.settings.tierlist.changed", t.getName()), Component.translatable("vtl.settings.tierlist.reloading"));
                });
        button(c, "vtl.settings.clearCache", HubButton.Style.GHOST, TierCache::clearCache);
    }

    private void buildColors(int c) {
        TierTaggerConfig cfg = config();
        header(c, "vtl.settings.colors");
        Map<String, Integer> colors = cfg.getTierColors();
        List<String> tiers = new ArrayList<>(colors.keySet());
        tiers.sort(Comparator.<String, Character>comparing(t -> t.length() > 2 ? t.charAt(2) : ' ').thenComparing(t -> t.charAt(0)));
        for (String tier : tiers) {
            color(c, Component.literal(tier), colors.get(tier), v -> colors.put(tier, v));
        }
        color(c, Component.translatable("vtl.settings.retiredColor"), cfg.getRetiredColor(), cfg::setRetiredColor);
    }

    private void buildQueue(int c) {
        TierTaggerConfig cfg = config();
        header(c, "vtl.settings.queue");
        toggle(c, "vtl.settings.queueNotify", cfg.isQueueNotify(), v -> {
            cfg.setQueueNotify(v);
            save();
            TierTagger.getQueue().restart();
        });
        choice(c, "vtl.settings.region", List.copyOf(EnumSet.allOf(QueueRegion.class)), cfg.getQueueRegion(),
                r -> Component.translatable(r.getTranslationKey()), cfg::setQueueRegion);
        choice(c, "vtl.settings.mode", TierTaggerConfig.QUEUE_MODES, cfg.getQueueMode(),
                m -> "all".equals(m) ? Component.translatable("vtl.hub.all_kits") : Component.literal(VtlHubScreen.kitLabel(m)), cfg::setQueueMode);
        toggle(c, "vtl.settings.sound", cfg.isQueueSound(), cfg::setQueueSound);
        toggle(c, "vtl.settings.onlyJoinable", cfg.isQueueOnlyJoinable(), cfg::setQueueOnlyJoinable);
    }

    private void buildHub(int c) {
        TierTaggerConfig cfg = config();
        header(c, "vtl.settings.hub");
        toggle(c, "vtl.settings.hud", cfg.isHudEnabled(), cfg::setHudEnabled);
        toggle(c, "vtl.settings.effects", cfg.isHubEffects(), cfg::setHubEffects);
        choice(c, "vtl.settings.hudCorner", Arrays.asList(HudCorner.values()), cfg.getHudCorner(),
                k -> Component.translatable("vtl.settings.hudCorner." + k.name().toLowerCase(java.util.Locale.ROOT)), cfg::setHudCorner);
        Entry volume = row(c, Component.translatable("vtl.settings.volume"));
        volume.widget = screen.add(new HubSlider(0, 0, controlW, 16, 0, 100, cfg.getAlertVolume(),
                v -> Component.literal(v + "%"), v -> {
                    cfg.setAlertVolume(v);
                    save();
                }));
        toggle(c, "vtl.settings.threeDItems", ItemPack.isActive(), v -> {
            cfg.setThreeDItems(v);
            save();
            ItemPack.apply(v);
        });
    }

    private void buildDiscord(int c) {
        header(c, "vtl.settings.discord");
        Entry status = row(c, Component.translatable("vtl.settings.linkStatus"));
        boolean linked = QueueTokenStore.isLinked();
        Entry linkRow = row(c, Component.empty());
        linkRow.widget = screen.add(new HubButton(0, 0, controlW, 16,
                Component.translatable(linked ? "vtl.settings.relink" : "vtl.settings.link"), HubButton.Style.PRIMARY,
                () -> Minecraft.getInstance().setScreen(new QueueLinkScreen(screen))));
        if (linked) {
            Entry unlinkRow = row(c, Component.empty());
            unlinkRow.widget = screen.add(new HubButton(0, 0, controlW, 16, Component.translatable("vtl.settings.unlink"),
                    HubButton.Style.DANGER, () -> {
                        QueueLinker.unlink();
                        screen.refresh();
                    }));
        }
        Entry url = row(c, Component.translatable("vtl.settings.server"));
        urlBox = new EditBox(Minecraft.getInstance().font, 0, 0, controlW, 16, Component.translatable("vtl.settings.server"));
        urlBox.setMaxLength(256);
        urlBox.setValue(config().getQueueUrl());
        urlSeen = urlBox.getValue();
        urlBox.setResponder(v -> {
            urlEditedAt = Util.getMillis();
            urlBox.setTextColor(validUrl(v) ? 0xFFE0E0E0 : 0xFFFF5555);
        });
        url.widget = screen.add(urlBox);
    }

    private static boolean validUrl(String url) {
        return url.startsWith("ws://") || url.startsWith("wss://");
    }

    @Override
    public void tick() {
        // The server URL applies one second after the last keystroke, so typing does not
        // reconnect the socket on every character.
        if (urlBox == null) return;
        String v = urlBox.getValue();
        if (!v.equals(urlSeen) && validUrl(v) && Util.getMillis() - urlEditedAt > 1000) {
            urlSeen = v;
            if (!v.equals(config().getQueueUrl())) {
                config().setQueueUrl(v);
                config().setQueueLinkUrl(TierTaggerConfig.linkUrlFor(v));
                save();
                TierTagger.getQueue().restart();
            }
        }
    }

    // --- builders ---

    private void header(int column, String key) {
        entries.add(new Entry(column, Component.translatable(key), true));
    }

    private Entry row(int column, Component label) {
        Entry e = new Entry(column, label, false);
        entries.add(e);
        return e;
    }

    private void toggle(int column, String key, boolean value, Consumer<Boolean> setter) {
        Entry e = row(column, Component.translatable(key));
        e.widget = screen.add(new HubToggle(0, 0, 64, 16, Component.translatable(key), value, v -> {
            setter.accept(v);
            save();
        }));
    }

    private <T> HubChoice<T> choice(int column, String key, List<T> values, T current,
                                    java.util.function.Function<T, Component> label, Consumer<T> setter) {
        Entry e = row(column, Component.translatable(key));
        HubChoice<T> choice = new HubChoice<>(0, 0, controlW, 16, Component.translatable(key), values, current, label, v -> {
            setter.accept(v);
            save();
        });
        e.widget = screen.add(choice);
        return choice;
    }

    private void button(int column, String key, HubButton.Style style, Runnable action) {
        Entry e = row(column, Component.empty());
        e.widget = screen.add(new HubButton(0, 0, controlW, 16, Component.translatable(key), style, action));
    }

    private void color(int column, Component label, int value, Consumer<Integer> setter) {
        Entry e = row(column, label);
        e.swatch = value;
        EditBox box = new EditBox(Minecraft.getInstance().font, 0, 0, 70, 16, label);
        box.setMaxLength(7);
        box.setValue(String.format("#%06X", value & 0xFFFFFF));
        box.setResponder(v -> {
            boolean ok = HEX.matcher(v).matches();
            box.setTextColor(ok ? 0xFFE0E0E0 : 0xFFFF5555);
            if (!ok) return;
            int rgb = Integer.parseInt(v.startsWith("#") ? v.substring(1) : v, 16);
            e.swatch = rgb;
            setter.accept(rgb);
            save();
        });
        e.widget = screen.add(box);
    }

    // --- layout, scrolling, drawing ---

    private int maxScroll() {
        return Math.max(0, contentH - areaH);
    }

    private void layout() {
        for (Entry e : entries) {
            if (e.widget == null) continue;
            int y = areaY + e.contentY - (int) scroll;
            int h = e.widget.getHeight();
            e.widget.setX(colX[e.column] + colW - e.widget.getWidth() - 6);
            e.widget.setY(y + (ROW - h) / 2);
            e.widget.visible = y >= areaY && y + ROW <= areaY + areaH;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (maxScroll() == 0) return false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - vertical * ROW));
        layout();
        return true;
    }

    @Override
    public void renderBehind(GuiGraphics g, int mouseX, int mouseY, float delta) {
        var font = Minecraft.getInstance().font;
        boolean two = colX[1] != colX[0];
        for (int c = 0; c < (two ? 2 : 1); c++) {
            HubTheme.frame(g, colX[c] - 6, areaY - 4, colW + 12, areaH + 8, 0xB0100818, 0xFF2C1940);
        }
        g.enableScissor(areaX - 6, areaY, areaX + areaW + 6, areaY + areaH);
        for (Entry e : entries) {
            int y = areaY + e.contentY - (int) scroll;
            if (y + (e.header ? HEADER : ROW) < areaY || y > areaY + areaH) continue;
            int x = colX[e.column];
            if (e.header) {
                g.drawString(font, e.label, x, y + 8, HubTheme.GOLD, true);
                g.fill(x, y + 18, x + colW, y + 19, 0xFF3B2152);
                continue;
            }
            int ty = y + (ROW - 8) / 2;
            if (e.swatch != null) {
                g.fill(x, ty - 1, x + 10, ty + 9, HubTheme.BLACK);
                g.fill(x + 1, ty, x + 9, ty + 8, 0xFF000000 | e.swatch);
                g.drawString(font, e.label, x + 14, ty, HubTheme.TEXT, false);
            } else if (e.widget == null && !e.label.getString().isEmpty()) {
                g.drawString(font, e.label, x, ty, HubTheme.DIM, false);
                Component status = QueueLinkScreen.linkStatus();
                int sw = font.width(status);
                g.drawString(font, status, x + colW - sw - 6, ty, QueueTokenStore.isLinked() ? HubTheme.GREEN : HubTheme.GOLD, false);
            } else {
                g.drawString(font, e.label, x, ty, HubTheme.TEXT, false);
            }
        }
        g.disableScissor();
    }

    @Override
    public void renderFront(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (maxScroll() == 0) return;
        int trackX = areaX + areaW + 2;
        g.fill(trackX, areaY, trackX + 2, areaY + areaH, 0x40FFFFFF);
        int thumbH = Math.max(16, areaH * areaH / contentH);
        int thumbY = areaY + (int) ((areaH - thumbH) * (scroll / maxScroll()));
        g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, HubTheme.VIOLET);
    }
}
