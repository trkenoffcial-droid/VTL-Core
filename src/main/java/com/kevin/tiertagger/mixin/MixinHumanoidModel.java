package com.kevin.tiertagger.mixin;

import com.kevin.tiertagger.hub.anim.Pose;
import com.kevin.tiertagger.hub.anim.VtlPosed;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After the model has posed itself, a figure drawn by the VTL hub (its render state carries a
 * {@link Pose}) gets the hub's angles for head, arms and legs. The armour models are posed from
 * the same render state, so the armour follows. Nothing changes for any other entity.
 */
@Mixin(HumanoidModel.class)
public abstract class MixinHumanoidModel {
    @Shadow @Final public ModelPart head;
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Shadow @Final public ModelPart rightLeg;
    @Shadow @Final public ModelPart leftLeg;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void vtl$applyPose(HumanoidRenderState state, CallbackInfo ci) {
        if (!(state instanceof VtlPosed posed)) return;
        Pose p = posed.vtl$pose();
        if (p == null) return;
        head.xRot = p.headPitch();
        head.yRot = p.headYaw();
        head.zRot = p.headRoll();
        rightArm.xRot = p.rightArmPitch();
        rightArm.yRot = p.rightArmYaw();
        rightArm.zRot = p.rightArmRoll();
        // the model mirrors the left side: outwards is negative roll there
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
