package com.kevin.tiertagger.hub;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;

/** One page of the VTL Hub. Its widgets go through the screen; it draws the rest itself. */
public interface HubTab {
    enum Id {
        PLAY("vtl.hub.tab.play"),
        TICKET("vtl.hub.tab.ticket"),
        PLAYERS("vtl.hub.tab.players"),
        SETTINGS("vtl.hub.tab.settings");

        public final String translationKey;

        Id(String translationKey) {
            this.translationKey = translationKey;
        }
    }

    /** Lay out and add widgets inside {@code x, y, w, h} (the area between the bars). */
    void init(VtlHubScreen screen, int x, int y, int w, int h);

    /** Drawn after the background, before the widgets. */
    default void renderBehind(GuiGraphics g, int mouseX, int mouseY, float delta) {}

    /** Drawn after the widgets. */
    default void renderFront(GuiGraphics g, int mouseX, int mouseY, float delta) {}

    /** Whether the centre stage (vault, glow) belongs behind this tab. */
    default boolean wantsStage() {
        return false;
    }

    default void tick() {}

    default boolean keyPressed(KeyEvent event) {
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        return false;
    }

    default boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseDragged(double mouseX, double mouseY, double dragX, double dragY) {
        return false;
    }

    default boolean mouseReleased(double mouseX, double mouseY) {
        return false;
    }

    /** The hub is closing or switching to another tab. */
    default void removed() {}
}
