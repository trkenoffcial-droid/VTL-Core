package com.kevin.tiertagger.hub.anim;

/**
 * The figure's animations as plain maths: a {@link Pose} for any moment, from the animation, the
 * kit's fighting stance and the time in seconds. Real time, not frames, so it moves the same at
 * any frame rate. No Minecraft types: {@code AnimatorCheck} runs it headless.
 */
public final class VtlModelAnimator {
    private VtlModelAnimator() {}

    public enum Anim {
        /** Standing, breathing, looking around; arms in the kit's stance. */
        IDLE,
        /** Hopping and fidgeting while the result is being tallied. */
        WAIT_BOUNCE,
        /** Jumping with both arms up, one full spin. */
        CHEER,
        /** Head down, arms hanging, an occasional head shake. */
        SAD
    }

    /** How the figure holds the kit's items. */
    public enum Stance { NONE, SWORD_GUARD, SHIELD_UP, MACE_RAISE, POT_READY, CRYSTAL_HOLD, BOW_DRAW }

    /** How long a change of stance (picking another kit) takes, in seconds. */
    public static final float TRANSITION_S = 0.45f;
    /** The cheer's spin takes this long; afterwards the figure faces front again. */
    public static final float CHEER_SPIN_S = 1.0f;

    private static final float TAU = (float) (Math.PI * 2);
    private static final float HALF_PI = (float) (Math.PI / 2);

    public static Pose pose(Anim anim, Stance stance, float t) {
        return switch (anim) {
            case IDLE -> idle(stance, t);
            case WAIT_BOUNCE -> waitBounce(t);
            case CHEER -> cheer(t);
            case SAD -> sad(t);
        };
    }

    // --- idle and the kit stances ---

    private static Pose idle(Stance stance, float t) {
        float breath = sin(t * TAU / 3.2f);
        float sway = 0.035f * breath;
        float headPitch = -0.04f + 0.05f * sin(t * TAU / 3.2f + 0.6f);
        float headYaw = stance == Stance.BOW_DRAW ? 0.04f * sin(t * TAU / 5f) : 0.22f * sin(t * TAU / 7f);
        Pose base = Pose.NEUTRAL.withHead(headPitch, headYaw, 0).withBody(0.012f * breath, 0, 0);
        boolean fighting = stance != Stance.NONE;
        Pose legs = fighting ? base.withLegs(0.12f, 0.04f, -0.15f, 0.04f) : base.withLegs(0, 0.03f, 0, 0.03f);
        return switch (stance) {
            case NONE -> legs.withArms(0.02f * breath, 0, 0.07f + 0.02f * breath, -0.02f * breath, 0, 0.07f + 0.02f * breath);
            case SWORD_GUARD -> legs.withArms(-1.05f + sway, -0.28f, 0.05f, -0.35f - sway, 0.15f, 0.10f);
            case SHIELD_UP -> legs.withArms(-0.55f + sway, -0.15f, 0.05f, -0.95f, 0.52f, 0.0f);
            case MACE_RAISE -> legs.withArms(-2.55f + sway, 0.10f, -0.15f, -0.40f - sway, 0.0f, 0.12f);
            case POT_READY -> legs.withArms(-2.10f + sway, -0.20f, 0.25f, -0.30f - sway, 0.0f, 0.08f);
            case CRYSTAL_HOLD -> legs.withArms(-0.95f + sway, -0.25f, 0.05f, -0.60f - sway, 0.30f, 0.05f);
            // Minecraft's own bow pose, both arms follow the head
            case BOW_DRAW -> legs.withArms(-HALF_PI + headPitch, -0.1f + headYaw, 0, -HALF_PI + headPitch, 0.5f + headYaw, 0);
        };
    }

    // --- waiting for the result ---

    private static Pose waitBounce(float t) {
        float period = 0.55f;
        float phase = (t % period) / period;
        float air = sin((float) Math.PI * phase);
        float swing = sin(t * TAU * 1.8f);
        return Pose.NEUTRAL
                .withHead(-0.10f, 0.35f * sin(t * TAU / 1.6f), 0)
                .withArms(-0.6f + 0.7f * swing, 0, 0.35f, -0.6f - 0.7f * swing, 0, 0.35f)
                .withLegs(-0.35f * air, 0.03f, 0.20f * air, 0.03f)
                .withBody(0.32f * air, 0, 0);
    }

    // --- promotion ---

    private static Pose cheer(float t) {
        float period = 1.1f;
        float phase = (t % period) / period;
        float air = phase < 0.7f ? sin((float) Math.PI * phase / 0.7f) : 0f;
        float wave = 0.12f * sin(t * TAU * 2f);
        float spin = t < CHEER_SPIN_S ? 360f * easeOutCubic(t / CHEER_SPIN_S) : 360f + 12f * sin((t - CHEER_SPIN_S) * TAU / 2.4f);
        // Minecraft turns a part around Z after X: with the arm raised overhead, a negative roll
        // is the one that spreads it outwards into a V (positive would cross the arms)
        return Pose.NEUTRAL
                .withHead(-0.35f + 0.05f * sin(t * TAU), 0, 0)
                .withArms(-2.85f + wave, 0, -0.5f - wave, -2.85f - wave, 0, -0.5f + wave)
                .withLegs(-0.30f * air, 0.05f, 0.20f * air, 0.05f)
                .withBody(0.62f * air, spin, 0);
    }

    // --- demotion ---

    private static Pose sad(float t) {
        float slow = sin(t * TAU / 3f);
        float cycle = t % 4f;
        float shake = cycle < 1.2f ? 0.3f * sin(TAU * 2.5f * cycle) * sin((float) Math.PI * cycle / 1.2f) : 0f;
        return Pose.NEUTRAL
                .withHead(0.72f + 0.05f * slow, shake, 0)
                .withArms(0.06f + 0.02f * slow, 0, 0.02f, 0.06f - 0.02f * slow, 0, 0.02f)
                .withLegs(0, 0.01f, 0, 0.01f)
                .withBody(-0.06f + 0.01f * slow, 0, 9f);
    }

    // --- changes between poses ---

    /** A blend from the pose that was showing to a new one, started at {@code start} seconds. */
    public static final class Transition {
        private final Pose from;
        private final float start;

        public Transition(Pose from, float start) {
            this.from = from;
            this.start = start;
        }

        public Pose apply(Pose to, float now) {
            float p = (now - start) / TRANSITION_S;
            if (p <= 0f) return from;
            if (p >= 1f) return to;
            return Pose.blend(from, to, easeOutBack(p));
        }

        public boolean done(float now) {
            return now - start >= TRANSITION_S;
        }
    }

    /** Ends at 1 after going a little past it: a weapon swung up into a guard. */
    public static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        float d = x - 1f;
        return 1f + c3 * d * d * d + c1 * d * d;
    }

    public static float easeOutCubic(float x) {
        float d = 1f - x;
        return 1f - d * d * d;
    }

    private static float sin(float x) {
        return (float) Math.sin(x);
    }
}
