package com.kevin.tiertagger.hub.widget;

import com.kevin.tiertagger.hub.HubState.KitView;
import com.kevin.tiertagger.hub.HubState.Status;
import com.kevin.tiertagger.hub.HubTheme;
import com.kevin.tiertagger.hub.Kit;
import com.kevin.tiertagger.hub.TextFit;
import com.kevin.tiertagger.queue.QueueMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The row of eight kit cards. The selected card is larger and in colour; kits with an open queue
 * are in colour with their region and line length; closed kits are grey.
 *
 * <p>Card art lives at {@code vtl:textures/gui/kit/<id>.png} (colour) and {@code <id>_gray.png};
 * without it the card shows the kit's item instead.</p>
 */
public class KitCarousel extends AbstractWidget {
    private static final Map<Kit, ItemStack> ICONS = new EnumMap<>(Map.of(
            Kit.AXE, new ItemStack(Items.NETHERITE_AXE),
            Kit.VANILLA, new ItemStack(Items.END_CRYSTAL),
            Kit.MACE, new ItemStack(Items.MACE),
            Kit.SWORD, new ItemStack(Items.NETHERITE_SWORD),
            Kit.NETHOP, new ItemStack(Items.TOTEM_OF_UNDYING),
            Kit.POT, new ItemStack(Items.SPLASH_POTION),
            Kit.UHC, new ItemStack(Items.GOLDEN_APPLE),
            Kit.SMP, new ItemStack(Items.BOW)));
    /** Card art is 128 x 176 (ratio 0.727). */
    private static final int ART_W = 128, ART_H = 176;
    private static final Map<Identifier, Boolean> ART_PRESENT = new java.util.HashMap<>();
    /** Below this text scale the kit name drops bold instead of shrinking further. */
    private static final float MIN_READABLE = 0.5f;

    private List<KitView> views = List.of();
    private int selected;
    private final IntConsumer onSelect;

    public KitCarousel(int x, int y, int width, int height, IntConsumer onSelect) {
        super(x, y, width, height, Component.translatable("vtl.play.kits"));
        this.onSelect = onSelect;
    }

    public void setViews(List<KitView> views, int selected) {
        this.views = views;
        this.selected = selected;
    }

    public static ItemStack icon(Kit kit) {
        return ICONS.get(kit);
    }

    // --- geometry ---

    private int gap() {
        return Math.max(2, getWidth() / 160);
    }

    private int cardW() {
        int n = Math.max(1, views.size());
        int byWidth = (getWidth() - gap() * (n - 1) - 16) / n;
        int byHeight = (int) (getHeight() * 0.80f * ART_W / ART_H);
        return Math.max(28, Math.min(byWidth, byHeight));
    }

    private int cardH() {
        return cardW() * ART_H / ART_W;
    }

    private int cardX(int i) {
        int n = views.size();
        int total = n * cardW() + (n - 1) * gap();
        return getX() + (getWidth() - total) / 2 + i * (cardW() + gap());
    }

    private int cardY() {
        return getY() + getHeight() - cardH();
    }

    /** Index of the card under {@code mouseX, mouseY}, or -1. */
    public int cardAt(double mouseX, double mouseY) {
        for (int i = 0; i < views.size(); i++) {
            int x = cardX(i), y = cardY();
            if (mouseX >= x && mouseX < x + cardW() && mouseY >= y - cardH() / 6 && mouseY < y + cardH()) return i;
        }
        return -1;
    }

