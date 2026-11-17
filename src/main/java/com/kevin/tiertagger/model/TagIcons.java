package com.kevin.tiertagger.model;

/**
 * Kit icons that only Viet TierList has. Each is a character of the mod's font
 * ({@code assets/minecraft/font/default.json}) drawn in front of the tier on name tags.
 * Kept free of Minecraft classes so the headless checks can read it.
 */
public final class TagIcons {
    private TagIcons() {}

    /** Spear Mace: the website's spear icon (tier_icons/spear.svg) at 32 px. */
    public static final char SPEAR = '\uE709';

    /** The kit colour of Spear on the website and in the Hub. */
    public static final int SPEAR_COLOR = 0xB9A9CC;
}
