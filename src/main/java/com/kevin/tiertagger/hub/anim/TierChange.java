package com.kevin.tiertagger.hub.anim;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What a test result means for the player: up, down, the same, a first tier, or none. */
public final class TierChange {
    private TierChange() {}

    public enum Outcome {
        PROMOTED, DEMOTED, KEPT, FIRST_TIER, NO_TIER,
        /** The bot did not say the tier before the test (bot older than V38). */
        UNKNOWN;

        public boolean celebrates() {
            return this == PROMOTED || this == FIRST_TIER;
        }

        public boolean mourns() {
            return this == DEMOTED || this == NO_TIER;
        }
    }

    /** Lowest to highest. */
    private static final List<String> LADDER = List.of("LT5", "HT5", "LT4", "HT4", "LT3", "HT3", "LT2", "HT2", "LT1", "HT1");
    private static final Pattern CODE = Pattern.compile("(HT|LT)([1-5])", Pattern.CASE_INSENSITIVE);

    /** {@code "HT3"} from anything that contains a tier code, or null. */
    public static String code(String raw) {
        if (raw == null) return null;
        Matcher m = CODE.matcher(raw);
        return m.find() ? (m.group(1) + m.group(2)).toUpperCase(Locale.ROOT) : null;
    }

    /** Position on the ladder (LT5 = 0, HT1 = 9), or -1. */
    public static int rank(String tier) {
        String c = code(tier);
        return c == null ? -1 : LADDER.indexOf(c);
    }

    /**
     * @param previous the tier before the test: a code, {@code "NONE"} for no tier, or null when unknown
     * @param now      the tier the tester gave, or null / {@code "NONE"} for none
     */
    public static Outcome of(String previous, String now) {
        if (code(now) == null) return Outcome.NO_TIER;
        if (previous == null) return Outcome.UNKNOWN;
        if (code(previous) == null) return Outcome.FIRST_TIER;
        int before = rank(previous), after = rank(now);
        return after > before ? Outcome.PROMOTED : after < before ? Outcome.DEMOTED : Outcome.KEPT;
    }
}
