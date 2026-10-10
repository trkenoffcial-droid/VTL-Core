package com.kevin.tiertagger.model;

import com.google.gson.JsonObject;
import com.kevin.tiertagger.TierTagger;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public record GameMode(String id, String title) {
    public static final GameMode NONE = new GameMode("annoying_long_id_that_no_one_will_ever_use_just_to_make_sure", "§cNone§r");

    /**
     * Hardcoded gamemode list for the Viet tierlist, which does not expose a
     * mode-list endpoint. If new modes are added server-side, add them here
     * and rebuild.
     */
    public static final List<GameMode> VIET_STATIC_GAMEMODES = List.of(
            new GameMode("vanilla", "Vanilla"),
            new GameMode("sword", "Sword"),
            new GameMode("uhc", "UHC"),
            new GameMode("pot", "Pot"),
            new GameMode("nethop", "Nethop"),
            new GameMode("smp", "SMP"),
            new GameMode("axe", "Axe"),
            new GameMode("mace", "Mace"),
            new GameMode("spear", "Spear"),
            new GameMode("trident", "Trident")
    );

    public static CompletableFuture<List<GameMode>> fetchGamemodes(HttpClient client) {
        // Viet tierlist has no mode-list endpoint; use the static fallback.
        if (isVietTierlistActive()) {
            return CompletableFuture.completedFuture(VIET_STATIC_GAMEMODES);
        }

        String endpoint = TierTagger.getManager().getConfig().getApiUrl() + "/v2/mode/list";
        final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).GET().build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    JsonObject obj = TierTagger.GSON.fromJson(r.body(), JsonObject.class);

                    return obj.entrySet().stream().map(e -> {
                        String title = e.getValue().getAsJsonObject().get("title").getAsString();
                        return new GameMode(e.getKey(), title);
                    }).toList();
                });
    }

    private static boolean isVietTierlistActive() {
        return TierList.findByUrl(TierTagger.getManager().getConfig().getApiUrl())
                .map(TierList::usesNameLookup)
                .orElse(false);
    }

    public boolean isNone() {
        return this.id.equals(NONE.id);
    }

    private Pair<Character, TextColor> iconAndColor() {
        return switch (this.id) {
            case "axe" -> Pair.of('\uE701', TextColor.fromLegacyFormat(ChatFormatting.GREEN));
            case "mace" -> Pair.of('\uE702', TextColor.fromLegacyFormat(ChatFormatting.GRAY));
            case "nethop", "neth_pot" -> Pair.of('\uE703', TextColor.fromRgb(0x7d4a40));
            case "pot" -> Pair.of('\uE704', TextColor.fromRgb(0xff0000));
            case "smp" -> Pair.of('\uE705', TextColor.fromRgb(0xeccb45));
            case "sword" -> Pair.of('\uE706', TextColor.fromRgb(0xa4fdf0));
            case "uhc" -> Pair.of('\uE707', TextColor.fromLegacyFormat(ChatFormatting.RED));
            case "vanilla" -> Pair.of('\uE708', TextColor.fromLegacyFormat(ChatFormatting.LIGHT_PURPLE));
            case "bed" -> Pair.of('\uE801', TextColor.fromRgb(0xff0000));
            case "bow" -> Pair.of('\uE802', TextColor.fromRgb(0x663d10));
            case "creeper" -> Pair.of('\uE803', TextColor.fromLegacyFormat(ChatFormatting.GREEN));
            case "debuff" -> Pair.of('\uE804', TextColor.fromLegacyFormat(ChatFormatting.DARK_GRAY));
            case "dia_crystal" -> Pair.of('\uE805', TextColor.fromLegacyFormat(ChatFormatting.AQUA));
            case "dia_smp" -> Pair.of('\uE806', TextColor.fromRgb(0x8c668b));
            case "elytra" -> Pair.of('\uE807', TextColor.fromRgb(0x8d8db1));
            case "manhunt" -> Pair.of('\uE808', TextColor.fromLegacyFormat(ChatFormatting.RED));
            case "minecart" -> Pair.of('\uE809', TextColor.fromLegacyFormat(ChatFormatting.GRAY));
            case "og_vanilla" -> Pair.of('\uE810', TextColor.fromLegacyFormat(ChatFormatting.GOLD));
            case "speed" -> Pair.of('\uE811', TextColor.fromRgb(0x43a9d1));
            case "trident" -> Pair.of('\uE812', TextColor.fromRgb(0x579b8c));
            // Viet tierlist-only modes (no icon PNG yet — falls back to bullet point)
            case "spear" -> Pair.of('•', TextColor.fromLegacyFormat(ChatFormatting.WHITE));
            default -> Pair.of('•', TextColor.fromLegacyFormat(ChatFormatting.WHITE));
        };
    }

    public Optional<Character> icon() {
        Pair<Character, TextColor> pair = this.iconAndColor();

        return pair.right().getValue() == 0xFFFFFF ? Optional.empty() : Optional.of(pair.left());
    }

    public Component asStyled(boolean withDefaultDot) {
        Pair<Character, TextColor> pair = this.iconAndColor();

        if (pair.right().getValue() == 0xFFFFFF && !withDefaultDot) {
            return Component.literal(this.title);
        } else {
            Component name = Component.literal(this.title).withStyle(s -> s.withColor(pair.right()));
            return Component.literal(pair.left() + " ").append(name);
        }
    }
}
