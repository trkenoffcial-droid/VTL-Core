package com.kevin.tiertagger.hub;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

/**
 * The hub's backdrop: a dark violet amethyst cave. Walls and floor are tiled from Minecraft's own
 * block textures and crystals are the amethyst cluster sprite, so nothing here is an extra asset.
 * The vault behind the player is drawn by the PLAY tab, which knows where the player stands.
 */
public final class HubBackground {
    private HubBackground() {}

    private static final Identifier WALL = Identifier.withDefaultNamespace("textures/block/deepslate_tiles.png");
    private static final Identifier FLOOR = Identifier.withDefaultNamespace("textures/block/cobbled_deepslate.png");
    private static final Identifier CRYSTAL = Identifier.withDefaultNamespace("textures/block/amethyst_cluster.png");

    /**
     * @param top     height of the top bar (the cave starts under it)
     * @param bottom  height of the bottom bar
     * @param time    seconds since the hub opened, for the gentle motion
     * @param stage   whether to draw the centre stage glow behind the player
     */
    public static void render(GuiGraphics g, int width, int height, int top, int bottom, float time, boolean stage) {
        int mid = height * 55 / 100;
        g.fillGradient(0, 0, width, mid, HubTheme.BG_TOP, HubTheme.BG_MID);
        g.fillGradient(0, mid, width, height, HubTheme.BG_MID, HubTheme.BG_BOTTOM);

        // light: violet behind the stage, a faint pink upper left
        HubTheme.glow(g, width / 2, height * 44 / 100, width * 32 / 100, height * 40 / 100, HubTheme.argb(0xA35BFF, 0x8C));
        HubTheme.glow(g, width * 18 / 100, height * 28 / 100, width * 20 / 100, height * 22 / 100, HubTheme.argb(0xFF4FA3, 0x2A));

        int tile = Math.max(16, height / 14);
        int floorY = height - bottom - Math.max(tile * 2, height * 15 / 100);

        // walls: two stepped columns on each side, tiled and darkened
        wall(g, 0, top, width * 12 / 100, floorY - top, tile);
        wall(g, width * 12 / 100, top + (floorY - top) * 22 / 100, width * 5 / 100, (floorY - top) * 40 / 100, tile);
        wall(g, width - width * 11 / 100, top, width * 11 / 100, floorY - top, tile);
        wall(g, width - width * 17 / 100, top + (floorY - top) * 30 / 100, width * 6 / 100, (floorY - top) * 30 / 100, tile);

        // floor
        tiles(g, FLOOR, 0, floorY, width, height - bottom - floorY, tile);
        g.fill(0, floorY, width, height - bottom, 0x9A0B0610);
        g.fill(0, floorY, width, floorY + 1, 0x803A2A48);

        // amethyst at the foot of the walls, glowing
        crystal(g, width * 15 / 100, floorY - tile * 2, tile * 2, time, 0f);
        crystal(g, width * 85 / 100 - tile * 2, floorY - tile * 2, tile * 2, time, 1.7f);
        crystal(g, width * 27 / 100, floorY - tile, tile, time, 3.1f);
        crystal(g, width * 71 / 100, floorY - tile, tile, time, 4.2f);

        if (stage) {
            int size = Math.max(32, height / 7);
            HubTheme.glow(g, width / 2, top + height * 17 / 100, size * 2, size * 2, HubTheme.argb(0xC27BFF, 0x70));
        }
    }

    private static void wall(GuiGraphics g, int x, int y, int w, int h, int tile) {
        if (w <= 0 || h <= 0) return;
        tiles(g, WALL, x, y, w, h, tile);
        g.fill(x, y, x + w, y + h, 0xB0100A16);
        g.renderOutline(x, y, w, h, 0x60000000);
    }

    private static int mixColor(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gr = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return r << 16 | gr << 8 | bl;
    }

    /** Fills a rectangle with a 16x16 texture scaled to {@code tile}, clipped at the edges. */
    private static void tiles(GuiGraphics g, Identifier texture, int x, int y, int w, int h, int tile) {
        g.enableScissor(x, y, x + w, y + h);
        for (int ty = y; ty < y + h; ty += tile) {
            for (int tx = x; tx < x + w; tx += tile) {
                HubTheme.image(g, texture, tx, ty, tile, tile, 16, 16);
            }
        }
        g.disableScissor();
    }

    private static void crystal(GuiGraphics g, int x, int y, int size, float time, float phase) {
        boolean moving = com.kevin.tiertagger.hub.fx.HubEffects.enabled();
        float wave = moving ? (float) (0.5 + 0.5 * Math.sin(time * 1.6f + phase)) : 0.5f;
        float hue = moving ? (float) (0.5 + 0.5 * Math.sin(time * 0.5f + phase * 1.3f)) : 0f;
        int alpha = 0x40 + (int) (0x60 * wave);
        HubTheme.glow(g, x + size / 2, y + size / 2, size + Math.round(size * 0.3f * wave), size, HubTheme.argb(mixColor(0xB06BFF, 0xFF6FD0, hue), alpha));
        HubTheme.image(g, CRYSTAL, x, y, size, size, 16, 16);
    }
}
