package com.kevin.tiertagger.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Toasts, whose manager was a ToastComponent reached with getToasts() before 1.21.2.
 */
public final class Toasts {
    private Toasts() {}

    /** Removes every toast on screen. */
    public static void clear() {
        //? if >=1.21.2 {
        Minecraft.getInstance().getToastManager().clear();
        //?} else {
        /*Minecraft.getInstance().getToasts().clear();
        *///?}
    }

    /** Shows a system toast. */
    public static void system(Component title, Component body) {
        // A fresh id per toast: SystemToastId doesn't override equals, and reusing one would make
        // two events close together overwrite each other.
        //? if >=1.21.2 {
        SystemToast.add(Minecraft.getInstance().getToastManager(), new SystemToast.SystemToastId(), title, body);
        //?} else {
        /*SystemToast.add(Minecraft.getInstance().getToasts(), new SystemToast.SystemToastId(), title, body);
        *///?}
    }
}
