package com.kevin.tiertagger.compat;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.PlayerSkin;
//? if <1.21.9 {
/*import com.google.common.base.Suppliers;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
*///?}

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Player profile and skin lookups, whose Minecraft APIs moved in 1.21.9.
 */
public final class Players {
    private Players() {}

    /** The player's skin, loaded in the background. {@code secure} asks for signed textures (1.21.9 and newer). */
    public static Supplier<PlayerSkin> skin(GameProfile profile, boolean secure) {
        //? if >=1.21.9 {
        return Minecraft.getInstance().getSkinManager().createLookup(profile, secure);
        //?} else {
        /*return Minecraft.getInstance().getSkinManager().lookupInsecure(profile);
        *///?}
    }

    /** A player's profile, skin textures included, by name. Blocks: call it off the render thread. */
    public static Optional<GameProfile> fetchByName(String name) {
        //? if >=1.21.9 {
        return Minecraft.getInstance().services().profileResolver().fetchByName(name);
        //?} else {
        /*Optional<GameProfile> found = REPOSITORY.get().findProfileByName(name);
        if (found.isEmpty()) return found;
        ProfileResult full = Minecraft.getInstance().getMinecraftSessionService().fetchProfile(found.get().getId(), false);
        return Optional.of(full != null ? full.profile() : found.get());
        *///?}
    }

    //? if <1.21.9 {
    /*// before 1.21.9 the client has no name lookup of its own outside a world
    private static final Supplier<GameProfileRepository> REPOSITORY = Suppliers.memoize(() ->
            new YggdrasilAuthenticationService(Minecraft.getInstance().getProxy()).createProfileRepository());
    *///?}
}
