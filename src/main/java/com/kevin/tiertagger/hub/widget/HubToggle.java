package com.kevin.tiertagger.hub.widget;

import com.kevin.tiertagger.hub.HubTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/** An ON/OFF switch in VTL colours. */
public class HubToggle extends AbstractWidget {
    private boolean value;
    private final Consumer<Boolean> onChange;

    public HubToggle(int x, int y, int width, int height, Component label, boolean value, Consumer<Boolean> onChange) {
        super(x, y, width, height, label);
        this.value = value;
        this.onChange = onChange;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hot = isHoveredOrFocused();
        HubTheme.frame(g, x, y, w, h, hot ? 0xFF241536 : 0xFF1A1026, hot ? 0xFF7A4BB0 : HubTheme.LINE);
        int knobW = w / 2 - 2;
        int knobX = value ? x + w - knobW - 2 : x + 2;
        g.fillGradient(knobX, y + 2, knobX + knobW, y + h - 2, value ? 0xFF35C766 : 0xFF3A3046, value ? 0xFF1D8A44 : 0xFF2A2234);
        g.renderOutline(knobX, y + 2, knobW, h - 4, HubTheme.BLACK);
        var font = Minecraft.getInstance().font;
        Component text = value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
        HubTheme.centered(g, font, text, knobX + knobW / 2, y + (h - 8) / 2, value ? 0xFFFFFFFF : 0xFFB0A4C0, 1f);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        flip();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!this.active || !this.visible || !this.isFocused()) return false;
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_SPACE) {
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            flip();
            return true;
        }
        return false;
    }

    private void flip() {
        value = !value;
        onChange.accept(value);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
