package com.kevin.tiertagger.hub.anim;

import com.kevin.tiertagger.hub.Kit;
import com.kevin.tiertagger.hub.anim.VtlModelAnimator.Stance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.util.EnumMap;
import java.util.Map;

/**
 * What a figure wears and holds for each kit, and how it stands. The one place for it: the hub's
 * figure, the result screen and the card art all use this.
 */
public final class VtlKitRegistry {
    private VtlKitRegistry() {}

    public record Loadout(Item head, Item chest, Item legs, Item feet, ItemStack mainHand, ItemStack offHand, Stance stance) {
        /** The same gear in fresh stacks (a render state keeps its own references). */
        public ItemStack headStack() {
            return new ItemStack(head);
        }

        public ItemStack chestStack() {
            return new ItemStack(chest);
        }

        public ItemStack legsStack() {
            return new ItemStack(legs);
        }

        public ItemStack feetStack() {
            return new ItemStack(feet);
        }
    }

    private static Map<Kit, Loadout> loadouts;

    public static Loadout of(Kit kit) {
        if (loadouts == null) loadouts = build(); // items exist only once the game has bootstrapped
        return loadouts.get(kit == null ? Kit.SWORD : kit);
    }

    private static Map<Kit, Loadout> build() {
        Map<Kit, Loadout> map = new EnumMap<>(Kit.class);
        map.put(Kit.AXE, diamond(new ItemStack(Items.DIAMOND_AXE), new ItemStack(Items.SHIELD), Stance.SHIELD_UP));
        map.put(Kit.VANILLA, netherite(new ItemStack(Items.END_CRYSTAL), new ItemStack(Items.TOTEM_OF_UNDYING), Stance.CRYSTAL_HOLD));
        map.put(Kit.MACE, netherite(new ItemStack(Items.MACE), new ItemStack(Items.WIND_CHARGE), Stance.MACE_RAISE));
        map.put(Kit.SWORD, diamond(new ItemStack(Items.DIAMOND_SWORD), ItemStack.EMPTY, Stance.SWORD_GUARD));
        map.put(Kit.NETHOP, netherite(new ItemStack(Items.NETHERITE_SWORD), new ItemStack(Items.TOTEM_OF_UNDYING), Stance.SWORD_GUARD));
        ItemStack healing = new ItemStack(Items.SPLASH_POTION);
        healing.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRONG_HEALING));
        map.put(Kit.POT, diamond(healing, new ItemStack(Items.DIAMOND_SWORD), Stance.POT_READY));
        map.put(Kit.UHC, diamond(new ItemStack(Items.GOLDEN_APPLE), new ItemStack(Items.SHIELD), Stance.SHIELD_UP));
        map.put(Kit.SMP, netherite(new ItemStack(Items.BOW), new ItemStack(Items.ENDER_PEARL), Stance.BOW_DRAW));
        return map;
    }

    private static Loadout diamond(ItemStack main, ItemStack off, Stance stance) {
        return new Loadout(Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, main, off, stance);
    }

    private static Loadout netherite(ItemStack main, ItemStack off, Stance stance) {
        return new Loadout(Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS, main, off, stance);
    }
}
