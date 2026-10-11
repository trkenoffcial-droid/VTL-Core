package com.kevin.tiertagger.dev;

import com.google.gson.JsonObject;
import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.config.TierTaggerConfig;
import com.kevin.tiertagger.hub.HubTab;
import com.kevin.tiertagger.hub.Kit;
import com.kevin.tiertagger.hub.PlayTab;
import com.kevin.tiertagger.hub.VtlHubScreen;
import com.kevin.tiertagger.hub.VtlResultScreen;
import com.kevin.tiertagger.queue.QueueLinker;
import com.kevin.tiertagger.queue.QueueMessages.Queue;
import com.kevin.tiertagger.queue.QueueTokenStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
//? if >=1.21.9
import net.minecraft.client.input.CharacterEvent;
//? if >=1.21.9
import net.minecraft.client.input.KeyEvent;
//? if >=1.21.9
import net.minecraft.client.input.MouseButtonEvent;
//? if >=1.21.9
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.File;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Development only: drives the real client through every VTL Hub state against the mock queue
 * server and saves screenshots, then renders the kit card figures for {@code tools/kit-art.js}.
 *
 * <p>Inactive unless the game runs from the development environment with
 * {@code -Dvtl.devshots=<dir>} ({@code ./gradlew runClient -Pdevshots}); it does nothing in a
 * released jar.</p>
 */
public final class DevShots {
    private DevShots() {}

    private static final String DIR = System.getProperty("vtl.devshots");
    private static final String DEV = System.getProperty("vtl.devshots.dev", "http://127.0.0.1:3057/dev");
    private static final String WS = System.getProperty("vtl.devshots.ws", "ws://127.0.0.1:3056/mod/ws");
    private static final int TIMEOUT_TICKS = 20 * 30;

    private record Step(String label, int delay, BooleanSupplier until, Runnable action) {}

    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static boolean started;
    private static int waited;
    private static boolean delayDone;
    private static int delayLeft = -1;

