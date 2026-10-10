package com.kevin.tiertagger.mixin;

import com.kevin.tiertagger.dev.Trailer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * End of a rendered frame, GUI included: where the development-only trailer recorder reads the
 * picture. Does nothing unless the trailer is being recorded.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
    private void vtl$trailerFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        Trailer.onFrame();
    }
}
