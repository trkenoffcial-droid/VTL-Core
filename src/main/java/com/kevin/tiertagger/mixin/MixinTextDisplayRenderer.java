package com.kevin.tiertagger.mixin;

import com.kevin.tiertagger.TierTagger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.DisplayRenderer;
//? if >=1.21.2
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.player.Player;
import com.kevin.tiertagger.util.StyledText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
//? if >=1.21.2 {
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
*///?}

import java.util.ArrayList;
import java.util.List;

@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public class MixinTextDisplayRenderer {
    //? if >=1.21.2 {
    // Replaces cachedInfo in the render state so that text positioning, background width,
    // and rendered contents all agree on the counter-appended line width
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Display$TextDisplay;Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;F)V",
            at = @At("RETURN"))
    private void addTier(Display.TextDisplay entity, TextDisplayEntityRenderState renderState, float partialTick, CallbackInfo ci) {
        if (renderState.cachedInfo != null) renderState.cachedInfo = vtl$withTier(entity, renderState.cachedInfo);
    }
    //?} else {
    /*// Before 1.21.2 the renderer lays the text out while it draws: the same swap, on the layout it gets
    @WrapOperation(method = "renderInner(Lnet/minecraft/world/entity/Display$TextDisplay;Lnet/minecraft/world/entity/Display$TextDisplay$TextRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Display$TextDisplay;cacheDisplay(Lnet/minecraft/world/entity/Display$TextDisplay$LineSplitter;)Lnet/minecraft/world/entity/Display$TextDisplay$CachedInfo;"))
    private Display.TextDisplay.CachedInfo addTier(Display.TextDisplay entity, Display.TextDisplay.LineSplitter splitter,
                                                  Operation<Display.TextDisplay.CachedInfo> original) {
        Display.TextDisplay.CachedInfo info = original.call(entity, splitter);
        return info == null ? null : vtl$withTier(entity, info);
    }
    *///?}

    /** {@code info} with the tier after the rider's name, or {@code info} itself when there is nothing to add. */
    @Unique
    private static Display.TextDisplay.CachedInfo vtl$withTier(Display.TextDisplay entity, Display.TextDisplay.CachedInfo info) {
        if (!TierTagger.getManager().getConfig().isEnabled()) return info;
        if (!(entity.getVehicle() instanceof Player player)) return info;

        List<Display.TextDisplay.CachedLine> lines = info.lines();
        for (int i = 0; i < lines.size(); i++) {
            final Display.TextDisplay.CachedLine line = lines.get(i);
            final Component lineText = StyledText.of(line.contents());
            final String lineString = lineText.getString();
            if (lineString.isBlank() || !lineString.contains(player.getScoreboardName())) continue;

            final Component modified = TierTagger.appendTier(
                    player.getUUID(),
                    player.getScoreboardName(),
                    lineText
            );
            if (modified == lineText) return info; // no pops or counter disabled

            final FormattedCharSequence modifiedSeq = modified.getVisualOrderText();
            final int newLineWidth = Minecraft.getInstance().font.width(modified);

            final List<Display.TextDisplay.CachedLine> newLines = new ArrayList<>(lines);
            newLines.set(i, new Display.TextDisplay.CachedLine(modifiedSeq, newLineWidth));

            final int newMaxWidth = newLines.stream()
                    .mapToInt(Display.TextDisplay.CachedLine::width)
                    .max().orElse(info.width());

            return new Display.TextDisplay.CachedInfo(newLines, newMaxWidth);
        }
        return info;
    }
}
