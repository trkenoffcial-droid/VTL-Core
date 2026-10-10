package com.kevin.tiertagger.hub.anim;

import com.kevin.tiertagger.hub.anim.VtlKitRegistry.Loadout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.function.Supplier;

/**
 * A player figure for GUI screens, built from scratch: no entity and no world needed, so it works
 * on the title screen too and never touches the real player. Skin, the kit's armour and items,
 * and a {@link Pose} for its body language; drawn the way the inventory screen draws the player.
 */
public final class VtlFigure {
    private VtlFigure() {}

    /** Full brightness, as the inventory screen uses. */
    private static final int LIGHT = 15728880;

    /**
     * Draws the figure standing at the bottom middle of {@code x0..x1, y0..y1}, {@code height}
     * pixels tall. Keep head room in the box for jumps: whatever leaves it is cut off.
     *
     * @param yaw extra turn in degrees (dragging, a three-quarter view)
     */
    public static void render(GuiGraphics g, int x0, int y0, int x1, int y1, float height,
                              Supplier<PlayerSkin> skin, Loadout kit, Pose pose, float yaw, float seconds) {
        AvatarRenderState s = state(skin.get(), kit, pose, yaw, seconds);
        float scale = height / 1.8f;
        // The translation is applied before the figure is flipped upright, so +y moves it down the
        // screen. The inventory uses half the figure's height to centre it; here the feet go to the
        // bottom edge of the box (half the box down) and a lift moves it back up.
        float boxHalf = (y1 - y0) / 2f / scale;
        Vector3f translation = new Vector3f(0, boxHalf - 0.04f - pose.lift(), 0);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).rotateX((float) Math.toRadians(pose.tilt()));
        Quaternionf camera = new Quaternionf();
        g.submitEntityRenderState(s, scale, translation, rotation, camera, x0, y0, x1, y1);
    }

    /** A fresh render state every frame: the GUI keeps the one it was given until it is drawn. */
    private static AvatarRenderState state(PlayerSkin skin, Loadout kit, Pose pose, float yaw, float seconds) {
        Minecraft mc = Minecraft.getInstance();
        AvatarRenderState s = new AvatarRenderState();
        s.entityType = EntityType.PLAYER;
        s.skin = skin;
        s.showHat = s.showJacket = s.showLeftPants = s.showRightPants = s.showLeftSleeve = s.showRightSleeve = true;
        s.showCape = false;
        s.lightCoords = LIGHT;
        s.outlineColor = 0;
        s.ageInTicks = seconds * 20f;
        s.boundingBoxWidth = 0.6f;
        s.boundingBoxHeight = 1.8f;
        s.eyeHeight = 1.62f;
        s.scale = 1f;
        s.ageScale = 1f;
        s.bodyRot = 180f + yaw + pose.spin();
        s.yRot = 0f;
        s.xRot = 0f;
        s.pose = net.minecraft.world.entity.Pose.STANDING;
        s.mainArm = HumanoidArm.RIGHT;
        s.shadowPieces.clear();

        s.headEquipment = kit.headStack();
        s.chestEquipment = kit.chestStack();
        s.legsEquipment = kit.legsStack();
        s.feetEquipment = kit.feetStack();
        hold(mc, s, kit.mainHand(), true);
        hold(mc, s, kit.offHand(), false);

        ((VtlPosed) s).vtl$setPose(pose);
        return s;
    }

    private static void hold(Minecraft mc, AvatarRenderState s, ItemStack stack, boolean right) {
        ItemStack copy = stack == null ? ItemStack.EMPTY : stack.copy();
        if (right) {
            s.rightHandItemStack = copy;
            s.rightArmPose = copy.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
            if (!copy.isEmpty()) mc.getItemModelResolver().updateForTopItem(s.rightHandItemState, copy, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, mc.level, null, 0);
        } else {
            s.leftHandItemStack = copy;
            s.leftArmPose = copy.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
            if (!copy.isEmpty()) mc.getItemModelResolver().updateForTopItem(s.leftHandItemState, copy, ItemDisplayContext.THIRD_PERSON_LEFT_HAND, mc.level, null, 0);
        }
    }
}
