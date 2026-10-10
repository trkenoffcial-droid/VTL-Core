package com.kevin.tiertagger.hub;

import com.kevin.tiertagger.TierTagger;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;

/**
 * The bundled "Block-Accurate: 3D Item Models" resource pack (teyokuma, MIT): off unless the
 * player turns it on, here or in Minecraft's Resource Packs screen.
 */
public final class ItemPack {
    private ItemPack() {}

    private static final String PATH = "block_accurate";

    public static void register() {
        FabricLoader.getInstance().getModContainer("vtl").ifPresent(mod ->
                ResourceManagerHelper.registerBuiltinResourcePack(HubTheme.vtl(PATH), mod,
                        Component.translatable("vtl.pack.blockAccurate"), ResourcePackActivationType.NORMAL));
    }

    /** The pack's id in the repository (Fabric names built-in packs after their identifier). */
    private static String id(PackRepository repo) {
        for (String id : repo.getAvailableIds()) {
            if (id.endsWith(PATH)) return id;
        }
        return null;
    }

    public static boolean isActive() {
        PackRepository repo = Minecraft.getInstance().getResourcePackRepository();
        String id = id(repo);
        return id != null && repo.getSelectedIds().contains(id);
    }

    /** Turns the pack on or off and reloads resources if that changed anything. */
    public static void apply(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        PackRepository repo = mc.getResourcePackRepository();
        String id = id(repo);
        if (id == null) {
            TierTagger.getLogger().warn("The bundled 3D item pack is not available");
            return;
        }
        if (repo.getSelectedIds().contains(id) == on) return;
        if (on) repo.addPack(id);
        else repo.removePack(id);
        mc.options.updateResourcePacks(repo);
    }
}
