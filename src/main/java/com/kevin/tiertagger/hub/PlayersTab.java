package com.kevin.tiertagger.hub;

import com.kevin.tiertagger.TierCache;
import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.hub.widget.HubButton;
import com.kevin.tiertagger.model.PlayerInfo;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * PLAYERS: look a player up on the selected tierlist and see their tiers, region and points.
 * In a world, the players around are one click away. The last result stays when switching tabs.
 */
public class PlayersTab implements HubTab {
    private enum State { IDLE, LOADING, FOUND, NOT_FOUND }

    private static State state = State.IDLE;
    private static String lastQuery = "";
    private static PlayerInfo lastInfo;
    private static Supplier<PlayerSkin> lastSkin;

    private VtlHubScreen screen;
    private int ax, ay, aw, ah;
    private EditBox search;
    private int resultX, resultY, skinW, skinH;

    @Override
    public void init(VtlHubScreen screen, int x, int y, int w, int h) {
        this.screen = screen;
        ax = x;
        ay = y;
        aw = w;
        ah = h;
        Minecraft mc = Minecraft.getInstance();

        int boxW = Math.min(220, w - 120);
        int top = y + 14;
        search = new EditBox(mc.font, x + (w - boxW - 86) / 2, top, boxW, 20, Component.translatable("vtl.players.search"));
        search.setMaxLength(32);
        search.setHint(Component.translatable("vtl.players.hint"));
        search.setFilter(s -> s.matches("[A-Za-z0-9_-]*"));
        search.setValue(lastQuery);
        screen.add(search);
        screen.add(new HubButton(search.getX() + boxW + 6, top, 80, 20, Component.translatable("vtl.players.search"),
                HubButton.Style.PRIMARY, () -> submit(search.getValue())));

        // players on this server, one click each
        if (mc.getConnection() != null) {
            List<String> names = mc.getConnection().getOnlinePlayers().stream()
                    .map(p -> p.getProfile().name())
                    .filter(n -> !n.equalsIgnoreCase(mc.getUser().getName()))
                    .sorted(String.CASE_INSENSITIVE_ORDER).limit(10).toList();
            int cx = x + 20, cy = top + 28;
            for (String name : names) {
                int bw = mc.font.width(name) + 12;
                if (cx + bw > x + w - 20) break;
                screen.add(new HubButton(cx, cy, bw, 14, Component.literal(name), HubButton.Style.GHOST, () -> {
                    search.setValue(name);
                    submit(name);
                }));
                cx += bw + 4;
            }
        }

        resultY = top + 52;
        skinH = Math.max(60, Math.min(160, y + h - resultY - 16));
        skinW = skinH / 2;
        resultX = x + Math.max(20, (w - 360) / 2);
        if (state == State.FOUND && lastSkin != null) {
            PlayerSkinWidget skin = new PlayerSkinWidget(skinW, skinH, mc.getEntityModels(), lastSkin);
            skin.setPosition(resultX, resultY);
            screen.add(skin);
        }
        screen.focus(search);
    }

    private void submit(String name) {
        if (name == null || name.isBlank() || state == State.LOADING) return;
        String query = name.trim();
        lastQuery = query;
        state = State.LOADING;
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<Supplier<PlayerSkin>> skin = CompletableFuture.supplyAsync(() -> {
            GameProfile profile = mc.services().profileResolver().fetchByName(query)
                    .orElseGet(() -> new GameProfile(UUID.randomUUID(), query));
            return mc.getSkinManager().createLookup(profile, true);
        });
        TierCache.searchPlayer(query)
                .thenCombine(skin, (info, skinLookup) -> {
                    mc.execute(() -> {
                        lastInfo = info;
                        lastSkin = skinLookup;
                        state = info == null || info.rankings() == null ? State.NOT_FOUND : State.FOUND;
                        if (mc.screen == screen) screen.refresh();
                    });
                    return null;
                })
                .exceptionally(t -> {
                    mc.execute(() -> {
                        state = State.NOT_FOUND;
                        if (mc.screen == screen) screen.refresh();
                    });
                    return null;
                });
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (search != null && search.isFocused() && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            submit(search.getValue());
            return true;
        }
        return false;
    }

    @Override
    public void renderBehind(GuiGraphics g, int mouseX, int mouseY, float delta) {
        HubTheme.frame(g, ax + 12, ay + 8, aw - 24, ah - 16, 0xB0100818, 0xFF2C1940);
    }

    @Override
    public void renderFront(GuiGraphics g, int mouseX, int mouseY, float delta) {
        var font = Minecraft.getInstance().font;
        int cx = ax + aw / 2;
        switch (state) {
            case IDLE -> HubTheme.centered(g, font, Component.translatable("vtl.players.idle"), cx, resultY + 30, HubTheme.DIM, 1f);
            case LOADING -> HubTheme.centered(g, font, Component.translatable("vtl.players.loading"), cx, resultY + 30, HubTheme.GOLD, 1f);
            case NOT_FOUND -> HubTheme.centered(g, font, Component.translatable("vtl.players.notFound", lastQuery), cx, resultY + 30, HubTheme.RED, 1f);
            case FOUND -> drawInfo(g, font);
        }
    }

    private void drawInfo(GuiGraphics g, net.minecraft.client.gui.Font font) {
        PlayerInfo info = lastInfo;
        if (info == null) return;
        int x = resultX + skinW + 18;
        int y = resultY;
        HubTheme.text(g, font, Component.literal(info.name()), x, y, 0xFFFFFFFF, 1.5f);
        y += 18;
        if (info.region() != null) {
            g.drawString(font, Component.translatable("vtl.players.region").append(Component.literal(" " + info.region())
                    .withStyle(s -> s.withColor(info.getRegionColor()))), x, y, HubTheme.DIM, false);
            y += 11;
        }
        PlayerInfo.PointInfo points = info.getPointInfo();
        g.drawString(font, Component.translatable("vtl.players.points").append(Component.literal(" " + info.points() + " ")
                        .withStyle(s -> s.withColor(points.getColor())))
                .append(Component.literal("(" + points.getTitle() + ")").withStyle(s -> s.withColor(points.getAccentColor()))), x, y, HubTheme.DIM, false);
        y += 11;
        if (info.overall() > 0) {
            g.drawString(font, Component.translatable("vtl.players.rank", info.overall()), x, y, HubTheme.DIM, false);
            y += 11;
        }
        y += 6;
        for (PlayerInfo.NamedRanking named : info.getSortedTiers()) {
            if (named.mode() == null) continue;
            if (y > ay + ah - 20) break;
            Component line = Component.empty()
                    .append(named.mode().asStyled(true))
                    .append(Component.literal(": ").withStyle(s -> s.withColor(0x9D8FB0)))
                    .append(TierTagger.getRankingText(named.ranking(), true));
            g.drawString(font, line, x, y, 0xFFFFFFFF, false);
            y += 11;
        }
        if (info.getSortedTiers().isEmpty()) {
            g.drawString(font, Component.translatable("vtl.players.noTiers"), x, y, HubTheme.DIM, false);
        }
    }
}
