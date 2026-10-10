package com.kevin.tiertagger.hub.widget;

import com.kevin.tiertagger.hub.HubTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** A whole-number slider in VTL colours. */
public class HubSlider extends AbstractSliderButton {
    private final int min;
    private final int max;
    private final IntFunction<Component> label;
    private final IntConsumer onChange;

    public HubSlider(int x, int y, int width, int height, int min, int max, int value,
                     IntFunction<Component> label, IntConsumer onChange) {
        super(x, y, width, height, Component.empty(), (value - min) / (double) (max - min));
        this.min = min;
        this.max = max;
        this.label = label;
        this.onChange = onChange;
        updateMessage();
    }

    private int current() {
        return (int) Math.round(min + this.value * (max - min));
    }

    @Override
    protected void updateMessage() {
        this.setMessage(label.apply(current()));
    }

    @Override
    protected void applyValue() {
        onChange.accept(current());
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hot = isHoveredOrFocused();
        HubTheme.frame(g, x, y, w, h, hot ? 0xFF241536 : 0xFF1A1026, hot ? 0xFF7A4BB0 : HubTheme.LINE);
        int fillW = (int) Math.round((w - 4) * this.value);
        g.fillGradient(x + 2, y + 2, x + 2 + fillW, y + h - 2, 0xFF8B3DFF, 0xFF5B1FB8);
        int knob = x + 2 + fillW;
        g.fill(knob - 2, y + 1, knob + 2, y + h - 1, 0xFFFFFFFF);
        HubTheme.centered(g, Minecraft.getInstance().font, getMessage(), x + w / 2, y + (h - 8) / 2, HubTheme.TEXT, 1f);
    }
}
