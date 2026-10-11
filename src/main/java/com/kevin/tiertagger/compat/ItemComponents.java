package com.kevin.tiertagger.compat;

//? if >=26 {
/*import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.item.Items;
*///?}

/**
 * Item stacks need their items' default data components. Before 26.1 items carry them from the start;
 * from 26.1 they are bound only when a world or a server loads its registries, and the hub can open on
 * the title screen. So the hub binds the vanilla defaults itself the first time it needs items there;
 * joining a world or a server binds them again from that game's registries, as usual.
 */
public final class ItemComponents {
    private ItemComponents() {}

    /** Makes sure item stacks can be created (does nothing before 26.1, or once components are bound). */
    public static void ensureBound() {
        //? if >=26 {
        /*if (Items.DIAMOND_SWORD.builtInRegistryHolder().areComponentsBound()) return;
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup())
                .forEach(pending -> pending.apply());
        *///?}
    }
}
