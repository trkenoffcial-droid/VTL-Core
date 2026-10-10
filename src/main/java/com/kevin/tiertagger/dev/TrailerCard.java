package com.kevin.tiertagger.dev;

import com.kevin.tiertagger.hub.HubBackground;
import com.kevin.tiertagger.hub.HubTheme;
import com.kevin.tiertagger.hub.fx.HubEffects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.List;

/**
 * Development only: a full-screen card of the trailer (title, the link rules, the end) on the
 * hub's own animated background.
 */
final class TrailerCard extends Screen {
    record Line(String text, int color, float scale) {}

    private final boolean wordmark;
    private final List<Line> lines;
    private final long opened = Util.getMillis();
    private final HubEffects effects = new HubEffects();

    private TrailerCard(boolean wordmark, List<Line> lines) {
        super(Component.literal("VTL Core"));
        this.wordmark = wordmark;
        this.lines = lines;
    }

    static TrailerCard intro() {
        return new TrailerCard(true, List.of(
                new Line("VTL Core", 0xFFFFD45C, 4.0f),
                new Line("", 0, 0.6f),
                new Line("Viet TierList, in your game", 0xFFE9DDF7, 2.0f)));
    }

    static TrailerCard rules() {
        return new TrailerCard(false, List.of(
                new Line("A link that proves who plays", 0xFFFFD45C, 2.6f),
                new Line("", 0, 1f),
                new Line("•  The same Minecraft account as /verify", 0xFFE9DDF7, 1.7f),
                new Line("•  Premium accounts confirmed by Mojang", 0xFFE9DDF7, 1.7f),
                new Line("•  One network, one Discord account", 0xFFE9DDF7, 1.7f)));
    }

    static TrailerCard end() {
        return new TrailerCard(true, List.of(
                new Line("VTL Core", 0xFFFFD45C, 3.6f),
                new Line("", 0, 0.6f),
                new Line("3.3.0  ·  Minecraft 1.21.11  ·  Fabric", 0xFFE9DDF7, 1.6f),
                new Line("", 0, 1f),
                new Line("tierslist.net", 0xFFFFD45C, 2.2f)));
    }

    private float time() {
        return (Util.getMillis() - opened) / 1000f;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        HubBackground.render(g, this.width, this.height, 0, 0, time(), false);
        effects.render(g, this.width, this.height, 0, 0, time());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int y = this.height / 2;
        int blockH = 0;
        for (Line line : lines) blockH += lineHeight(line);
        if (wordmark) {
            int ww = Math.min(this.width * 9 / 20, 360);
            int wh = ww / 3;
            // a slow breath on the glow behind the wordmark
            float pulse = 0.75f + 0.25f * (float) Math.sin(time() * 2.2);
            HubTheme.glow(g, cx, y - blockH / 2 - wh / 2, ww * 3 / 4, wh, HubTheme.argb(0xB65CFF, (int) (150 * pulse)));
            HubTheme.image(g, HubTheme.WORDMARK, cx - ww / 2, y - blockH / 2 - wh - 6, ww, wh, 720, 240);
            y = y - blockH / 2 + 6;
        } else {
            y = y - blockH / 2;
        }
        for (Line line : lines) {
            if (!line.text().isEmpty()) {
                HubTheme.centered(g, this.font, Component.literal(line.text()), cx, y, line.color(), HubTheme.sharp(line.scale()));
            }
            y += lineHeight(line);
        }
    }

    private int lineHeight(Line line) {
        return Math.round(11 * line.scale());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
