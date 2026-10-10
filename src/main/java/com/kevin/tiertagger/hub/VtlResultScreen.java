package com.kevin.tiertagger.hub;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.hub.anim.Pose;
import com.kevin.tiertagger.hub.anim.TierChange;
import com.kevin.tiertagger.hub.anim.TierChange.Outcome;
import com.kevin.tiertagger.hub.anim.VtlFigure;
import com.kevin.tiertagger.hub.anim.VtlKitRegistry;
import com.kevin.tiertagger.hub.anim.VtlModelAnimator;
import com.kevin.tiertagger.hub.anim.VtlModelAnimator.Anim;
import com.kevin.tiertagger.hub.fx.HubEffects;
import com.kevin.tiertagger.hub.fx.ParticleRenderer;
import com.kevin.tiertagger.hub.fx.VtlParticleSystem;
import com.kevin.tiertagger.hub.widget.HubButton;
import com.kevin.tiertagger.queue.QueueMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Locale;
import java.util.Random;
import java.util.function.Supplier;

/**
 * The test result, shown when the tester posts it: a short wait while the tier "rolls", then the
 * reveal. A promotion (or a first tier) cheers with fireworks and confetti; a demotion (or no
 * tier) hangs its head under the rain. CONTINUE goes back to where the player was.
 */
public class VtlResultScreen extends Screen {
    /** What the screen shows; {@code previousTier} null when the bot did not say it. */
    public record Data(Kit kit, String region, String tester, String previousTier, String tier) {
        public Outcome outcome() {
            return TierChange.of(previousTier, tier);
        }
    }

    public static final float SUSPENSE_S = 2.6f;
    private static final String[] ROLL = {"LT5", "HT5", "LT4", "HT4", "LT3", "HT3", "LT2", "HT2", "LT1", "HT1"};

    private final Screen parent;
    private final Data data;
    private final Outcome outcome;
    private final long opened = Util.getMillis();
    private final HubEffects background = new HubEffects();
    private final VtlParticleSystem fx = new VtlParticleSystem(500, Util.getMillis());
    private final Random random = new Random();
    private Supplier<PlayerSkin> skin;
    private HubButton button;

    private boolean revealed;
    private float revealAt;
    private Pose shown;
    private VtlModelAnimator.Transition transition;
    private long lastFrame;
    private float nextBurst;
    private int lastTick = -1;

    public VtlResultScreen(Screen parent, Data data) {
        super(Component.translatable("vtl.result.title"));
        this.parent = parent;
        this.data = data;
        this.outcome = data.outcome();
    }

    private float now() {
        return (Util.getMillis() - opened) / 1000f;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        if (skin == null) skin = mc.getSkinManager().createLookup(mc.getGameProfile(), true);
        int bw = 150;
        button = this.addRenderableWidget(new HubButton((this.width - bw) / 2, this.height - 34, bw, 22,
                Component.translatable(revealed ? "vtl.result.continue" : "vtl.result.skip"), HubButton.Style.PRIMARY, this::press));
    }

    private void press() {
        if (!revealed) reveal(now());
        else onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (!revealed) {
            reveal(now()); // a click anywhere skips the wait
            return true;
        }
        return false;
    }

    private void reveal(float t) {
        if (revealed) return;
        revealed = true;
        revealAt = t;
        if (shown != null) transition = new VtlModelAnimator.Transition(shown, t);
        button.setMessage(Component.translatable("vtl.result.continue"));
        if (outcome.celebrates()) {
            play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            play(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1f, 0.8f);
            fx.confetti(this.width * 0.3f, this.height * 0.15f, 70);
            fx.confetti(this.width * 0.7f, this.height * 0.15f, 70);
            nextBurst = t;
        } else if (outcome.mourns()) {
            play(SoundEvents.BEACON_DEACTIVATE, 0.8f, 1f);
        } else {
            play(SoundEvents.PLAYER_LEVELUP, 1.2f, 0.6f);
        }
    }

