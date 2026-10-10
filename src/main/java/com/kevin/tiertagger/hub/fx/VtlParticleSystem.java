package com.kevin.tiertagger.hub.fx;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Light particles for GUI screens, in screen pixels and real seconds: rising sparks for the hub
 * background, confetti and firework bursts for a promotion, rain for a demotion.
 *
 * <p>Only the simulation lives here (no Minecraft types, {@code ParticleCheck} runs it headless);
 * {@link ParticleRenderer} draws it. Sparks move along a fixed path by age, and emitters count
 * fractional particles, so the picture is the same at 30 or 144 FPS.</p>
 */
public final class VtlParticleSystem {
    public static final int AMBIENT_PER_SECOND = 9;
    private static final int RAIN_PER_SECOND = 70;
    private static final int[] SPARK_COLORS = {0xC9A2FF, 0xFF8FD0, 0xE6E0F0, 0xA35BFF};
    private static final int[] CONFETTI_COLORS = {0xFFC83D, 0xFF4FA3, 0xA35BFF, 0x5AD1FF, 0x5DFF8A, 0xFFFFFF};

    public enum Kind { SPARK, CONFETTI, BURST, RAIN }

    /** One particle; public fields for the renderer and the checks. */
    public static final class Particle {
        public Kind kind;
        public float x, y, vx, vy;
        public float age, life;
        public float size;
        public float phase;
        public float spin, spinSpeed;
        public int color;
        float baseX;
    }

    private final List<Particle> particles = new ArrayList<>();
    private final int cap;
    private final Random random;
    private double ambientCarry;
    private double rainCarry;

    public VtlParticleSystem(int cap, long seed) {
        this.cap = cap;
        this.random = new Random(seed);
    }

    public List<Particle> particles() {
        return particles;
    }

    public void clear() {
        particles.clear();
    }

    // --- emitters ---

    /** A spark at {@code x, y} that drifts up, swaying, and fades out. */
    public void spark(float x, float y) {
        Particle p = add(Kind.SPARK);
        if (p == null) return;
        p.baseX = x;
        p.phase = random.nextFloat() * (float) (Math.PI * 2);
        p.x = x + (float) Math.sin(p.phase) * 6f;
        p.y = y;
        p.vy = -(14f + random.nextFloat() * 12f);
        p.life = 5f + random.nextFloat() * 3f;
        p.size = 1f + random.nextFloat() * 1.5f;
        p.color = SPARK_COLORS[random.nextInt(SPARK_COLORS.length)];
    }

    /** The background's steady trickle of sparks from the lower part of the screen. */
    public void ambient(float dt, float width, float height) {
        ambientCarry += AMBIENT_PER_SECOND * (double) dt;
        while (ambientCarry >= 1.0 - 1e-6) {
            ambientCarry -= 1.0;
            spark(random.nextFloat() * width, height - random.nextFloat() * height * 0.15f);
        }
    }

    /** {@code count} pieces of confetti thrown from around {@code x, y}. */
    public void confetti(float x, float y, int count) {
        for (int i = 0; i < count; i++) {
            Particle p = add(Kind.CONFETTI);
            if (p == null) return;
            p.x = x + (random.nextFloat() - 0.5f) * 80f;
            p.y = y + random.nextFloat() * 10f;
            p.vx = (random.nextFloat() - 0.5f) * 120f;
            p.vy = -40f + random.nextFloat() * 50f;
            p.life = 3f + random.nextFloat() * 1.5f;
            p.size = 2f + random.nextFloat() * 1.5f;
            p.spin = random.nextFloat() * 360f;
            p.spinSpeed = (random.nextFloat() - 0.5f) * 720f;
            p.color = CONFETTI_COLORS[random.nextInt(CONFETTI_COLORS.length)];
        }
    }

    /** A firework: {@code count} sparks thrown outwards from {@code x, y}. */
    public void burst(float x, float y, int count, int color) {
        for (int i = 0; i < count; i++) {
            Particle p = add(Kind.BURST);
            if (p == null) return;
            double angle = Math.PI * 2 * i / count + random.nextFloat() * 0.2;
            float speed = 60f + random.nextFloat() * 50f;
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(angle) * speed;
            p.vy = (float) Math.sin(angle) * speed;
            p.life = 1.2f + random.nextFloat() * 0.6f;
            p.size = 1.5f + random.nextFloat();
            p.color = color & 0xFFFFFF;
        }
    }

    /** Rain falling across the whole screen. */
    public void rain(float dt, float width, float height) {
        rainCarry += RAIN_PER_SECOND * (double) dt;
        while (rainCarry >= 1.0 - 1e-6) {
            rainCarry -= 1.0;
            Particle p = add(Kind.RAIN);
            if (p == null) return;
            p.x = random.nextFloat() * (width + 40f);
            p.y = -10f;
            p.vx = -12f;
            p.vy = 220f + random.nextFloat() * 80f;
            p.life = 3f;
            p.size = 3f + random.nextFloat() * 3f;
            p.color = 0x9FB8D8;
        }
    }

    private Particle add(Kind kind) {
        if (particles.size() >= cap) return null;
        Particle p = new Particle();
        p.kind = kind;
        particles.add(p);
        return p;
    }

    // --- simulation ---

    public void update(float dt, float width, float height) {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.age += dt;
            switch (p.kind) {
                case SPARK -> {
                    p.y += p.vy * dt;
                    p.x = p.baseX + (float) Math.sin(p.phase + p.age * 1.3f) * 6f;
                }
                case CONFETTI -> {
                    p.vy = Math.min(70f, p.vy + 60f * dt);
                    p.vx -= p.vx * 0.8f * dt;
                    p.x += p.vx * dt;
                    p.y += p.vy * dt;
                    p.spin += p.spinSpeed * dt;
                }
                case BURST -> {
                    p.vy += 40f * dt;
                    p.vx -= p.vx * 1.2f * dt;
                    p.vy -= p.vy * 1.2f * dt;
                    p.x += p.vx * dt;
                    p.y += p.vy * dt;
                }
                case RAIN -> {
                    p.x += p.vx * dt;
                    p.y += p.vy * dt;
                }
            }
            if (p.age >= p.life || p.x < -40 || p.x > width + 40 || p.y < -40 || p.y > height + 40) particles.remove(i);
        }
    }

    /** 0..1: fades in after birth and out before death. */
    public static float alpha(Particle p) {
        float in = p.kind == Kind.SPARK ? 0.6f : 0.15f;
        float out = p.kind == Kind.SPARK ? 1.2f : 0.5f;
        float a = Math.min(p.age / in, (p.life - p.age) / out);
        return Math.max(0f, Math.min(1f, a));
    }
}
