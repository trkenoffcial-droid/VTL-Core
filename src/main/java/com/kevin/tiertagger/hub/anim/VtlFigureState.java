package com.kevin.tiertagger.hub.anim;

import com.kevin.tiertagger.hub.anim.VtlKitRegistry.Loadout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//? if >=1.21.5
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
//? if >=1.21.4 && <1.21.11 {
/*import net.minecraft.client.renderer.item.ItemStackRenderState;
*///?} elif <1.21.4 {
/*import net.minecraft.client.resources.model.BakedModel;
*///?}

/** The render state of a {@link VtlFigure}: what the game's player renderer draws. */
final class VtlFigureState {
    private VtlFigureState() {}

    /** A fresh render state every frame: the GUI keeps the one it was given until it is drawn. */
    static AvatarRenderState of(PlayerSkin skin, Loadout kit, Pose pose, float yaw, float seconds) {
        Minecraft mc = Minecraft.getInstance();
        AvatarRenderState s = new AvatarRenderState();
        //? if >=1.21.5
        s.entityType = EntityType.PLAYER;
        s.skin = skin;
        s.showHat = s.showJacket = s.showLeftPants = s.showRightPants = s.showLeftSleeve = s.showRightSleeve = true;
        s.showCape = false;
        //? if >=1.21.9 {
        s.lightCoords = VtlFigure.LIGHT;
        s.outlineColor = 0;
        //?}
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
        //? if >=1.21.9
        s.shadowPieces.clear();

        //? if >=1.21.4 {
        s.headEquipment = kit.headStack();
        s.chestEquipment = kit.chestStack();
        s.legsEquipment = kit.legsStack();
        s.feetEquipment = kit.feetStack();
        //?} else {
        /*s.headItem = kit.headStack();
        // the head layer draws only what has a model here; helmets are the armour layer's
        s.headItemModel = null;
        s.chestItem = kit.chestStack();
        s.legsItem = kit.legsStack();
        s.feetItem = kit.feetStack();
        *///?}
        hold(mc, s, kit.mainHand(), true);
        hold(mc, s, kit.offHand(), false);

        ((VtlPosed) s).vtl$setPose(pose);
        return s;
    }

    private static void hold(Minecraft mc, AvatarRenderState s, ItemStack stack, boolean right) {
        ItemStack copy = stack == null ? ItemStack.EMPTY : stack.copy();
        ItemDisplayContext context = right ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        //? if >=1.21.4 {
        HumanoidModel.ArmPose arm = copy.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        if (right) s.rightArmPose = arm;
        else s.leftArmPose = arm;
        //?} else {
        /*// before 1.21.4 the renderer works the arm poses out from the hands (the main hand is the right one)
        (right ? s.mainHandState : s.offhandState).isEmpty = copy.isEmpty();
        *///?}
        //? if >=1.21.11 {
        if (right) s.rightHandItemStack = copy;
        else s.leftHandItemStack = copy;
        if (!copy.isEmpty()) mc.getItemModelResolver().updateForTopItem(right ? s.rightHandItemState : s.leftHandItemState, copy, context, mc.level, null, 0);
        //?} elif >=1.21.5 {
        /*// before 1.21.11 the state has no stack: clear it so the last kit's item does not stay
        ItemStackRenderState item = right ? s.rightHandItem : s.leftHandItem;
        item.clear();
        if (!copy.isEmpty()) mc.getItemModelResolver().updateForTopItem(item, copy, context, mc.level, null, 0);
        *///?} elif >=1.21.4 {
        /*ItemStackRenderState item = right ? s.rightHandItem : s.leftHandItem;
        item.clear();
        if (!copy.isEmpty()) mc.getItemModelResolver().updateForTopItem(item, copy, context, !right, mc.level, null, 0);
        *///?} else {
        /*// before 1.21.4 the state holds the stack and its baked model
        BakedModel model = copy.isEmpty() ? null : mc.getItemRenderer().getModel(copy, mc.level, null, 0);
        if (right) {
            s.rightHandItem = copy;
            s.rightHandItemModel = model;
        } else {
            s.leftHandItem = copy;
            s.leftHandItemModel = model;
        }
        *///?}
    }
}
