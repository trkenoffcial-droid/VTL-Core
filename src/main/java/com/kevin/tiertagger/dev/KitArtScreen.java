package com.kevin.tiertagger.dev;

import com.kevin.tiertagger.compat.Players;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.hub.Kit;
import com.kevin.tiertagger.hub.anim.VtlFigure;
import com.kevin.tiertagger.hub.anim.VtlKitRegistry;
import com.kevin.tiertagger.hub.anim.VtlModelAnimator;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Development only: one figure per kit, wearing that kit (from {@link VtlKitRegistry}) and standing
 * in its guard, on a #FF00FF background, for {@code tools/kit-art.js} to cut into the card images.
 * Writes the cell rectangles next to the screenshot so the tool knows where each figure is.
 */
final class KitArtScreen extends Screen {
    /** Fixed default skins, one per kit, so the cards do not all show the same face. */
    private static final String[] UUIDS = {
            "a7f3c2d1-0000-4000-8000-000000000001", "b1e2d3c4-0000-4000-8000-000000000002",
            "c9d8e7f6-0000-4000-8000-000000000003", "d4c3b2a1-0000-4000-8000-000000000004",
            "e5f6a7b8-0000-4000-8000-000000000005", "f1a2b3c4-0000-4000-8000-000000000006",
            "0a1b2c3d-0000-4000-8000-000000000007", "1b2c3d4e-0000-4000-8000-000000000008"};

    private final File dir;
    private final Map<Kit, Supplier<PlayerSkin>> skins = new EnumMap<>(Kit.class);
    private boolean written;

    KitArtScreen(File dir) {
        super(Component.literal("kit art"));
        this.dir = dir;
    }

    @Override
    protected void init() {
        com.kevin.tiertagger.compat.ItemComponents.ensureBound(); // 26.1+: items need components, even on the title screen
        Minecraft mc = Minecraft.getInstance();
        Kit[] kits = Kit.values();
        for (int i = 0; i < kits.length; i++) {
            GameProfile profile = new GameProfile(UUID.fromString(UUIDS[i % UUIDS.length]), "vtl" + kits[i].id());
            skins.put(kits[i], Players.skin(profile, false));
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0xFFFF00FF);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int n = Kit.values().length;
        int gap = 8;
        // one screen pixel per card pixel: the card images are 128 x 176, no resampling
        int gs = Math.max(1, (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
        int cellW = 128 / gs;
        int cellH = 176 / gs;
        int y = (this.height - cellH) / 2;
        JsonArray cells = new JsonArray();
        for (int i = 0; i < n; i++) {
            Kit kit = Kit.values()[i];
            int x = 10 + i * (cellW + gap);
            var loadout = VtlKitRegistry.of(kit);
            // a still moment of the guard (no breathing offset), three-quarter view
            var pose = VtlModelAnimator.pose(VtlModelAnimator.Anim.IDLE, loadout.stance(), 0f);
            VtlFigure.render(g, x, y, x + cellW, y + cellH, cellH * 0.66f, skins.get(kit), loadout, pose, 25f, 0f);
            JsonObject cell = new JsonObject();
            cell.addProperty("kit", kit.id());
            cell.addProperty("x", x);
            cell.addProperty("y", y);
            cell.addProperty("w", cellW);
            cell.addProperty("h", cellH);
            cells.add(cell);
        }
        if (!written) {
            written = true;
            JsonObject out = new JsonObject();
            out.addProperty("guiScale", Minecraft.getInstance().getWindow().getGuiScale());
            out.add("cells", cells);
            try {
                Files.createDirectories(dir.toPath());
                Files.writeString(dir.toPath().resolve("kit-art.json"), TierTagger.GSON.toJson(out), StandardCharsets.UTF_8);
            } catch (IOException e) {
                TierTagger.getLogger().error("[devshots] could not write kit-art.json", e);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
