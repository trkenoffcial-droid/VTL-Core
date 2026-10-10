package com.kevin.tiertagger.hub.anim;

/**
 * Added to Minecraft's humanoid render state by a mixin: a figure the hub draws carries its
 * {@link Pose} here, and the model applies it after its own animation. Every other entity has
 * none and is drawn exactly as before.
 */
public interface VtlPosed {
    Pose vtl$pose();

    void vtl$setPose(Pose pose);
}
