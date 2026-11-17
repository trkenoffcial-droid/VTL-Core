package com.kevin.tiertagger.mixin;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.queue.QueueWebSocket;
import com.kevin.tiertagger.util.UpdateCheck;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(TitleScreen.class)
public class MixinTitleScreen extends Screen {
    /** The prompt shows at most once per game start. */
    @Unique
    private static final AtomicBoolean hasShownPrompt = new AtomicBoolean(false);

    protected MixinTitleScreen(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    public void showUpdateScreen(CallbackInfo ci) {
        if (hasShownPrompt.get()) return;

        if (TierTagger.isObsolete()) {
            hasShownPrompt.set(true);
            Minecraft.getInstance().setScreen(new ConfirmScreen(
                    b -> {
                        if (b) {
                            Minecraft.getInstance().stop();
                        } else {
                            Minecraft.getInstance().setScreen(this);
                        }
                    },
                    Component.translatable("tiertagger.obsolete.title"),
                    Component.translatable("tiertagger.obsolete.desc"),
                    Component.translatable("menu.quit"),
                    Component.translatable("tiertagger.outdated.ignore")
            ));
            return;
        }

        // The check runs in the background; until it has answered, a later title screen asks again.
        UpdateCheck.Latest update = TierTagger.getLatestUpdate();
        if (update == null) return;
        hasShownPrompt.set(true);

        String current = QueueWebSocket.modVersionPlain();
        Component newVersion = Component.literal(update.version()).withStyle(ChatFormatting.GREEN);
        Component oldVersion = Component.literal(current == null ? "?" : current);
        Minecraft.getInstance().setScreen(new ConfirmScreen(
                b -> {
                    if (b) {
                        java.net.URI uri = java.net.URI.create(UpdateCheck.safeUrl(update.url()));
                        Util.getPlatform().openUri(uri);
                    }
                    Minecraft.getInstance().setScreen(this);
                },
                Component.translatable("vtl.update.title"),
                Component.translatable("vtl.update.desc", newVersion, oldVersion),
                Component.translatable("vtl.update.download"),
                Component.translatable("vtl.update.later")
        ));
    }
}
