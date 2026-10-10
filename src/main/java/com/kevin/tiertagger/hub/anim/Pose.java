package com.kevin.tiertagger.hub.anim;

/**
 * One frame of a figure's body language, in Minecraft's model conventions.
 *
 * <p>Angles are radians on the model parts: a negative arm pitch swings the arm forward and up
 * ({@code -PI} points straight up), a positive head pitch looks down. Roll on the arms is
 * outwards-positive for both arms (the model mirrors the left one). {@code lift} raises the whole
 * figure in blocks, {@code spin} turns it around its vertical axis and {@code tilt} leans it
 * forward, both in degrees.</p>
 */
public record Pose(
        float headPitch, float headYaw, float headRoll,
        float rightArmPitch, float rightArmYaw, float rightArmRoll,
        float leftArmPitch, float leftArmYaw, float leftArmRoll,
        float rightLegPitch, float rightLegRoll,
        float leftLegPitch, float leftLegRoll,
        float lift, float spin, float tilt) {

    public static final Pose NEUTRAL = new Pose(0, 0, 0, 0, 0, 0.06f, 0, 0, 0.06f, 0, 0, 0, 0, 0, 0, 0);

    /** {@code a} at {@code t = 0}, {@code b} at {@code t = 1}; values outside 0..1 extrapolate (for overshoot). */
    public static Pose blend(Pose a, Pose b, float t) {
        if (t == 0f) return a;
        if (t == 1f) return b;
        return new Pose(
                mix(a.headPitch, b.headPitch, t), mix(a.headYaw, b.headYaw, t), mix(a.headRoll, b.headRoll, t),
                mix(a.rightArmPitch, b.rightArmPitch, t), mix(a.rightArmYaw, b.rightArmYaw, t), mix(a.rightArmRoll, b.rightArmRoll, t),
                mix(a.leftArmPitch, b.leftArmPitch, t), mix(a.leftArmYaw, b.leftArmYaw, t), mix(a.leftArmRoll, b.leftArmRoll, t),
                mix(a.rightLegPitch, b.rightLegPitch, t), mix(a.rightLegRoll, b.rightLegRoll, t),
                mix(a.leftLegPitch, b.leftLegPitch, t), mix(a.leftLegRoll, b.leftLegRoll, t),
                mix(a.lift, b.lift, t), mix(a.spin, b.spin, t), mix(a.tilt, b.tilt, t));
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public Pose withArms(float rightPitch, float rightYaw, float rightRoll, float leftPitch, float leftYaw, float leftRoll) {
        return new Pose(headPitch, headYaw, headRoll, rightPitch, rightYaw, rightRoll, leftPitch, leftYaw, leftRoll,
                rightLegPitch, rightLegRoll, leftLegPitch, leftLegRoll, lift, spin, tilt);
    }

    public Pose withHead(float pitch, float yaw, float roll) {
        return new Pose(pitch, yaw, roll, rightArmPitch, rightArmYaw, rightArmRoll, leftArmPitch, leftArmYaw, leftArmRoll,
                rightLegPitch, rightLegRoll, leftLegPitch, leftLegRoll, lift, spin, tilt);
    }

    public Pose withLegs(float rightPitch, float rightRoll, float leftPitch, float leftRoll) {
        return new Pose(headPitch, headYaw, headRoll, rightArmPitch, rightArmYaw, rightArmRoll, leftArmPitch, leftArmYaw, leftArmRoll,
                rightPitch, rightRoll, leftPitch, leftRoll, lift, spin, tilt);
    }

    public Pose withBody(float lift, float spin, float tilt) {
        return new Pose(headPitch, headYaw, headRoll, rightArmPitch, rightArmYaw, rightArmRoll, leftArmPitch, leftArmYaw, leftArmRoll,
                rightLegPitch, rightLegRoll, leftLegPitch, leftLegRoll, lift, spin, tilt);
    }
}