    // --- drawing ---

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (views.isEmpty()) return;
        int hovered = isHovered() ? cardAt(mouseX, mouseY) : -1;
        for (int i = 0; i < views.size(); i++) {
            if (i != selected) drawCard(g, i, cardX(i), cardY(), cardW(), cardH(), false, i == hovered);
        }
        // the selected card: 18 % larger, lifted, glowing, on top
        int w = Math.round(cardW() * 1.18f), h = Math.round(cardH() * 1.18f);
        int x = cardX(selected) - (w - cardW()) / 2;
        int y = cardY() - (h - cardH()) - cardH() / 14;
        drawCard(g, selected, x, y, w, h, true, false);
    }

    private void drawCard(GuiGraphics g, int i, int x, int y, int w, int h, boolean isSelected, boolean hovered) {
        KitView view = views.get(i);
        Kit kit = view.kit();
        var font = Minecraft.getInstance().font;
        boolean closed = view.status() == Status.CLOSED;
        int color = kit.color();

        if (isSelected) {
            HubTheme.glow(g, x + w / 2, y + h / 2, w, h * 3 / 4, HubTheme.argb(color, 0x90));
        }
        g.fillGradient(x, y, x + w, y + h, HubTheme.argb(darken(color, 0.30f), 0xFF), 0xFF140B1D);

        int tagH = Math.max(10, h / 8);
        Identifier art = HubTheme.vtl("textures/gui/kit/" + kit.id() + (closed && !isSelected ? "_gray" : "") + ".png");
        if (present(art)) {
            HubTheme.image(g, art, x + 1, y + 1, w - 2, h - tagH - 1, ART_W, ART_H);
        } else {
            int size = w * 56 / 100;
            g.pose().pushMatrix();
            g.pose().translate(x + (w - size) / 2f, y + h * 0.22f);
            g.pose().scale(size / 16f, size / 16f);
            g.renderItem(ICONS.get(kit), 0, 0);
            g.pose().popMatrix();
        }

        // dim what is not the focus
        if (!isSelected) {
            int shade = closed ? 0xB0100A16 : hovered ? 0x10000000 : 0x55000000;
            g.fill(x, y, x + w, y + h, shade);
        }

        // kit tag: bold when it fits, plain when bold is too wide; always inside the card
        boolean greyTag = closed && !isSelected;
        g.fill(x, y + h - tagH, x + w, y + h, greyTag ? 0xFF4A4452 : HubTheme.argb(color, 0xFF));
        g.fill(x, y + h - tagH - 1, x + w, y + h - tagH, HubTheme.BLACK);
        int gs = HubTheme.guiScale();
        float tagMax = Math.min(1.5f, (tagH - 2) / 8f);
        String label = kit.label().toUpperCase(Locale.ROOT);
        Component tag = Component.literal(label).withStyle(s -> s.withBold(true));
        float tagScale = TextFit.fit(font.width(tag), w - 4, tagMax, gs);
        if (tagScale < MIN_READABLE) {
            tag = Component.literal(label);
            tagScale = Math.max(TextFit.fit(font.width(tag), w - 4, tagMax, gs), 1f / gs);
        }
        HubTheme.centered(g, font, tag, x + w / 2, y + h - tagH + (tagH - Math.round(8 * tagScale)) / 2,
                greyTag ? 0xFFBDB3C9 : 0xFF120818, tagScale, false);

        // status line at the top, on a dark fade so it reads over the art
        g.fillGradient(x + 1, y + 1, x + w - 1, y + 1 + Math.max(12, h / 4), 0xB0000000, 0x00000000);
        float statusMax = Math.min(isSelected ? 1.25f : 1f, Math.max(isSelected ? 0.85f : 0.7f, w / 70f));
        fitted(g, font, statusText(view), x + w / 2, y + 3, w - 4, statusMax, statusColor(view));

        g.renderOutline(x, y, w, h, isSelected ? 0xFFFFFFFF : HubTheme.BLACK);
        if (isSelected) g.renderOutline(x - 1, y - 1, w + 2, h + 2, HubTheme.argb(color, 0xFF));
    }

    /**
     * {@code text} centred on {@code cx} inside {@code maxW}, at the largest sharp scale (up to
     * {@code maxScale}) where it fits on at most two lines.
     */
    private static void fitted(GuiGraphics g, Font font, Component text, int cx, int y, int maxW, float maxScale, int color) {
        int gs = HubTheme.guiScale();
        float scale = 1f / gs;
        List<FormattedCharSequence> lines = font.split(text, Math.max(1, maxW * gs));
        for (int px = Math.round(TextFit.snap(maxScale, gs) * gs); px >= 1; px--) {
            float s = px / (float) gs;
            List<FormattedCharSequence> split = font.split(text, Math.max(1, (int) (maxW / s)));
            if (split.size() <= 2) {
                lines = split;
                scale = s;
                break;
            }
        }
        int lineH = Math.round(9 * scale);
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            int width = Math.round(font.width(line) * scale);
            g.pose().pushMatrix();
            g.pose().translate(cx - width / 2, y + i * lineH);
            g.pose().scale(scale, scale);
            g.drawString(font, line, 0, 0, color, true);
            g.pose().popMatrix();
        }
    }

    private static Component statusText(KitView v) {
        return switch (v.status()) {
            case CLOSED -> Component.translatable("vtl.play.card.closed");
            case NOT_OPEN_TO_YOU -> Component.translatable("vtl.play.card.notOpen");
            case WAITING -> Component.translatable("vtl.play.card.position", v.myPosition() == null ? "?" : v.myPosition());
            case TESTING -> Component.translatable("vtl.play.card.testing");
            case OPEN -> Component.translatable("vtl.play.card.open", QueueMessages.regionLabel(v.chosen().region()), v.waiting());
        };
    }

    private static int statusColor(KitView v) {
        return switch (v.status()) {
            case CLOSED -> 0xFF8A7D99;
            case NOT_OPEN_TO_YOU -> 0xFFFFAA55;
            case WAITING -> HubTheme.GOLD;
            case TESTING, OPEN -> HubTheme.GREEN;
        };
    }

    private static int darken(int rgb, float factor) {
        int r = (int) (((rgb >> 16) & 0xFF) * factor), gr = (int) (((rgb >> 8) & 0xFF) * factor), b = (int) ((rgb & 0xFF) * factor);
        return r << 16 | gr << 8 | b;
    }

    private static boolean present(Identifier id) {
        return ART_PRESENT.computeIfAbsent(id, i -> Minecraft.getInstance().getResourceManager().getResource(i).isPresent());
    }

    // --- input ---

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        int i = cardAt(event.x(), event.y());
        if (i >= 0) onSelect.accept(i);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
