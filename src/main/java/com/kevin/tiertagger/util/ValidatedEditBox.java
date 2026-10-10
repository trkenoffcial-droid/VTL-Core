package com.kevin.tiertagger.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Predicate;

/** An {@link EditBox} with a validity rule; the text turns red while it does not match. */
public class ValidatedEditBox extends EditBox {
    private static final int VALID = 0xFFE0E0E0;
    private static final int INVALID = 0xFFFF5555;

    private final Predicate<String> validator;

    public ValidatedEditBox(int x, int y, int width, int height, String initial, String hint,
                            Predicate<String> validator, int maxLength) {
        super(Minecraft.getInstance().font, x, y, width, height, Component.literal(hint));
        this.validator = validator;
        this.setMaxLength(maxLength);
        this.setHint(Component.literal(hint));
        this.setValue(initial);
        this.setResponder(text -> this.setTextColor(isValid() ? VALID : INVALID));
    }

    public boolean isValid() {
        return this.validator.test(this.getValue());
    }

    public String getText() {
        return this.getValue();
    }

    public void setText(String text) {
        this.setValue(text);
    }
}
