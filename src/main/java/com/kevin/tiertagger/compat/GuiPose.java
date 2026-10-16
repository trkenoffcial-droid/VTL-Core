package com.kevin.tiertagger.compat;

import net.minecraft.client.gui.GuiGraphics;
//? if <1.21.6 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
*///?}
//? if <1.21.5
/*import org.lwjgl.opengl.GL11;*/

/**
 * 2D moves of the GUI drawing transform. From 1.21.6 the GUI has its own 2D matrix stack; before
 * that it was the 3D pose stack, where the same moves need a z of 0 (or a scale of 1).
 * {@link #layer} keeps the 1.21.6 drawing order on the older GUI.
 */
public final class GuiPose {
    private GuiPose() {}

    public static void push(GuiGraphics g) {
        //? if >=1.21.6 {
        g.pose().pushMatrix();
        //?} else {
        /*g.pose().pushPose();
        *///?}
    }

    public static void pop(GuiGraphics g) {
        //? if >=1.21.6 {
        g.pose().popMatrix();
        //?} else {
        /*g.pose().popPose();
        *///?}
    }

    public static void translate(GuiGraphics g, float x, float y) {
        //? if >=1.21.6 {
        g.pose().translate(x, y);
        //?} else {
        /*g.pose().translate(x, y, 0f);
        *///?}
    }

    public static void scale(GuiGraphics g, float x, float y) {
        //? if >=1.21.6 {
        g.pose().scale(x, y);
        //?} else {
        /*g.pose().scale(x, y, 1f);
        *///?}
    }

    /** Turns what is drawn next by {@code radians}, clockwise on screen. */
    public static void rotate(GuiGraphics g, float radians) {
        //? if >=1.21.6 {
        g.pose().rotate(radians);
        //?} else {
        /*g.pose().mulPose(Axis.ZP.rotation(radians));
        *///?}
    }

    /**
     * Makes what is drawn next cover what is drawn so far. From 1.21.6 the GUI draws in that order.
     * Before, it sorted by depth: an item sits 150 and a figure 50 in front of the rest, so a vault
     * drawn behind the figure covered it, and text drawn over the figure went behind it.
     */
    public static void layer(GuiGraphics g) {
        //? if <1.21.2 {
        /*g.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        *///?} elif <1.21.5 {
        /*g.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT);
        *///?} elif <1.21.6 {
        /*g.flush();
        RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(Minecraft.getInstance().getMainRenderTarget().getDepthTexture(), 1.0);
        *///?}
    }
}
