package com.kevin.tiertagger.compat;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The item that stands for the Spear kit. Spears exist from 1.21.11; older games show a trident,
 * the closest long thrusting weapon they have.
 */
public final class SpearItem {
    private SpearItem() {}

    public static Item get() {
        //? if >=1.21.11 {
        return Items.NETHERITE_SPEAR;
        //?} else {
        /*return Items.TRIDENT;
        *///?}
    }

    /** Whether this game has spears, so the Spear Mace kit can be played. */
    public static boolean available() {
        //? if >=1.21.11 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }
}
