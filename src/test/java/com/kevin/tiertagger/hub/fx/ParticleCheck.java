package com.kevin.tiertagger.hub.fx;

import com.kevin.tiertagger.hub.fx.VtlParticleSystem.Kind;
import com.kevin.tiertagger.hub.fx.VtlParticleSystem.Particle;

import java.util.List;

/**
 * Runnable self-check for the GUI particle simulation: motion, life, limits, and the same result
 * at any frame rate. Pure Java, no game: part of {@code ./gradlew animatorCheck}.
 */
public final class ParticleCheck {
    private ParticleCheck() {}

    private static int checks = 0;

    public static void main(String[] args) {
        sparksRiseSwayAndFade();
        sparksDieAndAreRemoved();
        capHolds();
        sameAtAnyFrameRate();
        confettiFalls();
        burstSpreadsOut();
        rainFallsFast();
        ambientRateIsPerSecond();
        System.out.println("ParticleCheck: " + checks + " checks passed");
    }

    private static VtlParticleSystem system() {
        return new VtlParticleSystem(200, 42L);
    }

    private static void run(VtlParticleSystem s, float seconds, float fps) {
        int frames = Math.round(seconds * fps);
        for (int i = 0; i < frames; i++) s.update(1f / fps, 400, 300);
    }

    private static void sparksRiseSwayAndFade() {
        VtlParticleSystem s = system();
        s.spark(200, 280);
        Particle p = s.particles().get(0);
        float startY = p.y, startX = p.x;
        check(VtlParticleSystem.alpha(p) < 0.2f, "a new spark fades in");
        run(s, 1f, 60);
        check(p.y < startY - 5, "sparks rise");
        boolean swayed = false;
        for (int i = 0; i < 120; i++) {
            s.update(1f / 60, 400, 300);
            if (Math.abs(p.x - startX) > 1f) swayed = true;
        }
        check(swayed, "sparks sway sideways");
        check(VtlParticleSystem.alpha(p) > 0f && VtlParticleSystem.alpha(p) <= 1f, "alpha stays within 0..1");
    }

    private static void sparksDieAndAreRemoved() {
        VtlParticleSystem s = system();
        s.spark(200, 280);
        run(s, 12f, 30);
        check(s.particles().isEmpty(), "dead sparks are removed");
    }

    private static void capHolds() {
        VtlParticleSystem s = new VtlParticleSystem(50, 1L);
        for (int i = 0; i < 500; i++) s.spark(10, 10);
        check(s.particles().size() == 50, "never more than the cap, got " + s.particles().size());
    }

    private static void sameAtAnyFrameRate() {
        VtlParticleSystem slow = system(), fast = system();
        for (int i = 0; i < 30; i++) {
            slow.ambient(1f / 30, 400, 300);
            slow.update(1f / 30, 400, 300);
        }
        for (int i = 0; i < 144; i++) {
            fast.ambient(1f / 144, 400, 300);
            fast.update(1f / 144, 400, 300);
        }
        check(slow.particles().size() == fast.particles().size(), "as many sparks after 1 s at 30 and 144 FPS: "
                + slow.particles().size() + " vs " + fast.particles().size());
        VtlParticleSystem a = system(), b = system();
        a.spark(100, 250);
        b.spark(100, 250);
        run(a, 2f, 30);
        run(b, 2f, 144);
        Particle pa = a.particles().get(0), pb = b.particles().get(0);
        check(Math.abs(pa.y - pb.y) < 0.5f && Math.abs(pa.x - pb.x) < 0.5f, "a spark is in the same place after 2 s at 30 and 144 FPS");
    }

    private static void confettiFalls() {
        VtlParticleSystem s = system();
        s.confetti(200, 20, 10);
        float before = average(s.particles(), true);
        run(s, 1.5f, 60);
        check(average(s.particles(), true) > before + 20, "confetti falls");
        check(s.particles().stream().allMatch(p -> p.kind == Kind.CONFETTI), "confetti particles are confetti");
    }

    private static void burstSpreadsOut() {
        VtlParticleSystem s = system();
        s.burst(200, 150, 24, 0xFFFFC83D);
        run(s, 0.4f, 60);
        float maxDist = 0;
        for (Particle p : s.particles()) maxDist = Math.max(maxDist, (float) Math.hypot(p.x - 200, p.y - 150));
        check(s.particles().size() == 24 && maxDist > 20, "a burst throws its sparks outwards");
    }

    private static void rainFallsFast() {
        VtlParticleSystem s = system();
        for (int i = 0; i < 60; i++) {
            s.rain(1f / 60, 400, 300);
            s.update(1f / 60, 400, 300);
        }
        check(!s.particles().isEmpty() && s.particles().stream().allMatch(p -> p.kind == Kind.RAIN && p.vy > 100), "rain falls quickly");
    }

    private static void ambientRateIsPerSecond() {
        VtlParticleSystem s = new VtlParticleSystem(1000, 3L);
        for (int i = 0; i < 60; i++) s.ambient(1f / 60, 400, 300);
        int n = s.particles().size();
        check(n >= VtlParticleSystem.AMBIENT_PER_SECOND - 1 && n <= VtlParticleSystem.AMBIENT_PER_SECOND + 1,
                "about " + VtlParticleSystem.AMBIENT_PER_SECOND + " ambient sparks per second, got " + n);
    }

    private static float average(List<Particle> ps, boolean y) {
        return (float) ps.stream().mapToDouble(p -> y ? p.y : p.x).average().orElse(0);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("ParticleCheck failed: " + message);
    }
}