    public static void register() {
        if (DIR == null || !FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        TierTagger.getLogger().info("[devshots] writing to {}", DIR);
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            // a fresh run directory starts on the accessibility screen; skip it like "Continue" would
            if (!started && screen instanceof AccessibilityOnboardingScreen) mc.execute(screen::onClose);
            if (!started && screen instanceof TitleScreen) {
                started = true;
                plan();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
    }

    // --- the script ---

    private static void plan() {
        Minecraft mc = Minecraft.getInstance();
        TierTaggerConfig cfg = TierTagger.getManager().getConfig();
        step("setup", 40, null, () -> {
            mc.options.tutorialStep = TutorialSteps.NONE;
            // another window taking focus must not open the pause menu mid-run
            mc.options.pauseOnLostFocus = false;
            QueueTokenStore.set(null);
            cfg.setQueueNotify(true);
            cfg.setHudEnabled(true);
            cfg.setQueueUrl(WS);
            cfg.setQueueLinkUrl(TierTaggerConfig.linkUrlFor(WS));
            TierTagger.getManager().saveConfig();
            TierTagger.getQueue().restart();
            dev("open", Map.of("mode", "sword", "region", "EU"));
            dev("open", Map.of("mode", "vanilla", "region", "AS_ME"));
            dev("open", Map.of("mode", "mace", "region", "NA_SA"));
            dev("open", Map.of("mode", "nethop", "region", "EU"));
            dev("open", Map.of("mode", "sword", "region", "AS_ME"));
        });
        shot("01-title-menu", 60, () -> TierTagger.getQueue().hello() != null);
        step("hub", 2, null, () -> VtlHubScreen.open(HubTab.Id.PLAY));
        shot("02-hub-play-not-linked", 40, null);
        step("settings", 2, null, () -> hub().switchTo(HubTab.Id.SETTINGS));
        shot("03-settings", 20, null);
        step("players", 2, null, () -> hub().switchTo(HubTab.Id.PLAYERS));
        shot("04-players", 20, null);
        // bot V39 links by game name: the mock calls its account like this dev client's player
        step("link", 2, null, () -> dev("code", Map.of("name", Minecraft.getInstance().getUser().getName()),
                body -> QueueLinker.link(body.get("code").getAsString())));
        step("linked", 20, () -> QueueTokenStore.isLinked() && TierTagger.getQueue().hello() != null && TierTagger.getQueue().hello().linked(),
                () -> hub().switchTo(HubTab.Id.PLAY));
        shot("05-hub-linked", 30, null);
        step("someone ahead", 2, null, () -> dev("add", Map.of("mode", "sword", "region", "EU")));
        step("join", 30, null, () -> TierTagger.getQueue().join("EU:sword"));
        shot("06-hub-waiting", 40, () -> waiting("EU:sword"));
        step("scale 2", 2, null, () -> scale(2));
        shot("06b-hub-waiting-gui2", 20, null);
        step("scale 3", 2, null, () -> scale(3));
        shot("06c-hub-waiting-gui3", 20, null);
        step("scale auto", 2, null, () -> scale(0));
        step("pull", 2, null, () -> dev("pull", Map.of("mode", "sword", "region", "EU")));
        shot("07-hub-your-turn", 40, () -> TierTagger.getQueue().openQueues().stream().anyMatch(q -> q.me() != null && q.me().isTesting()));
        // the ticket chat: the tester writes on Discord, the player answers from the game
        step("ticket chat", 2, () -> TierTagger.getQueue().ticketLog().snapshot().active(), () -> {
            dev("say", Map.of("text", "Hi! Which server do you want to test on? I can do play.vtl.vn or eu.vtl.vn"));
            hub().switchTo(HubTab.Id.TICKET);
        });
        step("reply", 20, null, () -> {
            var ticket = TierTagger.getQueue().ticketLog().snapshot().ticket();
            TierTagger.getQueue().sendTicket(ticket.ticketId(), "play.vtl.vn please, joining now");
        });
        step("tester again", 30, null, () -> dev("say", Map.of("text", "Ok, I'm in. First to 3, sword only. Good luck!")));
        shot("07b-ticket-chat", 30, null);
        // input check: a click on the ticket box and two typed characters, through the screen's own events
        step("type check", 2, null, DevShots::typeCheck);
        // /result: the result screen opens over the hub - a promotion, LT3 -> HT3
        step("result", 2, null, () -> dev("result", Map.of("tier", "HT3", "previous", "LT3")));
        step("result opens", 2, () -> mc.screen instanceof VtlResultScreen, () -> {});
        grab("07c-result-suspense", 24);
        grab("07d-result-promoted", 60);
        grab("07e-result-promoted-later", 30);
        step("result closed", 2, null, () -> mc.screen.onClose());
        shot("07f-ticket-complete", 10, () -> mc.screen instanceof VtlHubScreen);
        // a second test that goes down: HT3 -> LT4
        step("join again", 2, null, () -> TierTagger.getQueue().join("EU:sword"));
        step("pull again", 40, () -> waiting("EU:sword"), () -> dev("pull", Map.of("mode", "sword", "region", "EU")));
        step("result down", 40, () -> TierTagger.getQueue().ticketLog().snapshot().active(),
                () -> dev("result", Map.of("tier", "LT4", "previous", "HT3")));
        step("result opens again", 2, () -> mc.screen instanceof VtlResultScreen, () -> {});
        grab("07g-result-demoted", 80);
        step("result closed again", 2, null, () -> mc.screen.onClose());
        step("back to play", 10, () -> mc.screen instanceof VtlHubScreen, () -> hub().switchTo(HubTab.Id.PLAY));
        // input check: clicks on the carousel arrows move the kit selection and back
        step("click check", 10, null, DevShots::clickCheck);
        step("world", 2, null, () -> {
            mc.setScreen(null);
            createWorld();
        });
        step("in world", 120, () -> mc.level != null && mc.player != null && mc.screen == null, () -> {
            command("time set day");
            command("weather clear");
            command("item replace entity @s armor.head with netherite_helmet");
            command("item replace entity @s armor.chest with netherite_chestplate");
            command("item replace entity @s armor.legs with netherite_leggings");
            command("item replace entity @s armor.feet with netherite_boots");
            command("item replace entity @s weapon.mainhand with netherite_sword");
            command("item replace entity @s weapon.offhand with totem_of_undying");
        });
        shot("08-hud-in-game", 60, null);
        step("hub in world", 2, null, () -> VtlHubScreen.open(HubTab.Id.PLAY));
        shot("09-hub-in-game", 40, null);
        // eight 128 px cells need a wide window; a small test window (-PdevshotsSize) skips them
        if (mc.getWindow().getWidth() >= 1200) {
            step("kit art", 2, null, () -> {
                scale(2);
                mc.setScreen(new KitArtScreen(new File(DIR)));
            });
            shot("kit-art", 40, null);
        }
        step("done", 20, null, () -> {
            TierTagger.getLogger().info("[devshots] done");
            mc.stop();
        });
    }

    // --- machinery ---

    private static void step(String label, int delay, BooleanSupplier until, Runnable action) {
        STEPS.add(new Step(label, delay, until, action));
    }

    /** Saves a picture after {@code delay} ticks, without tidying (the screen must not change underneath). */
    private static void grab(String name, int delay) {
        step(name + " (clear toasts)", 0, null, () -> Minecraft.getInstance().getToastManager().clear());
        step(name, delay, null, () -> grab(name));
    }

    /** Clears toasts and chat, waits a few frames so they are gone from the picture, then saves it. */
    private static void shot(String name, int delay, BooleanSupplier until) {
        step(name + " (tidy)", delay, until, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.getToastManager().clear();
            mc.gui.getChat().clearMessages(false);
        });
        step(name, 5, null, () -> grab(name));
    }

    private static void tick() {
        Step step = STEPS.peek();
        if (step == null) return;
        if (step.until != null && !step.until.getAsBoolean()) {
            if (++waited > TIMEOUT_TICKS) {
                TierTagger.getLogger().warn("[devshots] gave up waiting at '{}'", step.label);
                waited = 0;
                STEPS.clear();
                Minecraft.getInstance().stop();
            }
            return;
        }
        if (!delayDone) {
            if (delayLeft < 0) delayLeft = step.delay;
            if (delayLeft-- > 0) return;
            delayDone = true;
        }
        STEPS.poll();
        waited = 0;
        delayDone = false;
        delayLeft = -1;
        TierTagger.getLogger().info("[devshots] {}", step.label);
        try {
            step.action.run();
        } catch (RuntimeException e) {
            TierTagger.getLogger().error("[devshots] step '{}' failed", step.label, e);
        }
    }

    static VtlHubScreen hub() {
        if (Minecraft.getInstance().screen instanceof VtlHubScreen hub) return hub;
        VtlHubScreen hub = new VtlHubScreen(Minecraft.getInstance().screen, HubTab.Id.PLAY);
        Minecraft.getInstance().setScreen(hub);
        return hub;
    }

    /** Logs "click ok" when a left click on "›" selects another kit and a click on "‹" brings the first one back. */
    private static void clickCheck() {
        VtlHubScreen hub = hub();
        if (!(hub.tab() instanceof PlayTab play)) {
            TierTagger.getLogger().warn("[devshots] click FAILED: the PLAY tab is not open");
            return;
        }
        Kit before = play.selectedKit();
        boolean moved = click(hub, "›") && play.selectedKit() != before;
        boolean back = click(hub, "‹") && play.selectedKit() == before;
        if (moved && back) TierTagger.getLogger().info("[devshots] click ok");
        else TierTagger.getLogger().warn("[devshots] click FAILED: {} -> {}", before, play.selectedKit());
        // the arrow keys go through the screen's key event to the tab
        boolean keyMoved = key(hub, InputConstants.KEY_RIGHT) && play.selectedKit() != before;
        boolean keyBack = key(hub, InputConstants.KEY_LEFT) && play.selectedKit() == before;
        if (keyMoved && keyBack) TierTagger.getLogger().info("[devshots] key ok");
        else TierTagger.getLogger().warn("[devshots] key FAILED: {} -> {}", before, play.selectedKit());
    }

    /** A key press on the hub screen; true when the screen used it. */
    private static boolean key(VtlHubScreen hub, int key) {
        //? if >=1.21.9 {
        return hub.keyPressed(new KeyEvent(key, 0, 0));
        //?} else {
        /*return hub.keyPressed(key, 0, 0);
        *///?}
    }

    /** Logs "type ok" when a click on the ticket box and the characters h, i leave "hi" in it. */
    private static void typeCheck() {
        VtlHubScreen hub = hub();
        EditBox box = null;
        for (var child : hub.children()) {
            if (child instanceof EditBox b) box = b;
        }
        if (box == null) {
            TierTagger.getLogger().warn("[devshots] type FAILED: no ticket box");
            return;
        }
        box.setValue("");
        press(hub, box);
        //? if >=26 {
        /*hub.charTyped(new CharacterEvent('h'));
        hub.charTyped(new CharacterEvent('i'));
        *///?} elif >=1.21.9 {
        hub.charTyped(new CharacterEvent('h', 0));
        hub.charTyped(new CharacterEvent('i', 0));
        //?} else {
        /*hub.charTyped('h', 0);
        hub.charTyped('i', 0);
        *///?}
        String typed = box.getValue();
        box.setValue("");
        if ("hi".equals(typed)) TierTagger.getLogger().info("[devshots] type ok");
        else TierTagger.getLogger().warn("[devshots] type FAILED: the box holds '{}'", typed);
    }

    /** Clicks the visible widget labelled {@code label}; false when there is none. */
    private static boolean click(VtlHubScreen hub, String label) {
        for (var child : hub.children()) {
            if (child instanceof AbstractWidget w && w.visible && label.equals(w.getMessage().getString())) {
                press(hub, w);
                return true;
            }
        }
        return false;
    }

    /** A left button press and release on the centre of {@code w}. */
    private static void press(VtlHubScreen hub, AbstractWidget w) {
        double x = w.getX() + w.getWidth() / 2.0, y = w.getY() + w.getHeight() / 2.0;
        //? if >=1.21.9 {
        MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        hub.mouseClicked(event, false);
        hub.mouseReleased(event);
        //?} else {
        /*hub.mouseClicked(x, y, InputConstants.MOUSE_BUTTON_LEFT);
        hub.mouseReleased(x, y, InputConstants.MOUSE_BUTTON_LEFT);
        *///?}
    }

    static boolean waiting(String queueId) {
        for (Queue q : TierTagger.getQueue().openQueues()) {
            if (queueId.equals(q.queueId()) && q.me() != null && q.me().isWaiting()) return true;
        }
        return false;
    }

    private static void grab(String name) {
        Minecraft mc = Minecraft.getInstance();
        // Screenshot.grab with a file name does not create the folder itself
        new File(DIR, "screenshots").mkdirs();
        Screenshot.grab(new File(DIR), name + ".png", mc.getMainRenderTarget(), 1,
                msg -> TierTagger.getLogger().info("[devshots] {}", msg.getString()));
    }

    static void scale(int guiScale) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.guiScale().set(guiScale);
        mc.resizeDisplay();
    }

