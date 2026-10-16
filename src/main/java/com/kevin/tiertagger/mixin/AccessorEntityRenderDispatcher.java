package com.kevin.tiertagger.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** The player renderers by arm width: before 1.21.6 the hub figure is drawn through one of them. */
@Mixin(EntityRenderDispatcher.class)
public interface AccessorEntityRenderDispatcher {
    @Accessor("playerRenderers")
    Map<?, ?> vtl$playerRenderers();
}
