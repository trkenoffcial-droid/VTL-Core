package com.kevin.tiertagger.hub;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?} elif >=1.21.2 {
/*import net.minecraft.client.renderer.RenderType;
*///?} else {
/*import com.mojang.blaze3d.systems.RenderSystem;
*///?}
import com.kevin.tiertagger.compat.GuiPose;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** VTL colours (ARGB) and the few drawing helpers every part of the hub shares. */
public final class HubTheme {
    private HubTheme() {}

    public static final int BG_TOP = 0xFF0A0610;
    public static final int BG_MID = 0xFF160B22;
    public static final int BG_BOTTOM = 0xFF0D0713;
    public static final int BAR = 0xF00A050E;
    public static final int PANEL = 0xD0120A1A;
    public static final int PANEL_SOLID = 0xF2120A1A;
    public static final int LINE = 0xFF3B2152;
    public static final int TEXT = 0xFFEFE7F7;
    public static final int DIM = 0xFF9D8FB0;
    public static final int VIOLET = 0xFFA35BFF;
    public static final int PINK = 0xFFFF4FA3;
    public static final int GOLD = 0xFFFFC83D;
    public static final int GREEN = 0xFF5DFF8A;
    public static final int RED = 0xFFFF5A5A;
    public static final int BLACK = 0xFF000000;

    public static final Identifier ICON = vtl("textures/gui/icon.png");
    public static final Identifier WORDMARK = vtl("textures/gui/wordmark.png");
    public static final Identifier GLOW = vtl("textures/gui/glow.png");

    public static Identifier vtl(String path) {
        return Identifier.fromNamespaceAndPath("vtl", path);
    }

    /** {@code rgb} with the given alpha (0-255). */
    public static int argb(int rgb, int alpha) {
        return (alpha & 0xFF) << 24 | (rgb & 0xFFFFFF);
    }

    /** Filled box with a 1 px outline. */
    public static void frame(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        g.fill(x, y, x + w, y + h, fill);
        g.renderOutline(x, y, w, h, border);
    }

    /** A whole texture (or any region of it) stretched to {@code w x h}. */
    public static void image(GuiGraphics g, Identifier texture, int x, int y, int w, int h, int texW, int texH) {
        //? if >=1.21.6 {
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, w, h, texW, texH, texW, texH);
        //?} elif >=1.21.2 {
        /*g.blit(RenderType::guiTextured, texture, x, y, 0, 0, w, h, texW, texH, texW, texH);
        *///?} else {
        /*image(g, texture, x, y, w, h, texW, texH, 0xFFFFFFFF);
        *///?}
    }

    public static void image(GuiGraphics g, Identifier texture, int x, int y, int w, int h, int texW, int texH, int color) {
        //? if >=1.21.6 {
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, w, h, texW, texH, texW, texH, color);
        //?} elif >=1.21.2 {
        /*g.blit(RenderType::guiTextured, texture, x, y, 0, 0, w, h, texW, texH, texW, texH, color);
        *///?} else {
        /*// before 1.21.2 a blit has no colour of its own: tint the shader, and blend for the alpha
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor((color >> 16 & 0xFF) / 255f, (color >> 8 & 0xFF) / 255f, (color & 0xFF) / 255f, (color >>> 24) / 255f);
        g.blit(texture, x, y, w, h, 0, 0, texW, texH, texW, texH);
        g.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        *///?}
    }

    /** A soft round glow of {@code color} (ARGB, alpha = strength) centred on {@code cx, cy}. */
    public static void glow(GuiGraphics g, int cx, int cy, int radiusX, int radiusY, int color) {
        image(g, GLOW, cx - radiusX, cy - radiusY, radiusX * 2, radiusY * 2, 128, 128, color);
    }

    /** The window's GUI scale (screen pixels per GUI pixel). */
    public static int guiScale() {
        return Math.max(1, (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
    }

    /** {@code scale} moved to the closest one where the pixel font lands on whole screen pixels. */
    public static float sharp(float scale) {
        return TextFit.nearest(scale, guiScale());
    }

    /** Text at {@code scale} (kept sharp), with shadow; {@code x, y} is its top-left corner. */
    public static void text(GuiGraphics g, Font font, Component text, int x, int y, int color, float scale) {
        text(g, font, text, x, y, color, scale, true);
    }

    public static void text(GuiGraphics g, Font font, Component text, int x, int y, int color, float scale, boolean shadow) {
        scale = sharp(scale);
        if (scale == 1f) {
            g.drawString(font, text, x, y, color, shadow);
            return;
        }
        GuiPose.push(g);
        GuiPose.translate(g, x, y);
        GuiPose.scale(g, scale, scale);
        g.drawString(font, text, 0, 0, color, shadow);
        GuiPose.pop(g);
    }

    /** Text at {@code scale} (kept sharp), centred on {@code cx}. */
    public static void centered(GuiGraphics g, Font font, Component text, int cx, int y, int color, float scale) {
        text(g, font, text, cx - width(font, text, scale) / 2, y, color, scale, true);
    }

    public static void centered(GuiGraphics g, Font font, Component text, int cx, int y, int color, float scale, boolean shadow) {
        text(g, font, text, cx - width(font, text, scale) / 2, y, color, scale, shadow);
    }

    /** {@code text} cut to {@code maxWidth} pixels, ending in "…" when it was cut. */
    public static String ellipsize(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    /** Width of {@code text} at {@code scale} (kept sharp). */
    public static int width(Font font, Component text, float scale) {
        return Math.round(font.width(text) * sharp(scale));
    }
}
