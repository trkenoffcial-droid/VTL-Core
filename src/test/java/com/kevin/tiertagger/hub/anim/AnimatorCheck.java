package com.kevin.tiertagger.hub.anim;

import com.kevin.tiertagger.hub.anim.VtlModelAnimator.Anim;
import com.kevin.tiertagger.hub.anim.VtlModelAnimator.Stance;

/**
 * Runnable self-check for the figure animations: what each pose does over time, and that kit
 * changes blend instead of snapping. Pure Java, no game: {@code ./gradlew animatorCheck}.
 */
public final class AnimatorCheck {
    private AnimatorCheck() {}

    private static int checks = 0;
    private static final float UP = -2.5f; // arm pitch beyond this points above the head

    public static void main(String[] args) {
        idleIsGentle();
        idleBreathes();
        stancesDiffer();
        cheerArmsUpAndHeadBack();
        cheerJumpsAndLands();
        cheerSpinsOnceThenFacesFront();
        sadHangsHead();
        sadShakesHeadSometimes();
        bounceStaysAboveGround();
        transitionBlendsWithOvershoot();
        easingEnds();
        blendEndpoints();
        System.out.println("AnimatorCheck: " + checks + " checks passed");
    }

    private static Pose at(Anim anim, Stance stance, float t) {
        return VtlModelAnimator.pose(anim, stance, t);
    }

    private static void idleIsGentle() {
        float maxLift = 0, maxHead = 0;
        for (float t = 0; t < 12; t += 0.05f) {
            Pose p = at(Anim.IDLE, Stance.NONE, t);
            maxLift = Math.max(maxLift, Math.abs(p.lift()));
            maxHead = Math.max(maxHead, Math.abs(p.headPitch()));
        }
        check(maxLift <= 0.03f, "idle bobs at most 0.03 blocks, got " + maxLift);
        check(maxHead <= 0.15f, "idle head nods at most 0.15 rad, got " + maxHead);
    }

