package com.kevin.tiertagger.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Turns an already laid-out text line back into a {@link Component}, keeping its styles. */
public final class StyledText {
    private StyledText() {}

    public static Component of(FormattedCharSequence sequence) {
        MutableComponent out = Component.empty();
        StringBuilder run = new StringBuilder();
        Style[] runStyle = {Style.EMPTY};
        sequence.accept((index, style, codePoint) -> {
            if (!style.equals(runStyle[0]) && !run.isEmpty()) {
                out.append(Component.literal(run.toString()).setStyle(runStyle[0]));
                run.setLength(0);
            }
            runStyle[0] = style;
            run.appendCodePoint(codePoint);
            return true;
        });
        if (!run.isEmpty()) out.append(Component.literal(run.toString()).setStyle(runStyle[0]));
        return out;
    }
}
