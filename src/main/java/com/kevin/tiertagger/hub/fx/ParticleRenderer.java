package com.kevin.tiertagger.hub.fx;

import com.kevin.tiertagger.compat.GuiPose;
import com.kevin.tiertagger.hub.HubTheme;
import com.kevin.tiertagger.hub.fx.VtlParticleSystem.Particle;
import net.minecraft.client.gui.GuiGraphics;

/** Draws a {@link VtlParticleSystem} on a GUI screen. */
public final class ParticleRenderer {
    private ParticleRenderer() {}

    public static void draw(GuiGraphics g, VtlParticleSystem system) {
        for (Particle p : system.particles()) {
            float a = VtlParticleSystem.alpha(p);
            if (a <= 0f) continue;
            int x = Math.round(p.x), y = Math.round(p.y);
            switch (p.kind) {
                case SPARK, BURST -> {
                    int r = Math.max(2, Math.round(p.size * (p.kind == VtlParticleSystem.Kind.BURST ? 3f : 4f)));
                    HubTheme.glow(g, x, y, r, r, HubTheme.argb(p.color, (int) (0x90 * a)));
                    int core = Math.max(1, Math.round(p.size));
                    g.fill(x, y, x + core, y + core, HubTheme.argb(p.color, (int) (0xFF * a)));
                }
                case CONFETTI -> {
                    GuiPose.push(g);
                    GuiPose.translate(g, p.x, p.y);
                    GuiPose.rotate(g, (float) Math.toRadians(p.spin));
                    int s = Math.max(1, Math.round(p.size));
                    g.fill(-s, -Math.max(1, s / 2), s, Math.max(1, s / 2), HubTheme.argb(p.color, (int) (0xFF * a)));
                    GuiPose.pop(g);
                }
                case RAIN -> g.fill(x, y, x + 1, y + Math.max(2, Math.round(p.size * 2)), HubTheme.argb(p.color, (int) (0x8C * a)));
            }
        }
    }
}
