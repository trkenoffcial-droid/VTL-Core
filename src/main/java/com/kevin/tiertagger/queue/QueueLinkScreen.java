package com.kevin.tiertagger.queue;

import com.kevin.tiertagger.TierTagger;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import com.kevin.tiertagger.util.ParentScreen;
import com.kevin.tiertagger.util.ValidatedEditBox;

/**
 * Front-end for the link flow: type the short code the Discord bot gave you.
 *
 * <p>This screen and the {@code /tiertagger link <code>} command both end up in
 * {@link QueueLinker}, so neither is a separate code path.</p>
 *
 * <p>The token itself is never shown here or anywhere else in the UI — there is deliberately
 * no way to read it back out of the mod.</p>
 */
public class QueueLinkScreen extends ParentScreen {
    private static final int FIELD_WIDTH = 120;
    private static final int MAX_CODE_LENGTH = 6;

    private ValidatedEditBox codeField;
    private Button linkButton;

    public QueueLinkScreen(Screen parent) {
        super(Component.translatable("tiertagger.queue.link.title"), parent);
    }

    @Override
    protected void init() {
        int centerY = this.height / 4 + 48;

        this.codeField = this.addWidget(new ValidatedEditBox(
                this.width / 2 - FIELD_WIDTH / 2, centerY, FIELD_WIDTH, 20,
                "", "ABC123",
                s -> s.matches("[A-Za-z0-9]{6}"), MAX_CODE_LENGTH));

        this.linkButton = this.addRenderableWidget(
                Button.builder(Component.translatable("tiertagger.queue.link.submit"), b -> submit())
                        .bounds(this.width / 2 - 100, centerY + 32, 200, 20)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
                        .bounds(this.width / 2 - 100, centerY + 58, 200, 20)
                        .build()
        );

        if (isLinked()) {
            this.addRenderableWidget(
                    Button.builder(Component.translatable("tiertagger.queue.unlink"), b -> {
                                QueueLinker.unlink();
                                this.onClose();
                            })
                            .bounds(this.width / 2 - 100, centerY + 84, 200, 20)
                            .build()
            );
        }

        this.setInitialFocus(this.codeField);
    }

    @Override
    public void tick() {
        super.tick();
        // The predicate above decides whether the code can be sent.
        this.linkButton.active = this.codeField.isValid();
    }

    private void submit() {
        if (!this.codeField.isValid()) return;

        QueueLinker.link(this.codeField.getText());
        // The outcome arrives asynchronously as a chat message, so step out of the way rather
        // than blocking the screen on it.
        this.onClose();
    }

    private static boolean isLinked() {
        return QueueTokenStore.isLinked();
    }

    /** "Linked as <Discord name>" once the server has said who we are, else just "Linked". */
    public static Component linkStatus() {
        if (!isLinked()) return Component.translatable("tiertagger.queue.notLinked");
        var hello = TierTagger.getQueue().hello();
        return hello != null && hello.discordName() != null
                ? Component.translatable("tiertagger.queue.linkedAs", hello.discordName())
                : Component.translatable("tiertagger.queue.linked");
    }

    @Override
    public void resize(int width, int height) {
        String code = this.codeField == null ? "" : this.codeField.getText();
        this.init(width, height);
        if (this.codeField != null) this.codeField.setText(code);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);

        // ARGB: since 1.21.6 a colour without an alpha byte is fully transparent and draws nothing.
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 16, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("tiertagger.queue.link.hint"),
                this.width / 2, this.height / 4 + 8, 0xFFA0A0A0);
        graphics.drawCenteredString(this.font, linkStatus(),
                this.width / 2, this.height / 4 + 20, isLinked() ? 0xFF55FF55 : 0xFFFFAA00);

        this.codeField.render(graphics, mouseX, mouseY, delta);
    }
}
