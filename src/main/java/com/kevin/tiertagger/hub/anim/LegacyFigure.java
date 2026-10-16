package com.kevin.tiertagger.hub.anim;

import com.kevin.tiertagger.hub.anim.VtlKitRegistry.Loadout;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * {@link VtlFigure} before 1.21.2, when entities had no render states yet: the player model, its
 * armour and its items drawn here, the way the player renderer and its layers draw them.
 */
final class LegacyFigure {
    private LegacyFigure() {}

    private static Models wide;
    private static Models slim;

    /** The baked models for one arm width. */
    private record Models(PlayerModel<LivingEntity> body, HumanoidModel<LivingEntity> inner, HumanoidModel<LivingEntity> outer) {
        static Models bake(boolean slim) {
            EntityModelSet set = Minecraft.getInstance().getEntityModels();
            PlayerModel<LivingEntity> body = new PlayerModel<>(set.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim);
            // a new model counts as a baby (a big head) until a renderer says otherwise
            body.young = false;
            return new Models(body,
                    new HumanoidModel<>(set.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_INNER_ARMOR : ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidModel<>(set.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_OUTER_ARMOR : ModelLayers.PLAYER_OUTER_ARMOR)));
        }
    }

    /** As InventoryScreen#renderEntityInInventory draws the player, clipped to {@code x0..x1, y0..y1}. */
    static void render(GuiGraphics g, int x0, int y0, int x1, int y1, float scale, Vector3f translation, Quaternionf rotation,
                       PlayerSkin skin, Loadout kit, Pose pose, float bodyRot) {
        Models models = models(skin.model() == PlayerSkin.Model.SLIM);
        g.enableScissor(x0, y0, x1, y1);
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate((x0 + x1) / 2f, (y0 + y1) / 2f, 50f);
        ps.scale(scale, scale, -scale);
        ps.translate(translation.x, translation.y, translation.z);
        ps.mulPose(rotation);
        g.flush();
        Lighting.setupForEntityInInventory();
        MultiBufferSource.BufferSource buffers = g.bufferSource();
        RenderSystem.runAsFancy(() -> draw(ps, buffers, models, skin, kit, pose, bodyRot));
        g.flush();
        ps.popPose();
        Lighting.setupFor3DItems();
        g.disableScissor();
    }

    private static Models models(boolean thin) {
        if (thin) return slim != null ? slim : (slim = Models.bake(true));
        return wide != null ? wide : (wide = Models.bake(false));
    }

    private static void draw(PoseStack ps, MultiBufferSource buffers, Models models, PlayerSkin skin, Loadout kit, Pose pose, float bodyRot) {
        PlayerModel<LivingEntity> body = models.body();
        ps.pushPose();
        // LivingEntityRenderer#render, with PlayerRenderer#scale
        ps.mulPose(Axis.YP.rotationDegrees(180f - bodyRot));
        ps.scale(-1f, -1f, 1f);
        ps.scale(0.9375f, 0.9375f, 0.9375f);
        ps.translate(0f, -1.501f, 0f);

        pose(body, pose);
        body.setAllVisible(true);
        body.renderToBuffer(ps, buffers.getBuffer(body.renderType(skin.texture())), VtlFigure.LIGHT, OverlayTexture.NO_OVERLAY, -1);

        // HumanoidArmorLayer's order, then the hands
        armour(ps, buffers, models, kit.chestStack(), EquipmentSlot.CHEST);
        armour(ps, buffers, models, kit.legsStack(), EquipmentSlot.LEGS);
        armour(ps, buffers, models, kit.feetStack(), EquipmentSlot.FEET);
        armour(ps, buffers, models, kit.headStack(), EquipmentSlot.HEAD);
        hand(ps, buffers, body, kit.mainHand(), HumanoidArm.RIGHT);
        hand(ps, buffers, body, kit.offHand(), HumanoidArm.LEFT);
        ps.popPose();
    }

    /** What PlayerModel#setupAnim and the pose mixin do on later versions. */
    private static void pose(PlayerModel<LivingEntity> m, Pose p) {
        m.head.resetPose();
        m.body.resetPose();
        m.rightArm.resetPose();
        m.leftArm.resetPose();
        m.rightLeg.resetPose();
        m.leftLeg.resetPose();
        VtlFigure.applyPose(p, m.head, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg);
        // the outer skin layer follows the part it covers
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
    }

    private static void armour(PoseStack ps, MultiBufferSource buffers, Models models, ItemStack stack, EquipmentSlot slot) {
        if (stack == null || !(stack.getItem() instanceof ArmorItem item) || item.getEquipmentSlot() != slot) return;
        // the leggings use the inner model, so they sit under the chestplate
        boolean inner = slot == EquipmentSlot.LEGS;
        HumanoidModel<LivingEntity> model = inner ? models.inner() : models.outer();
        models.body().copyPropertiesTo(model);
        model.setAllVisible(false);
        switch (slot) {
            case HEAD -> {
                model.head.visible = true;
                model.hat.visible = true;
            }
            case CHEST -> {
                model.body.visible = true;
                model.rightArm.visible = true;
                model.leftArm.visible = true;
            }
            case LEGS -> {
                model.body.visible = true;
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            case FEET -> {
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            default -> {}
        }
        // the kits wear plain diamond and netherite: no dye, no trim
        for (ArmorMaterial.Layer layer : item.getMaterial().value().layers()) {
            model.renderToBuffer(ps, buffers.getBuffer(RenderType.armorCutoutNoCull(layer.texture(inner))), VtlFigure.LIGHT, OverlayTexture.NO_OVERLAY, -1);
        }
        if (stack.hasFoil()) {
            model.renderToBuffer(ps, buffers.getBuffer(RenderType.armorEntityGlint()), VtlFigure.LIGHT, OverlayTexture.NO_OVERLAY, -1);
        }
    }

    /** ItemInHandLayer#renderArmWithItem. */
    private static void hand(PoseStack ps, MultiBufferSource buffers, PlayerModel<LivingEntity> body, ItemStack stack, HumanoidArm arm) {
        if (stack == null || stack.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        boolean left = arm == HumanoidArm.LEFT;
        ps.pushPose();
        body.translateToHand(arm, ps);
        ps.mulPose(Axis.XP.rotationDegrees(-90f));
        ps.mulPose(Axis.YP.rotationDegrees(180f));
        ps.translate((left ? -1 : 1) / 16f, 0.125f, -0.625f);
        ItemDisplayContext context = left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        mc.getItemRenderer().render(stack, context, left, ps, buffers, VtlFigure.LIGHT, OverlayTexture.NO_OVERLAY,
                mc.getItemRenderer().getModel(stack, mc.level, null, 0));
        ps.popPose();
    }
}
