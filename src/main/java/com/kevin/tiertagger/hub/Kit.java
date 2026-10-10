package com.kevin.tiertagger.hub;

import java.util.Locale;

/**
 * The eight VTL kits in carousel order, with the mode id the bot sends, the label the website
 * uses and the kit's tag colour (RGB).
 */
public enum Kit {
    AXE("axe", "Axe", 0x5AD1FF),
    VANILLA("vanilla", "Vanilla", 0xB07CFF),
    MACE("mace", "Mace", 0xFF4FA3),
    SWORD("sword", "Sword", 0xFF8A3D),
    NETHOP("nethop", "NethOP", 0x5DFF8A),
    POT("pot", "Pot", 0xFF5A5A),
    UHC("uhc", "UHC", 0xFFC83D),
    SMP("smp", "SMP", 0x38D6B5);

    private final String id;
    private final String label;
    private final int color;

    Kit(String id, String label, int color) {
        this.id = id;
        this.label = label;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** RGB, no alpha. */
    public int color() {
        return color;
    }

    /** The kit for a protocol mode id, or null for a mode VTL does not test. */
    public static Kit byId(String id) {
        if (id == null) return null;
        String wanted = id.toLowerCase(Locale.ROOT);
        for (Kit kit : values()) {
            if (kit.id.equals(wanted)) return kit;
        }
        return null;
    }
}