    static void createWorld() {
        Minecraft mc = Minecraft.getInstance();
        //? if >=26 {
        /*LevelSettings settings = new LevelSettings("vtl-devshots", GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        *///?} else {
        LevelSettings settings = new LevelSettings("vtl-devshots", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
        //?}
        //? if >=26.2 {
        /*// 26.2 dropped WorldPresets#createFlatWorldDimensions: build the flat preset from the registry
        java.util.function.Function<net.minecraft.core.HolderLookup.Provider, net.minecraft.world.level.levelgen.WorldDimensions> flat =
                lookup -> lookup.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                        .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
        *///?} else
        java.util.function.Function<net.minecraft.core.HolderLookup.Provider, net.minecraft.world.level.levelgen.WorldDimensions> flat = WorldPresets::createFlatWorldDimensions;
        mc.createWorldOpenFlows().createFreshLevel("vtl-devshots-" + System.currentTimeMillis(), settings,
                WorldOptions.defaultWithRandomSeed(), flat, new TitleScreen());
    }

    static void command(String command) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        server.execute(() -> {
            String name = mc.player.getGameProfile().name();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command.replace("@s", name));
        });
    }

    static void dev(String action, Map<String, Object> body) {
        dev(action, body, null);
    }

    static void dev(String action, Map<String, Object> body, java.util.function.Consumer<JsonObject> then) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(DEV + "/" + action))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(TierTagger.GSON.toJson(body)))
                .build();
        TierTagger.getClient().sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(r -> {
            TierTagger.getLogger().info("[devshots] /dev/{} -> {} {}", action, r.statusCode(), r.body());
            if (then != null && r.statusCode() == 200) {
                JsonObject json = TierTagger.GSON.fromJson(r.body(), JsonObject.class);
                Minecraft.getInstance().execute(() -> then.accept(json));
            }
        });
    }
}
