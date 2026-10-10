package com.kevin.tiertagger.hub.widget;

import com.kevin.tiertagger.hub.HubTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** "‹ value ›": click to go to the next value; arrow keys step both ways when focused. */
public class HubChoice<T> extends AbstractWidget {
    private final List<T> values;
    private final Function<T, Component> label;
    private final Consumer<T> onChange;
    private int index;

    public HubChoice(int x, int y, int width, int height, Component name, List<T> values, T current,
                     Function<T, Component> label, Consumer<T> onChange) {
        super(x, y, width, height, name);
        this.values = List.copyOf(values);
        this.label = label;
        this.onChange = onChange;
        this.index = Math.max(0, this.values.indexOf(current));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hot = this.active && isHoveredOrFocused();
        HubTheme.frame(g, x, y, w, h, hot ? 0xFF241536 : 0xFF1A1026, hot ? 0xFF7A4BB0 : HubTheme.LINE);
        var font = Minecraft.getInstance().font;
        int ty = y + (h - 8) / 2;
        g.drawString(font, "‹", x + 4, ty, HubTheme.DIM, false);
        g.drawString(font, "›", x + w - 8, ty, HubTheme.DIM, false);
        if (values.isEmpty()) return;
        Component text = label.apply(values.get(index));
        String clipped = font.plainSubstrByWidth(text.getString(), w - 24);
        Component shown = clipped.length() < text.getString().length() ? Component.literal(clipped + "…") : text;
        HubTheme.centered(g, font, shown, x + w / 2, ty, this.active ? HubTheme.TEXT : 0xFF7A6E88, 1f);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        step(event.x() < getX() + getWidth() / 3.0 ? -1 : 1);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!this.active || !this.visible || !this.isFocused()) return false;
        switch (event.key()) {
            case GLFW.GLFW_KEY_LEFT -> step(-1);
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_SPACE -> step(1);
            default -> {
                return false;
            }
        }
        this.playDownSound(Minecraft.getInstance().getSoundManager());
        return true;
    }

    private void step(int direction) {
        if (values.isEmpty()) return;
        index = Math.floorMod(index + direction, values.size());
        onChange.accept(values.get(index));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
