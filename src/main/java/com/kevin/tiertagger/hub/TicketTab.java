package com.kevin.tiertagger.hub;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.hub.widget.HubButton;
import com.kevin.tiertagger.queue.QueueClient;
import com.kevin.tiertagger.queue.QueueMessages;
import com.kevin.tiertagger.queue.QueueMessages.Attachment;
import com.kevin.tiertagger.queue.QueueMessages.Ticket;
import com.kevin.tiertagger.queue.QueueMessages.TicketClose;
import com.kevin.tiertagger.queue.QueueMessages.TicketMessage;
import com.kevin.tiertagger.queue.QueueTokenStore;
import com.kevin.tiertagger.queue.TicketLog;
import com.kevin.tiertagger.queue.TicketLog.Entry;
import com.kevin.tiertagger.queue.TicketLog.Snapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TICKET: the chat of the player's Discord test ticket, inside the game. What the player types
 * here is posted in the ticket; what the tester writes on Discord shows up here. A closed ticket
 * stays readable until the next one opens.
 */
public class TicketTab implements HubTab {
    private static final int LINE_H = 10;
    private static final int GAP = 6;
    private static final int PAD = 6;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    /** What was typed but not sent survives tab switches and the hub closing. */
    private static String draft = "";

    private VtlHubScreen screen;
    private int ax, ay, aw, ah;
    private int listX, listY, listW, listH;
    private int bottomY;
    private EditBox input;
    private HubButton sendButton;
    private boolean shownActive;
    private String shownTicketId;

    private record Block(Entry entry, List<FormattedCharSequence> lines, int height) {
    }

    private List<Block> blocks = List.of();
    private long laidOutVersion = -1;
    private int contentH;
    /** Pixels scrolled up from the newest message; 0 follows the chat. */
    private int scroll;
    private boolean newBelow;

    /** Whether the TICKET tab is on screen right now (so a new message needs no toast). */
    public static boolean isShowing() {
        return Minecraft.getInstance().screen instanceof VtlHubScreen hub && hub.current() == Id.TICKET;
    }

    private static TicketLog log() {
        return TierTagger.getQueue().ticketLog();
    }

    @Override
    public void init(VtlHubScreen screen, int x, int y, int w, int h) {
        this.screen = screen;
        ax = x;
        ay = y;
        aw = w;
        ah = h;
        var font = Minecraft.getInstance().font;
        Snapshot s = log().snapshot();
        shownActive = s.active();
        shownTicketId = s.ticket() == null ? null : s.ticket().ticketId();

        int side = screen.compact() ? 8 : 16;
        int headerH = screen.compact() ? 24 : 32;
        listX = x + side;
        listW = w - side * 2;
        listY = y + headerH + 8;
        bottomY = y + h - 26;
        listH = Math.max(20, bottomY - 6 - listY);

        // the Discord channel is deleted a few seconds after the ticket closes: no dead link then
        if (s.active() && QueueMessages.discordTicketUri(s.ticket().ticketUrl()) != null) {
            int bw = font.width(Component.translatable("vtl.ticket.openDiscord")) + 16;
            screen.add(new HubButton(x + w - side - bw, y + (headerH - 18) / 2 + 2, bw, 18,
                    Component.translatable("vtl.ticket.openDiscord"), HubButton.Style.GHOST, this::openDiscord));
        }
        if (s.active()) {
            int sendW = 70;
            input = new EditBox(font, listX, bottomY, listW - sendW - 6, 20, Component.translatable("vtl.ticket.input"));
            input.setMaxLength(QueueClient.MAX_TICKET_TEXT);
            input.setHint(Component.translatable("vtl.ticket.hint"));
            input.setValue(draft);
            input.setResponder(v -> draft = v);
            screen.add(input);
            sendButton = screen.add(new HubButton(listX + listW - sendW, bottomY, sendW, 20,
                    Component.translatable("vtl.ticket.send"), HubButton.Style.PRIMARY, this::send));
            screen.focus(input);
        } else if (s.ticket() == null) {
            screen.add(new HubButton(x + (w - 120) / 2, y + h / 2 + 24, 120, 20,
                    Component.translatable("vtl.ticket.goPlay"), HubButton.Style.GHOST, () -> screen.switchTo(Id.PLAY)));
        }
        laidOutVersion = -1;
    }

    // --- actions ---

    private void send() {
        if (input == null) return;
        String text = input.getValue().strip();
        Snapshot s = log().snapshot();
        if (text.isEmpty() || !s.active()) return;
        input.setValue("");
        draft = "";
        scroll = 0;
        TierTagger.getQueue().sendTicket(s.ticket().ticketId(), text);
    }

