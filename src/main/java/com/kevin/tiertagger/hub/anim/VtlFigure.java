package com.kevin.tiertagger.hub.anim;

import com.kevin.tiertagger.compat.GuiPose;
import com.kevin.tiertagger.hub.anim.VtlKitRegistry.Loadout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.geom.ModelPart;
//? if >=1.21.2
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Quaternionf;
import org.joml.Vector3f;
//? if >=1.21.2 && <1.21.6 {
/*import com.kevin.tiertagger.mixin.AccessorEntityRenderDispatcher;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import java.util.Map;
*///?}

import java.util.function.Supplier;

/**
 * A player figure for GUI screens, built from scratch: no entity and no world needed, so it works
 * on the title screen too and never touches the real player. Skin, the kit's armour and items,
 * and a {@link Pose} for its body language; drawn the way the inventory screen draws the player.
 */
public final class VtlFigure {
    private VtlFigure() {}

    /** Full brightness, as the inventory screen uses. */
    static final int LIGHT = 15728880;

    /**
     * Draws the figure standing at the bottom middle of {@code x0..x1, y0..y1}, {@code height}
     * pixels tall. Keep head room in the box for jumps: whatever leaves it is cut off.
     *
     * @param yaw extra turn in degrees (dragging, a three-quarter view)
     */
    public static void render(GuiGraphics g, int x0, int y0, int x1, int y1, float height,
                              Supplier<PlayerSkin> skin, Loadout kit, Pose pose, float yaw, float seconds) {
        float scale = height / 1.8f;
        // The translation is applied before the figure is flipped upright, so +y moves it down the
        // screen. The inventory uses half the figure's height to centre it; here the feet go to the
        // bottom edge of the box (half the box down) and a lift moves it back up.
        float boxHalf = (y1 - y0) / 2f / scale;
        Vector3f translation = new Vector3f(0, boxHalf - 0.04f - pose.lift(), 0);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).rotateX((float) Math.toRadians(pose.tilt()));
        // over what is behind it, under what comes next, on every GUI
        GuiPose.layer(g);
        //? if >=1.21.6 {
        AvatarRenderState s = VtlFigureState.of(skin.get(), kit, pose, yaw, seconds);
        Quaternionf camera = new Quaternionf();
        g.submitEntityRenderState(s, scale, translation, rotation, camera, x0, y0, x1, y1);
        //?} elif >=1.21.2 {
        /*AvatarRenderState s = VtlFigureState.of(skin.get(), kit, pose, yaw, seconds);
        // before 1.21.6 the GUI drew an entity on the spot, as InventoryScreen#renderEntityInInventory did
        PlayerRenderer renderer = playerRenderer(s.skin);
        g.enableScissor(x0, y0, x1, y1);
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate((x0 + x1) / 2f, (y0 + y1) / 2f, 50f);
        ps.scale(scale, scale, -scale);
        ps.translate(translation.x, translation.y, translation.z);
        ps.mulPose(rotation);
        g.flush();
        Lighting.setupForEntityInInventory();
        g.drawSpecial(buffers -> renderer.render(s, ps, buffers, LIGHT));
        g.flush();
        ps.popPose();
        Lighting.setupFor3DItems();
        g.disableScissor();
        *///?} else {
        /*LegacyFigure.render(g, x0, y0, x1, y1, scale, translation, rotation, skin.get(), kit, pose, 180f + yaw + pose.spin());
        *///?}
        GuiPose.layer(g);
    }

    //? if >=1.21.2 && <1.21.6 {
    /*private static PlayerRenderer playerRenderer(PlayerSkin skin) {
        Map<?, ?> renderers = ((AccessorEntityRenderDispatcher) Minecraft.getInstance().getEntityRenderDispatcher()).vtl$playerRenderers();
        Object renderer = renderers.get(skin.model());
        return (PlayerRenderer) (renderer != null ? renderer : renderers.get(PlayerSkin.Model.WIDE));
    }
    *///?}

    /** Gives a humanoid model the angles of {@code p}. The model mirrors the left side: outwards is negative roll there. */
    public static void applyPose(Pose p, ModelPart head, ModelPart rightArm, ModelPart leftArm, ModelPart rightLeg, ModelPart leftLeg) {
        head.xRot = p.headPitch();
        head.yRot = p.headYaw();
        head.zRot = p.headRoll();
        rightArm.xRot = p.rightArmPitch();
        rightArm.yRot = p.rightArmYaw();
        rightArm.zRot = p.rightArmRoll();
        leftArm.xRot = p.leftArmPitch();
        leftArm.yRot = p.leftArmYaw();
        leftArm.zRot = -p.leftArmRoll();
        rightLeg.xRot = p.rightLegPitch();
        rightLeg.yRot = 0;
        rightLeg.zRot = p.rightLegRoll();
        leftLeg.xRot = p.leftLegPitch();
        leftLeg.yRot = 0;
        leftLeg.zRot = -p.leftLegRoll();
    }
}
