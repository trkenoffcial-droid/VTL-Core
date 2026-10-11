package com.kevin.tiertagger.hub.widget;

import com.kevin.tiertagger.hub.HubTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
//? if >=1.21.9
import net.minecraft.client.input.KeyEvent;
//? if >=1.21.9
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

/** A VTL-styled button. Drawn by the mod instead of Minecraft's grey button sprite. */
public class HubButton extends AbstractWidget {
    public enum Style {
        /** Violet, the main action. */
        PRIMARY(0xFF8B3DFF, 0xFF5B1FB8),
        /** Red, leaving. */
        DANGER(0xFFD63A5A, 0xFF8E1C34),
        /** Green, open ticket. */
        SUCCESS(0xFF2FBF5C, 0xFF16793A),
        /** Dark, secondary actions. */
        GHOST(0xFF2E1B40, 0xFF1F122C),
        /** Top bar tab. */
        TAB(0x00000000, 0x00000000);

        final int top;
        final int bottom;

        Style(int top, int bottom) {
            this.top = top;
            this.bottom = bottom;
        }
    }

    private Style style;
    private final Runnable onPress;
    private float textScale = 1f;
    private boolean selected;

    public HubButton(int x, int y, int width, int height, Component message, Style style, Runnable onPress) {
        super(x, y, width, height, message);
        this.style = style;
        this.onPress = onPress;
    }

    public HubButton textScale(float scale) {
        this.textScale = scale;
        return this;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        var font = Minecraft.getInstance().font;
        boolean hot = this.active && this.isHoveredOrFocused();

        if (style == Style.TAB) {
            if (selected) {
                g.fillGradient(x, y, x + w, y + h, 0xFF3A1A55, 0xFF24113A);
                g.renderOutline(x, y, w, h, 0xFF6D3AA0);
                g.fill(x + 1, y + h - 2, x + w - 1, y + h, HubTheme.PINK);
            } else if (hot) {
                g.fill(x, y, x + w, y + h, 0x30A35BFF);
            }
            int color = !this.active ? 0xFF5E516E : selected ? 0xFFFFFFFF : hot ? 0xFFE6D8F5 : 0xFFBFA9D6;
            HubTheme.centered(g, font, getMessage(), x + w / 2, y + (h - Math.round(8 * textScale)) / 2, color, textScale);
            return;
        }

        int top = style.top, bottom = style.bottom;
        if (!this.active) {
            top = 0xFF2A2233;
            bottom = 0xFF1E1826;
        } else if (hot) {
            top = brighten(top);
            bottom = brighten(bottom);
        }
        g.fillGradient(x, y, x + w, y + h, top, bottom);
        g.renderOutline(x, y, w, h, HubTheme.BLACK);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x30FFFFFF);
        g.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, 0x40000000);
        if (hot && style == Style.PRIMARY) g.renderOutline(x - 1, y - 1, w + 2, h + 2, 0x80C27BFF);

        int textColor = this.active ? 0xFFFFFFFF : 0xFF7A6E88;
        int textHeight = Math.round(8 * textScale);
        HubTheme.centered(g, font, getMessage(), x + w / 2, y + (h - textHeight) / 2, textColor, textScale);
    }

    private static int brighten(int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, gr = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r = Math.min(255, r + 24);
        gr = Math.min(255, gr + 24);
        b = Math.min(255, b + 24);
        return a << 24 | r << 16 | gr << 8 | b;
    }

    //? if >=1.21.9 {
    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
    //?} else {
    /*@Override
    public void onClick(double mouseX, double mouseY) {
    *///?}
        if (this.active) this.onPress.run();
    }

    //? if >=1.21.9 {
    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
    //?} else {
    /*@Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
    *///?}
        if (!this.active || !this.visible || !this.isFocused()) return false;
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_SPACE || key == InputConstants.KEY_NUMPADENTER) {
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            this.onPress.run();
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