    private void openDiscord() {
        Ticket t = log().snapshot().ticket();
        URI uri = t == null ? null : QueueMessages.discordTicketUri(t.ticketUrl());
        if (uri != null) Util.getPlatform().openUri(uri);
    }

    @Override
    public void tick() {
        Snapshot s = log().snapshot();
        String id = s.ticket() == null ? null : s.ticket().ticketId();
        // opened, closed or replaced: the widgets change
        if (s.active() != shownActive || !java.util.Objects.equals(id, shownTicketId)) {
            screen.refresh();
            return;
        }
        log().markRead();
        if (sendButton != null && input != null) sendButton.active = !input.getValue().isBlank();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && input != null && input.isFocused()) {
            send();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX < listX || mouseX > listX + listW || mouseY < listY || mouseY > listY + listH) return false;
        scroll = clampScroll(scroll + (int) Math.round(vertical * LINE_H * 3));
        if (scroll == 0) newBelow = false;
        return true;
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(value, Math.max(0, contentH - listH)));
    }

    // --- layout ---

    private void layout(Snapshot s, Font font) {
        if (s.version() == laidOutVersion) return;
        int textW = listW - PAD * 2;
        List<Block> fresh = new ArrayList<>();
        int total = 0;
        for (Entry e : s.entries()) {
            TicketMessage m = e.message();
            List<FormattedCharSequence> lines = new ArrayList<>();
            String text = m.text() == null ? "" : m.text();
            for (String paragraph : text.split("\n", -1)) {
                if (paragraph.isEmpty()) continue;
                lines.addAll(font.split(Component.literal(paragraph), textW));
            }
            if (m.attachments() != null) {
                for (Attachment a : m.attachments()) {
                    lines.addAll(font.split(Component.translatable("vtl.ticket.file", a.name()), textW));
                }
            }
            if (e.state() == TicketLog.State.FAILED && e.error() != null) {
                lines.addAll(font.split(errorText(e.error()), textW));
            }
            int height = LINE_H * (1 + lines.size()) + GAP;
            fresh.add(new Block(e, lines, height));
            total += height;
        }
        // keep what the reader is looking at when new messages come in below
        if (laidOutVersion >= 0 && scroll > 0 && total > contentH) {
            scroll += total - contentH;
            newBelow = true;
        }
        blocks = fresh;
        contentH = total;
        scroll = clampScroll(scroll);
        laidOutVersion = s.version();
    }

    private static Component errorText(String error) {
        return error.matches("[a-z_]+")
                ? Component.translatable("vtl.ticket.error." + error)
                : Component.literal(error);
    }

    // --- drawing ---

    @Override
    public void renderBehind(GuiGraphics g, int mouseX, int mouseY, float delta) {
        var font = Minecraft.getInstance().font;
        Snapshot s = log().snapshot();
        if (s.ticket() == null) {
            drawEmpty(g, font);
            return;
        }
        drawHeader(g, font, s);

        HubTheme.frame(g, listX - 1, listY - 1, listW + 2, listH + 2, HubTheme.PANEL, HubTheme.LINE);
        layout(s, font);
        if (blocks.isEmpty()) {
            HubTheme.centered(g, font, Component.translatable("vtl.ticket.empty"), listX + listW / 2, listY + listH / 2 - 4, HubTheme.DIM, 1f);
        } else {
            g.enableScissor(listX, listY, listX + listW, listY + listH);
            int y = contentH <= listH ? listY + 4 : listY + listH - contentH + scroll;
            for (Block b : blocks) {
                if (y + b.height() >= listY && y <= listY + listH) drawBlock(g, font, b, y);
                y += b.height();
            }
            g.disableScissor();
        }

        if (!s.active()) drawClosedBanner(g, font, s.close());
    }

    private void drawHeader(GuiGraphics g, Font font, Snapshot s) {
        Ticket t = s.ticket();
        int side = listX - ax;
        int x = ax + side, y = ay + (screen.compact() ? 5 : 7);
        String kit = VtlHubScreen.kitLabel(t.mode()) + " · " + QueueMessages.regionLabel(t.region());
        HubTheme.text(g, font, Component.literal(kit.toUpperCase(Locale.ROOT)), x, y, HubTheme.TEXT, screen.compact() ? 1f : 1.25f);
        int titleW = HubTheme.width(font, Component.literal(kit.toUpperCase(Locale.ROOT)), screen.compact() ? 1f : 1.25f);

        // status chip
        boolean open = s.active();
        Component chip = Component.translatable(open ? "vtl.ticket.open" : "vtl.ticket.closed");
        int chipColor = open ? 0x5DFF8A : 0x9D8FB0;
        int cw = font.width(chip) + 10;
        int cx = x + titleW + 8;
        HubTheme.frame(g, cx, y - 1, cw, 12, HubTheme.argb(chipColor, 0x2E), HubTheme.argb(chipColor, 0x90));
        g.drawString(font, chip, cx + 5, y + 1, chipColor | 0xFF000000, false);

        if (!screen.compact() && t.testers() != null && !t.testers().isEmpty()) {
            Component testers = Component.translatable("vtl.ticket.testers", String.join(", ", t.testers()));
            g.drawString(font, HubTheme.ellipsize(font, testers.getString(), aw / 2), x, y + 15, HubTheme.DIM, false);
        }
    }

    private void drawBlock(GuiGraphics g, Font font, Block b, int y) {
        TicketMessage m = b.entry().message();
        boolean self = m.author() != null && m.author().self();
        String role = m.author() == null ? "staff" : m.author().role();
        if (self) g.fill(listX + 2, y - 2, listX + listW - 2, y + b.height() - GAP + 2, 0x1FA35BFF);

        int x = listX + PAD;
        String name = m.author() == null || m.author().name() == null ? "Discord" : m.author().name();
        g.drawString(font, name, x, y, nameColor(role, self), false);
        int after = x + font.width(name) + 6;
        String time = m.at() == null ? "" : TIME.format(Instant.ofEpochMilli(m.at()));
        g.drawString(font, time, after, y, 0xFF6F6480, false);
        after += font.width(time) + 6;
        if (b.entry().state() == TicketLog.State.PENDING) {
            g.drawString(font, Component.translatable("vtl.ticket.sending"), after, y, 0xFF8C7FA0, false);
        } else if (b.entry().state() == TicketLog.State.FAILED) {
            g.drawString(font, Component.translatable("vtl.ticket.notSent"), after, y, HubTheme.RED, false);
        }

        int textColor = "bot".equals(role) ? 0xFFB8AFC8 : b.entry().state() == TicketLog.State.PENDING ? 0xFFA79BBA : HubTheme.TEXT;
        int line = 0;
        int textLines = b.lines().size() - (b.entry().state() == TicketLog.State.FAILED && b.entry().error() != null ? 1 : 0);
        for (FormattedCharSequence seq : b.lines()) {
            int color = line < textLines ? textColor : HubTheme.RED;
            g.drawString(font, seq, x, y + LINE_H * (1 + line), color, false);
            line++;
        }
    }

    private static int nameColor(String role, boolean self) {
        if (self) return 0xFFC9A2FF;
        return switch (role == null ? "" : role) {
            case "tester" -> HubTheme.GOLD;
            case "bot" -> 0xFF9D8FB0;
            default -> 0xFF5AD1FF;
        };
    }

    private void drawClosedBanner(GuiGraphics g, Font font, TicketClose close) {
        int y = bottomY;
        boolean complete = close != null && "result".equals(close.reason());
        Component title;
        if (complete) {
            title = close.result() != null && close.result().tier() != null
                    ? Component.translatable("vtl.ticket.complete", close.result().tier())
                    : Component.translatable("vtl.ticket.completeNoTier");
        } else if (close != null && close.message() != null && !close.message().isBlank()) {
            title = Component.literal(close.message()); // e.g. skipped by staff, from the bot
        } else {
            title = Component.translatable("vtl.ticket.ended");
        }
        int color = complete ? HubTheme.GREEN : HubTheme.DIM;
        HubTheme.frame(g, listX, y, listW, 20, HubTheme.argb(color, 0x22), HubTheme.argb(color, 0x80));
        String text = HubTheme.ellipsize(font, title.getString(), listW - 12);
        HubTheme.centered(g, font, Component.literal(text), listX + listW / 2, y + 6, color | 0xFF000000, 1f);
    }

    private void drawEmpty(GuiGraphics g, Font font) {
        int cx = ax + aw / 2, cy = ay + ah / 2 - 24;
        HubTheme.centered(g, font, Component.translatable("vtl.ticket.noTicket"), cx, cy, HubTheme.TEXT, 1.5f);
        String hint = QueueTokenStore.isLinked() ? "vtl.ticket.noTicketHint" : "vtl.ticket.notLinked";
        HubTheme.centered(g, font, Component.translatable(hint), cx, cy + 20, HubTheme.DIM, 1f);
    }

    @Override
    public void renderFront(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!newBelow || scroll == 0) return;
        var font = Minecraft.getInstance().font;
        Component pill = Component.translatable("vtl.ticket.newBelow");
        int w = font.width(pill) + 12;
        int x = listX + (listW - w) / 2, y = listY + listH - 16;
        HubTheme.frame(g, x, y, w, 13, 0xF0261638, HubTheme.VIOLET);
        g.drawString(font, pill, x + 6, y + 3, HubTheme.TEXT, false);
    }
}
