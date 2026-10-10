package com.kevin.tiertagger.mixin;

import com.kevin.tiertagger.hub.anim.Pose;
import com.kevin.tiertagger.hub.anim.VtlPosed;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives every humanoid render state a place for a VTL pose (null unless the hub set one). */
@Mixin(HumanoidRenderState.class)
public class MixinHumanoidRenderState implements VtlPosed {
    @Unique
    private Pose vtl$pose;

    @Override
    public Pose vtl$pose() {
        return vtl$pose;
    }

    @Override
    public void vtl$setPose(Pose pose) {
        this.vtl$pose = pose;
    }
}
