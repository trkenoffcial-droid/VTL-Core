package com.kevin.tiertagger.menu;

import com.kevin.tiertagger.hub.HubTab;
import com.kevin.tiertagger.hub.HubTheme;
import com.kevin.tiertagger.hub.VtlHubScreen;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;

/**
 * Puts the VTL icon button on the title screen (next to Multiplayer) and in the pause menu
 * (next to Back to Game), so the hub is one click away with or without a world.
 */
public final class MenuButtons {
    private MenuButtons() {}

    private static final int SIZE = 20;

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen) place(screen, "menu.multiplayer", width / 2 + 104, height / 4 + 72);
            else if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) place(screen, "menu.returnToGame", width / 2 + 106, height / 4 + 8);
        });
    }

    /** Right of the button labelled {@code anchorKey}; at {@code fallbackX, fallbackY} if it is not there. */
    private static void place(Screen screen, String anchorKey, int fallbackX, int fallbackY) {
        List<AbstractWidget> buttons = Screens.getButtons(screen);
        int x = fallbackX, y = fallbackY;
        for (AbstractWidget b : buttons) {
            if (b.getMessage().getContents() instanceof TranslatableContents t && anchorKey.equals(t.getKey())) {
                x = b.getX() + b.getWidth() + 4;
                y = b.getY();
                break;
            }
        }
        // Another mod may already sit there: step right until the spot is free.
        for (int tries = 0; tries < 4 && occupied(buttons, x, y); tries++) x += SIZE + 4;
        buttons.add(new IconButton(x, y));
    }

    private static boolean occupied(List<AbstractWidget> buttons, int x, int y) {
        for (AbstractWidget b : buttons) {
            if (x < b.getX() + b.getWidth() && x + SIZE > b.getX() && y < b.getY() + b.getHeight() && y + SIZE > b.getY()) return true;
        }
        return false;
    }

    /** 20x20 button showing the VTL icon. */
    static final class IconButton extends AbstractWidget {
        IconButton(int x, int y) {
            super(x, y, SIZE, SIZE, Component.translatable("vtl.hub.title"));
            this.setTooltip(Tooltip.create(Component.translatable("vtl.hub.title")));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY();
            if (isHoveredOrFocused()) HubTheme.glow(g, x + SIZE / 2, y + SIZE / 2, SIZE, SIZE, HubTheme.argb(0xFFC83D, 0x90));
            g.fill(x, y, x + SIZE, y + SIZE, HubTheme.BLACK);
            HubTheme.image(g, HubTheme.ICON, x + 1, y + 1, SIZE - 2, SIZE - 2, 128, 128);
            g.renderOutline(x, y, SIZE, SIZE, isHoveredOrFocused() ? 0xFFFFFFFF : HubTheme.GOLD);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            VtlHubScreen.open(HubTab.Id.PLAY);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }
}
