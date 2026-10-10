package com.kevin.tiertagger.hub.widget;

import com.kevin.tiertagger.hub.Kit;
import com.kevin.tiertagger.hub.VtlHubScreen;
import com.kevin.tiertagger.hub.anim.Pose;
import com.kevin.tiertagger.hub.anim.VtlFigure;
import com.kevin.tiertagger.hub.anim.VtlKitRegistry;
import com.kevin.tiertagger.hub.anim.VtlModelAnimator;
import com.kevin.tiertagger.hub.anim.VtlModelAnimator.Anim;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.function.Supplier;

/**
 * The player in the middle of the PLAY tab, with their own skin, wearing the kit of the card that
 * is selected and standing in that kit's guard. Picking another kit swings into the new guard;
 * dragging turns the figure, letting go turns it back. Works on the title screen and in a world
 * alike (it never draws the real player entity).
 */
public final class PlayerView {
    /** Three-quarter view, like the card art. */
    private static final float BASE_YAW = 20f;

    private int x, y, w, h;
    private Supplier<PlayerSkin> skin;
    private Kit kit;
    private Pose shown;
    private VtlModelAnimator.Transition transition;
    private float dragYaw;
    private boolean dragging;
    private long lastFrame;
    private final long born = Util.getMillis();

    public void init(VtlHubScreen screen, int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.skin = screen.ownSkin();
    }

    private float now() {
        return (Util.getMillis() - born) / 1000f;
    }

    /** The kit to wear; a change swings into the new guard instead of snapping. */
    public void setKit(Kit kit) {
        if (kit == this.kit) return;
        if (this.kit != null && shown != null) transition = new VtlModelAnimator.Transition(shown, now());
        this.kit = kit;
    }

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        if (skin == null) return;
        float t = now();
        long millis = Util.getMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (millis - lastFrame) / 1000f);
        lastFrame = millis;
        if (!dragging) dragYaw *= (float) Math.exp(-3.0 * dt); // spring back to the front

        var loadout = VtlKitRegistry.of(kit);
        Pose target = VtlModelAnimator.pose(Anim.IDLE, loadout.stance(), t);
        Pose pose = transition != null ? transition.apply(target, t) : target;
        if (transition != null && transition.done(t)) transition = null;
        shown = pose;

        // head room above for the arms of a raised weapon; room at the sides for a shield
        int boxTop = y - h / 4;
        int boxW = Math.round(h * 0.9f);
        int cx = x + w / 2;
        VtlFigure.render(g, cx - boxW / 2, boxTop, cx + boxW / 2, y + h, h * 0.92f, skin, loadout, pose, BASE_YAW + dragYaw, t);
    }

    // --- turning by dragging ---

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x - w / 4.0 && mouseX <= x + w * 1.25 && mouseY >= y - h / 4.0 && mouseY <= y + h;
    }

    public boolean mouseClicked(double mouseX, double mouseY) {
        dragging = contains(mouseX, mouseY);
        return dragging;
    }

    public boolean mouseDragged(double dragX) {
        if (!dragging) return false;
        dragYaw = Math.max(-180f, Math.min(180f, dragYaw + (float) dragX * 1.5f));
        return true;
    }

    public boolean mouseReleased() {
        boolean was = dragging;
        dragging = false;
        return was;
    }
}
