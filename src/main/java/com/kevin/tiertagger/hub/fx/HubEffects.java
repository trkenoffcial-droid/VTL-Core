package com.kevin.tiertagger.hub.fx;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.hub.HubTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * The moving part of the hub's backdrop: sparks rising from the floor and a few faint crystals
 * drifting far behind. One per screen; off when the player turns background effects off.
 */
public final class HubEffects {
    private static final Identifier CRYSTAL = Identifier.withDefaultNamespace("textures/block/amethyst_cluster.png");
    private static final int MAX_PARTICLES = 70;

    private final VtlParticleSystem particles = new VtlParticleSystem(MAX_PARTICLES, Util.getMillis());
    private long lastFrame;
    private boolean warmed;

    public static boolean enabled() {
        return TierTagger.getManager().getConfig().isHubEffects();
    }

    /** Draws the effects for this frame; {@code time} is seconds since the screen opened. */
    public void render(GuiGraphics g, int width, int height, int top, int bottom, float time) {
        if (!enabled()) return;
        long now = Util.getMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        int floor = height - bottom;
        if (!warmed) {
            // start with the air already full instead of an empty screen filling up
            warmed = true;
            for (int i = 0; i < 60; i++) {
                particles.ambient(0.1f, width, floor);
                particles.update(0.1f, width, floor);
            }
        }

        drifters(g, width, top, floor, time);
        particles.ambient(dt, width, floor);
        particles.update(dt, width, floor);
        g.enableScissor(0, top, width, floor);
        ParticleRenderer.draw(g, particles);
        g.disableScissor();
    }

    /** Three faint crystals floating slowly across the upper cave, bobbing. */
    private static void drifters(GuiGraphics g, int width, int top, int floor, float time) {
        float[][] specs = {{0.10f, 7f, 0.22f, 26f}, {0.45f, 5f, 0.38f, 18f}, {0.80f, 9f, 0.16f, 22f}};
        for (float[] s : specs) {
            float span = width + 80f;
            float x = span - ((s[0] * span + time * s[1]) % span) - 40f;
            float y = top + (floor - top) * s[2] + (float) Math.sin(time * 0.6f + s[0] * 10f) * 6f;
            int size = Math.round(s[3]);
            HubTheme.glow(g, Math.round(x), Math.round(y), size, size, HubTheme.argb(0xA35BFF, 0x30));
            HubTheme.image(g, CRYSTAL, Math.round(x) - size / 2, Math.round(y) - size / 2, size, size, 16, 16, HubTheme.argb(0xFFFFFF, 0x55));
        }
    }
}