    // --- drawing ---

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        HubBackground.render(g, this.width, this.height, 0, 0, now(), true);
        background.render(g, this.width, this.height, 0, 0, now());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        float t = now();
        long millis = Util.getMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (millis - lastFrame) / 1000f);
        lastFrame = millis;
        if (!revealed && t >= SUSPENSE_S) reveal(t);

        if (revealed && outcome.mourns()) vignette(g, Math.min(1f, (t - revealAt) / 0.8f));
        effects(t, dt);
        ParticleRenderer.draw(g, fx);

        drawFigure(g, t);
        drawPanel(g, t);
        if (revealed) {
            // CONTINUE breathes so it is easy to find
            float pulse = 0.5f + 0.5f * (float) Math.sin(t * 4f);
            HubTheme.glow(g, button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2,
                    button.getWidth() / 2 + 10, button.getHeight() + 4, HubTheme.argb(0xA35BFF, (int) (0x40 + 0x60 * pulse)));
        }
        super.render(g, mouseX, mouseY, delta);
    }

    private void effects(float t, float dt) {
        if (revealed && outcome.celebrates()) {
            float since = t - revealAt;
            if (since < 4.5f && t >= nextBurst) {
                int[] colors = {0xFFC83D, 0xA35BFF, 0xFF4FA3, 0x5AD1FF};
                fx.burst(this.width * (0.15f + random.nextFloat() * 0.7f), this.height * (0.12f + random.nextFloat() * 0.3f), 28,
                        colors[random.nextInt(colors.length)]);
                play(SoundEvents.FIREWORK_ROCKET_TWINKLE, 0.9f + random.nextFloat() * 0.3f, 0.35f);
                nextBurst = t + 0.55f + random.nextFloat() * 0.4f;
            }
            if (since < 3f && random.nextFloat() < dt * 12f) fx.confetti(random.nextFloat() * this.width, -5f, 4);
        } else if (revealed && outcome.mourns()) {
            fx.rain(dt, this.width, this.height);
        }
        fx.update(dt, this.width, this.height);
    }

    private void vignette(GuiGraphics g, float strength) {
        int a = (int) (0xB0 * strength);
        int band = Math.max(20, this.height / 4);
        int cold = 0x0A1424;
        g.fill(0, 0, this.width, this.height, HubTheme.argb(0x1A2840, (int) (0x50 * strength)));
        g.fillGradient(0, 0, this.width, band, HubTheme.argb(cold, a), HubTheme.argb(cold, 0));
        g.fillGradient(0, this.height - band, this.width, this.height, HubTheme.argb(cold, 0), HubTheme.argb(cold, a));
        for (int i = 0; i < band / 2; i++) {
            int alpha = (int) (a * (1f - (float) i / (band / 2f)));
            g.fill(i, 0, i + 1, this.height, HubTheme.argb(cold, alpha));
            g.fill(this.width - 1 - i, 0, this.width - i, this.height, HubTheme.argb(cold, alpha));
        }
    }

    private void drawFigure(GuiGraphics g, float t) {
        if (skin == null) return;
        var loadout = VtlKitRegistry.of(data.kit());
        Anim anim = !revealed ? Anim.WAIT_BOUNCE : outcome.celebrates() ? Anim.CHEER : outcome.mourns() ? Anim.SAD : Anim.IDLE;
        float at = revealed ? t - revealAt : t;
        Pose target = VtlModelAnimator.pose(anim, anim == Anim.IDLE ? loadout.stance() : VtlModelAnimator.Stance.NONE, at);
        Pose pose = transition != null ? transition.apply(target, t) : target;
        if (transition != null && transition.done(t)) transition = null;
        shown = pose;

        int figH = Math.round(this.height * 0.48f);
        int cx = Math.round(this.width * 0.72f);
        int feet = this.height - 44;
        HubTheme.glow(g, cx, feet - 2, figH / 2, 8, HubTheme.argb(outcome.mourns() && revealed ? 0x5A7AA8 : 0xC27BFF, 0xB0));
        int boxW = Math.round(figH * 1.1f);
        VtlFigure.render(g, cx - boxW / 2, feet - Math.round(figH * 1.5f), cx + boxW / 2, feet, figH, skin, loadout, pose, 15f, t);
    }

    private void drawPanel(GuiGraphics g, float t) {
        var font = this.font;
        int x = Math.round(this.width * 0.07f);
        int w = Math.round(this.width * 0.48f);
        int y = Math.max(16, Math.round(this.height * 0.14f));

        HubTheme.text(g, font, Component.translatable("vtl.result.title"), x, y, HubTheme.DIM, 1f);
        String kit = (data.kit() == null ? "" : data.kit().label().toUpperCase(Locale.ROOT))
                + (data.region() == null ? "" : " · " + QueueMessages.regionLabel(data.region()));
        HubTheme.text(g, font, Component.literal(kit), x, y + 14, HubTheme.TEXT, 2f);
        if (data.tester() != null) {
            HubTheme.text(g, font, Component.translatable("vtl.result.tester", data.tester()), x, y + 36, HubTheme.DIM, 1f);
        }

        int boxY = y + 58;
        int boxH = Math.max(56, Math.round(this.height * 0.26f));
        if (!revealed) {
            // the tier "rolls" faster and faster, ticking
            int step = (int) (t * (8f + t * 6f));
            String rolling = ROLL[Math.floorMod(step * 7 + 3, ROLL.length)];
            if (step != lastTick) {
                lastTick = step;
                play(SoundEvents.NOTE_BLOCK_HAT.value(), 0.8f + Math.min(0.9f, t * 0.3f), 0.25f);
            }
            HubTheme.frame(g, x, boxY, w, boxH, HubTheme.PANEL_SOLID, HubTheme.GOLD);
            HubTheme.centered(g, font, Component.literal(rolling), x + w / 2, boxY + boxH / 2 - 14, HubTheme.GOLD, 3f);
            int dots = 1 + (int) (t * 2.5f) % 3;
            HubTheme.centered(g, font, Component.translatable("vtl.result.tallying").append(".".repeat(dots)), x + w / 2, boxY + boxH + 8, HubTheme.DIM, 1f);
            return;
        }

        float pop = Math.min(1f, (t - revealAt) / 0.4f);
        float grow = VtlModelAnimator.easeOutBack(pop);
        int color = outcome.celebrates() ? HubTheme.GOLD : outcome.mourns() ? 0xFF8FB4E0 : HubTheme.TEXT;
        int border = outcome.celebrates() ? HubTheme.GOLD : outcome.mourns() ? 0xFF5A7AA8 : HubTheme.VIOLET;
        if (outcome.celebrates()) {
            float glow = 0.6f + 0.4f * (float) Math.sin(t * 3f);
            HubTheme.glow(g, x + w / 2, boxY + boxH / 2, w / 2 + 30, boxH, HubTheme.argb(0xFFC83D, (int) (0x70 * glow)));
        }
        HubTheme.frame(g, x, boxY, w, boxH, HubTheme.PANEL_SOLID, border);
        Component headline = Component.translatable("vtl.result." + outcome.name().toLowerCase(Locale.ROOT));
        HubTheme.centered(g, font, headline, x + w / 2, boxY + 10, color, Math.max(0.5f, 2.2f * grow));
        Component tierLine = tierLine();
        if (tierLine != null) HubTheme.centered(g, font, tierLine, x + w / 2, boxY + boxH - 26, HubTheme.TEXT, Math.max(0.5f, 1.75f * grow));
    }

    private Component tierLine() {
        String now = TierChange.code(data.tier());
        String before = TierChange.code(data.previousTier());
        return switch (outcome) {
            case PROMOTED, DEMOTED -> Component.literal(before + "  →  " + now);
            case KEPT, FIRST_TIER, UNKNOWN -> Component.literal(now);
            case NO_TIER -> null; // what happens to the old tier is the bot's business; do not guess
        };
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        float v = TierTagger.getManager().getConfig().getAlertVolume() / 100f * volume;
        if (v <= 0f) return;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, v));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
