package com.kevin.tiertagger.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A screen that goes back to the one it was opened from (Esc, Done, Cancel). */
public abstract class ParentScreen extends Screen {
    protected final Screen parent;

    protected ParentScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    protected ParentScreen(String title, Screen parent) {
        this(Component.literal(title), parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }
}