    private static void idleBreathes() {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float t = 0; t < 6; t += 0.05f) {
            float lift = at(Anim.IDLE, Stance.NONE, t).lift();
            min = Math.min(min, lift);
            max = Math.max(max, lift);
        }
        check(max - min >= 0.01f, "idle is never frozen: the body moves at least 0.01 blocks");
    }

    private static void stancesDiffer() {
        Pose sword = at(Anim.IDLE, Stance.SWORD_GUARD, 1f);
        Pose mace = at(Anim.IDLE, Stance.MACE_RAISE, 1f);
        Pose shield = at(Anim.IDLE, Stance.SHIELD_UP, 1f);
        Pose bow = at(Anim.IDLE, Stance.BOW_DRAW, 1f);
        Pose none = at(Anim.IDLE, Stance.NONE, 1f);
        check(sword.rightArmPitch() < -0.6f && sword.rightArmPitch() > -1.6f, "sword guard: weapon arm forward");
        check(mace.rightArmPitch() < -2.0f, "mace: weapon arm raised over the head");
        check(shield.leftArmPitch() < -0.7f, "shield: off-hand arm raised");
        check(bow.rightArmPitch() < -1.2f && bow.leftArmPitch() < -1.2f, "bow: both arms up at shoulder height");
        check(Math.abs(none.rightArmPitch()) < 0.25f, "no stance: arms down");
    }

    private static void cheerArmsUpAndHeadBack() {
        for (float t = 0.2f; t < 5; t += 0.1f) {
            Pose p = at(Anim.CHEER, Stance.NONE, t);
            if (!(p.rightArmPitch() < UP && p.leftArmPitch() < UP && p.headPitch() < -0.15f)) {
                check(false, "cheer at " + t + "s: both arms above the head, head tipped back");
            }
        }
        check(true, "cheer: both arms above the head and head back, all the time");
    }

    private static void cheerJumpsAndLands() {
        float max = 0, min = Float.MAX_VALUE;
        for (float t = 0; t < 3; t += 0.02f) {
            float lift = at(Anim.CHEER, Stance.NONE, t).lift();
            max = Math.max(max, lift);
            min = Math.min(min, lift);
        }
        check(max >= 0.4f, "cheer jumps at least 0.4 blocks, got " + max);
        check(min >= -0.001f && min <= 0.02f, "cheer lands on the ground between jumps, lowest " + min);
    }

    private static void cheerSpinsOnceThenFacesFront() {
        check(Math.abs(at(Anim.CHEER, Stance.NONE, 0f).spin()) < 0.5f, "cheer starts facing front");
        check(Math.abs(at(Anim.CHEER, Stance.NONE, VtlModelAnimator.CHEER_SPIN_S).spin() - 360f) < 0.5f, "one full turn at the end of the spin");
        float later = at(Anim.CHEER, Stance.NONE, 4f).spin();
        check(Math.abs(later - 360f) < 20f, "afterwards it faces front again (sways at most 20 degrees), got " + later);
    }

    private static void sadHangsHead() {
        for (float t = 0; t < 8; t += 0.1f) {
            Pose p = at(Anim.SAD, Stance.NONE, t);
            if (p.headPitch() < 0.55f || p.rightArmPitch() < -0.25f || p.rightArmPitch() > 0.35f || p.lift() > 0.001f) {
                check(false, "sad at " + t + "s: head down, arms hanging, never lifted");
            }
        }
        check(true, "sad: head down, arms hanging, slumped");
    }

    private static void sadShakesHeadSometimes() {
        float maxYaw = 0;
        boolean stillMoment = false;
        for (float t = 0; t < 8; t += 0.05f) {
            float yaw = Math.abs(at(Anim.SAD, Stance.NONE, t).headYaw());
            maxYaw = Math.max(maxYaw, yaw);
            if (yaw < 0.02f) stillMoment = true;
        }
        check(maxYaw >= 0.2f && stillMoment, "sad shakes the head now and then, not all the time");
    }

    private static void bounceStaysAboveGround() {
        float min = Float.MAX_VALUE, max = 0;
        for (float t = 0; t < 4; t += 0.02f) {
            float lift = at(Anim.WAIT_BOUNCE, Stance.NONE, t).lift();
            min = Math.min(min, lift);
            max = Math.max(max, lift);
        }
        check(min >= -0.001f, "waiting hops never go below the ground, lowest " + min);
        check(max >= 0.25f, "waiting hops reach at least 0.25 blocks, got " + max);
    }

    private static void transitionBlendsWithOvershoot() {
        Pose from = at(Anim.IDLE, Stance.NONE, 0f);
        Pose to = at(Anim.IDLE, Stance.MACE_RAISE, 0f);
        VtlModelAnimator.Transition tr = new VtlModelAnimator.Transition(from, 10f);
        check(tr.apply(to, 10f).equals(from), "at the start of a change the old pose shows");
        check(tr.apply(to, 10f + VtlModelAnimator.TRANSITION_S).equals(to), "after the change time the new pose shows");
        float mid = tr.apply(to, 10f + VtlModelAnimator.TRANSITION_S / 2).rightArmPitch();
        check(mid < from.rightArmPitch() && mid > to.rightArmPitch() - 0.6f, "halfway the arm is on its way up");
        boolean overshoot = false;
        for (float t = 0; t <= VtlModelAnimator.TRANSITION_S; t += 0.01f) {
            if (tr.apply(to, 10f + t).rightArmPitch() < to.rightArmPitch() - 0.01f) overshoot = true;
        }
        check(overshoot, "the swing goes a little past the guard pose and settles (overshoot)");
    }

    private static void easingEnds() {
        check(Math.abs(VtlModelAnimator.easeOutBack(0f)) < 1e-6 && Math.abs(VtlModelAnimator.easeOutBack(1f) - 1f) < 1e-6, "easeOutBack 0 -> 0, 1 -> 1");
        check(Math.abs(VtlModelAnimator.easeOutCubic(0.5f) - 0.875f) < 1e-6, "easeOutCubic(0.5) = 0.875");
    }

    private static void blendEndpoints() {
        Pose a = at(Anim.IDLE, Stance.NONE, 0f), b = at(Anim.CHEER, Stance.NONE, 0.5f);
        check(Pose.blend(a, b, 0f).equals(a) && Pose.blend(a, b, 1f).equals(b), "blend 0 and 1 are the two poses");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("AnimatorCheck failed: " + message);
    }
}
