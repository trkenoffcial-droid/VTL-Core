package com.kevin.tiertagger.hub.anim;

import com.kevin.tiertagger.hub.anim.TierChange.Outcome;

/**
 * Runnable self-check for reading a test result as a promotion, a demotion and so on.
 * Pure Java, no game: part of {@code ./gradlew animatorCheck}.
 */
public final class TierChangeCheck {
    private TierChangeCheck() {}

    private static int checks = 0;

    public static void main(String[] args) {
        check(TierChange.of("HT3", "LT2") == Outcome.PROMOTED, "HT3 -> LT2 is a promotion (LT2 is higher)");
        check(TierChange.of("LT2", "HT3") == Outcome.DEMOTED, "LT2 -> HT3 is a demotion");
        check(TierChange.of("HT3", "HT3") == Outcome.KEPT, "same tier is kept");
        check(TierChange.of("NONE", "LT3") == Outcome.FIRST_TIER, "no tier before -> first tier");
        check(TierChange.of(null, "LT3") == Outcome.UNKNOWN, "previous tier not known (older bot) -> unknown");
        check(TierChange.of("HT3", "NONE") == Outcome.NO_TIER, "no tier given -> no tier");
        check(TierChange.of("HT3", null) == Outcome.NO_TIER, "missing tier -> no tier");
        check(TierChange.of("lt5", "ht5") == Outcome.PROMOTED, "case does not matter; HT5 is above LT5");
        check(TierChange.of("HT2", "LT1") == Outcome.PROMOTED, "HT2 -> LT1 is a promotion");
        check(TierChange.of("LT1", "HT1") == Outcome.PROMOTED, "LT1 -> HT1 is a promotion (HT1 is the top)");
        check(TierChange.of("RHT3", "HT3") == Outcome.KEPT, "a retired-style prefix still reads the tier");
        check(TierChange.rank("HT1") > TierChange.rank("LT1") && TierChange.rank("LT5") == 0 && TierChange.rank("bogus") < 0, "ladder order LT5 < ... < HT1");
        check(TierChange.code(" ht3 ").equals("HT3") && TierChange.code("NONE") == null && TierChange.code(null) == null, "codes are normalised");
        check(TierChange.of("HT3", "LT2").celebrates() && !TierChange.of("LT2", "HT3").celebrates() && TierChange.of("NONE", "LT3").celebrates(),
                "promotions and first tiers celebrate");
        check(TierChange.of("LT2", "HT3").mourns() && TierChange.of("HT3", "NONE").mourns() && !TierChange.of("HT3", "HT3").mourns()
                && !TierChange.of(null, "HT3").mourns(), "demotions and no tier are sad; kept and unknown are neither");
        System.out.println("TierChangeCheck: " + checks + " checks passed");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("TierChangeCheck failed: " + message);
    }
}
