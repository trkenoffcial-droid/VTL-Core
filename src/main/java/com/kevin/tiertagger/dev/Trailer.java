package com.kevin.tiertagger.dev;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.config.TierTaggerConfig;
import com.kevin.tiertagger.hub.HubTab;
import com.kevin.tiertagger.hub.HubTheme;
import com.kevin.tiertagger.hub.VtlHubScreen;
import com.kevin.tiertagger.hub.VtlResultScreen;
import com.kevin.tiertagger.hub.widget.PlayerView;
import com.kevin.tiertagger.queue.QueueLinker;
import com.kevin.tiertagger.queue.QueueTokenStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Development only: records the VTL Core trailer. The dev client walks through the hub against
 * the mock queue server like {@link DevShots}, but films it: every rendered frame of a recorded
 * scene goes to ffmpeg, the game clock moves one frame per frame ({@link VirtualClock}), and
 * English captions are drawn over the picture.
 *
 * <p>Inactive unless the game runs from the development environment with
 * {@code -Dvtl.trailer=<file.mp4>} and {@code -Dvtl.trailer.ffmpeg=<ffmpeg>}
 * ({@code ./gradlew runClient -Ptrailer -Pffmpeg=...}); it does nothing in a released jar.</p>
 */
public final class Trailer {
    private Trailer() {}

    private static final String OUT = System.getProperty("vtl.trailer");
    private static final String FFMPEG = System.getProperty("vtl.trailer.ffmpeg", "ffmpeg");
    private static final String WS = System.getProperty("vtl.devshots.ws", "ws://127.0.0.1:3056/mod/ws");
    private static final int TIMEOUT_TICKS = 20 * 60;
    private static final int SECOND = 20; // client ticks

    private static boolean active;
    private static boolean started;
    private static boolean recording;
    private static FrameRecorder recorder;

    private record Step(String label, int ticks, BooleanSupplier until, Runnable action, Runnable everyTick) {}

    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static Step current;
    private static int left;
    private static int waited;

    private static String caption;
    private static long captionAt;
    private static long fadeFrom;
    private static long fadeTo;
    private static float fadeStart;
    private static float fadeEnd;

    public static void register() {
        if (OUT == null || !FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        active = true;
        recorder = new FrameRecorder(FFMPEG, new File(OUT));
        TierTagger.getLogger().info("[trailer] recording to {} with {}", OUT, FFMPEG);
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!started && screen instanceof AccessibilityOnboardingScreen) mc.execute(screen::onClose);
            if (!started && screen instanceof TitleScreen) {
                started = true;
                plan();
            }
            ScreenEvents.afterRender(screen).register((s, g, mouseX, mouseY, delta) -> overlay(g, s.width, s.height));
        });
        HudElementRegistry.addLast(HubTheme.vtl("trailer_overlay"), (g, deltaTracker) -> {
            if (Minecraft.getInstance().screen == null) overlay(g, g.guiWidth(), g.guiHeight());
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
    }

    /** Called once per rendered frame, after the GUI is drawn (MixinGameRenderer). */
    public static void onFrame() {
        if (!active || !started) return;
        if (recording) {
            if (!recorder.ready()) return; // the encoder is behind: same moment again next frame
            recorder.capture();
        }
        VirtualClock.advance();
    }

    // --- the script ---

    private static void plan() {
        Minecraft mc = Minecraft.getInstance();
        TierTaggerConfig cfg = TierTagger.getManager().getConfig();
        VirtualClock.install();

        // Off camera: settings, open queues, link the game.
        step("setup", 40, null, () -> {
            mc.options.tutorialStep = TutorialSteps.NONE;
            mc.options.pauseOnLostFocus = false;
            mc.options.framerateLimit().set(260);
            mc.options.enableVsync().set(false);
            QueueTokenStore.set(null);
            cfg.setQueueNotify(true);
            cfg.setHudEnabled(true);
            cfg.setQueueUrl(WS);
            cfg.setQueueLinkUrl(TierTaggerConfig.linkUrlFor(WS));
            TierTagger.getManager().saveConfig();
            TierTagger.getQueue().restart();
            DevShots.dev("open", Map.of("mode", "sword", "region", "EU"));
            DevShots.dev("open", Map.of("mode", "vanilla", "region", "AS_ME"));
            DevShots.dev("open", Map.of("mode", "mace", "region", "NA_SA"));
            DevShots.dev("open", Map.of("mode", "nethop", "region", "EU"));
            DevShots.dev("open", Map.of("mode", "sword", "region", "AS_ME"));
        });
        step("link", 20, () -> TierTagger.getQueue().hello() != null,
                () -> DevShots.dev("code", Map.of("name", mc.getUser().getName()), body -> QueueLinker.link(body.get("code").getAsString())));
        step("linked", 40, () -> QueueTokenStore.isLinked() && TierTagger.getQueue().hello() != null && TierTagger.getQueue().hello().linked(),
                () -> {
                    // the hub loads the player's skin; give it a moment before filming
                    VtlHubScreen.open(HubTab.Id.PLAY);
                });
        step("skin", 60, null, () -> mc.setScreen(new TitleScreen()));

        // 1. Title card
        scene("intro", 3.2f, () -> {
            mc.setScreen(TrailerCard.intro());
            fade(0f, 1f, 0.7f);
        });
        // 2. The title screen and its VTL button
        scene("title", 2.8f, () -> {
            mc.setScreen(new TitleScreen());
            caption("Open it from the title screen, or press R in game", Spot.TITLE);
        });
        // 3. The hub
        scene("hub", 2.8f, () -> {
            VtlHubScreen.open(HubTab.Id.PLAY);
            caption("Your skin, your kit", Spot.HUB);
        });
        // 4. Kits: every card swings the figure into its guard pose
        // a press every 1.1 s, the last one with time left for its swing
        scene("kits", 4.6f, () -> caption("Eight kits, each with its own guard pose", Spot.HUB), ticked(t -> {
            if (t % 22 == 8) key(GLFW.GLFW_KEY_RIGHT);
        }));
        offCamera("back to sword", 30, () -> {
            for (int i = 0; i < 4; i++) key(GLFW.GLFW_KEY_LEFT);
        });
        // 5. Drag to turn
        scene("turn", 2.6f, () -> {
            caption("Drag to turn it", Spot.HUB);
            playerView(view -> setField(view, "dragging", true));
        }, ticked(t -> {
            // turned half way round, then let go: it springs back to the front by itself
            if (t < 15) playerView(view -> view.mouseDragged(-8));
            if (t == 15) playerView(PlayerView::mouseReleased);
        }));
        // 6. Join a live queue
        offCamera("someone ahead", 2, () -> DevShots.dev("add", Map.of("mode", "sword", "region", "EU")));
        offCamera("join", 30, () -> TierTagger.getQueue().join("EU:sword"));
        waitFor("waiting", () -> DevShots.waiting("EU:sword"));
        scene("queue", 2.8f, () -> caption("Join live testing queues right from the game", Spot.HUB));
        // 7. Your turn
        scene("your turn", 3.0f, () -> {
            caption("Your turn shows up in the hub", Spot.HUB);
            DevShots.dev("pull", Map.of("mode", "sword", "region", "EU"));
        });
        // 8. Ticket chat
        waitFor("ticket", () -> TierTagger.getQueue().ticketLog().snapshot().active());
        offCamera("ticket tab", 30, () -> {
            DevShots.dev("say", Map.of("text", "Hi! Which server do you want to test on?"));
            DevShots.hub().switchTo(HubTab.Id.TICKET);
        });
        scene("chat", 5.2f, () -> {
            mc.getToastManager().clear();
            caption("Chat with your tester, Discord and Minecraft both ways", Spot.LOW);
        }, ticked(t -> {
            if (t == 24) {
                var ticket = TierTagger.getQueue().ticketLog().snapshot().ticket();
                if (ticket != null) TierTagger.getQueue().sendTicket(ticket.ticketId(), "EU server please, joining now");
            }
            if (t == 58) DevShots.dev("say", Map.of("text", "Ok, I'm in. First to 3, sword only. Good luck!"));
        }));
        // 9. A promotion
        offCamera("result", 2, () -> DevShots.dev("result", Map.of("tier", "HT3", "previous", "LT3")));
        waitFor("result opens", () -> mc.screen instanceof VtlResultScreen);
        scene("tier up", 6.6f, () -> {
            mc.getToastManager().clear();
            caption(null, Spot.LOW);
        }, ticked(t -> {
            if (t == 54) caption("Your result, the moment the tester posts it", Spot.LOW);
        }));
        // 10. A demotion
        offCamera("close result", 10, () -> mc.screen.onClose());
        offCamera("join again", 40, () -> TierTagger.getQueue().join("EU:sword"));
        waitFor("waiting again", () -> DevShots.waiting("EU:sword"));
        offCamera("pull again", 2, () -> DevShots.dev("pull", Map.of("mode", "sword", "region", "EU")));
        waitFor("ticket again", () -> TierTagger.getQueue().ticketLog().snapshot().active());
        offCamera("result down", 2, () -> DevShots.dev("result", Map.of("tier", "LT4", "previous", "HT3")));
        waitFor("result opens again", () -> mc.screen instanceof VtlResultScreen);
        scene("tier down", 4.4f, () -> {
            mc.getToastManager().clear();
            caption(null, Spot.LOW);
        }, ticked(t -> {
            if (t == 54) caption("Up or down, every result", Spot.LOW);
        }));
        // 11. In game: the HUD and a queue alert
        offCamera("leave result", 10, () -> mc.screen.onClose());
        offCamera("world", 2, () -> {
            mc.setScreen(null);
            DevShots.createWorld();
        });
        waitFor("in world", () -> mc.level != null && mc.player != null && mc.screen == null);
        offCamera("gear", 100, () -> {
            DevShots.command("time set day");
            DevShots.command("weather clear");
            DevShots.command("item replace entity @s armor.head with netherite_helmet");
            DevShots.command("item replace entity @s armor.chest with netherite_chestplate");
            DevShots.command("item replace entity @s armor.legs with netherite_leggings");
            DevShots.command("item replace entity @s armor.feet with netherite_boots");
            DevShots.command("item replace entity @s weapon.mainhand with netherite_sword");
            DevShots.command("item replace entity @s weapon.offhand with totem_of_undying");
        });
        offCamera("tidy", 10, () -> {
            mc.getToastManager().clear();
            mc.gui.getChat().clearMessages(false);
        });
        scene("in game", 4.0f, () -> caption("Alerts and a HUD while you play. Press J to join", Spot.MID), ticked(t -> {
            if (t == 12) DevShots.dev("open", Map.of("mode", "pot", "region", "EU"));
        }));
        // 12. The link rules
        scene("rules", 4.2f, () -> {
            mc.getToastManager().clear();
            caption(null, Spot.LOW);
            mc.setScreen(TrailerCard.rules());
        });
        // 13. The end
        scene("end", 4.4f, () -> {
            mc.getToastManager().clear();
            mc.setScreen(TrailerCard.end());
            fadeLater(1f, 0f, 3.5f, 0.9f);
        });
        offCamera("finish", 1, () -> recorder.finish(() -> {
            TierTagger.getLogger().info("[trailer] done");
            mc.stop();
        }));
    }

    // --- script building blocks ---

    private interface TickAction {
        void run(int tick);
    }

    private static Runnable ticked(TickAction action) {
        int[] t = {0};
        return () -> action.run(t[0]++);
    }

    /** A filmed scene of {@code seconds}: {@code start} runs on its first tick, {@code everyTick} on each. */
    private static void scene(String label, float seconds, Runnable start) {
        scene(label, seconds, start, null);
    }

    private static void scene(String label, float seconds, Runnable start, Runnable everyTick) {
        STEPS.add(new Step("scene " + label, Math.round(seconds * SECOND), null, () -> {
            recording = true;
            start.run();
        }, everyTick));
    }

    /** Not filmed: runs {@code action}, then waits {@code ticks}. */
    private static void offCamera(String label, int ticks, Runnable action) {
        STEPS.add(new Step(label, ticks, null, () -> {
            recording = false;
            action.run();
        }, null));
    }

    private static void step(String label, int ticks, BooleanSupplier until, Runnable action) {
        STEPS.add(new Step(label, ticks, until, () -> {
            recording = false;
            action.run();
        }, null));
    }

    /** Not filmed: waits until {@code condition} holds. */
    private static void waitFor(String label, BooleanSupplier condition) {
        STEPS.add(new Step(label, 0, condition, () -> recording = false, null));
    }

    private static void tick() {
        if (current != null) {
            if (current.everyTick() != null) current.everyTick().run();
            if (--left > 0) return;
            current = null;
        }
        Step next = STEPS.peek();
        if (next == null) return;
        if (next.until() != null && !next.until().getAsBoolean()) {
            recording = false;
            if (++waited > TIMEOUT_TICKS) {
                TierTagger.getLogger().warn("[trailer] gave up waiting at '{}'", next.label());
                STEPS.clear();
                recorder.finish(() -> Minecraft.getInstance().stop());
            }
            return;
        }
        STEPS.poll();
        waited = 0;
        TierTagger.getLogger().info("[trailer] {} ({} frames so far)", next.label(), recorder.frames());
        try {
            next.action().run();
        } catch (RuntimeException e) {
            TierTagger.getLogger().error("[trailer] step '{}' failed", next.label(), e);
        }
        current = next;
        left = Math.max(1, next.ticks());
    }

    // --- actions ---

    /** Where a caption sits: clear of the hub buttons, the chat, the result buttons. */
    private enum Spot {
        /** Between the hub's bottom button and its status bar. */
        HUB(1.25f),
        /** Low over the picture (title screen, ticket, result screens). */
        LOW(1.8f),
        /** Under the title screen buttons. */
        TITLE(1.8f),
        /** Mid-screen, above the chat, in game. */
        MID(1.8f);

        final float scale;

        Spot(float scale) {
            this.scale = scale;
        }

        int y(int height, int textH) {
            return switch (this) {
                case HUB -> height - 13 - textH;
                case LOW -> Math.round(height * 0.80f);
                case TITLE -> Math.round(height * 0.865f);
                case MID -> Math.round(height * 0.62f);
            };
        }
    }

    private static Spot captionSpot = Spot.LOW;

    private static void caption(String text, Spot spot) {
        caption = text;
        captionSpot = spot;
        captionAt = Util.getMillis();
    }

    /** Fades how much of the picture shows through the black cover, from {@code from} to {@code to} (0 = black, 1 = clear). */
    private static void fade(float from, float to, float seconds) {
        fadeLater(from, to, 0f, seconds);
    }

    private static void fadeLater(float from, float to, float afterSeconds, float seconds) {
        long now = Util.getMillis();
        fadeFrom = now + Math.round(afterSeconds * 1000);
        fadeTo = fadeFrom + Math.round(seconds * 1000);
        fadeStart = from;
        fadeEnd = to;
    }

    private static void key(int glfwKey) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) mc.screen.keyPressed(new KeyEvent(glfwKey, 0, 0));
    }

    private static void playerView(java.util.function.Consumer<PlayerView> use) {
        if (!(Minecraft.getInstance().screen instanceof VtlHubScreen hub)) return;
        Object tab = getField(hub, "tab");
        if (tab == null) return;
        for (Field f : tab.getClass().getDeclaredFields()) {
            if (f.getType() == PlayerView.class) {
                f.setAccessible(true);
                try {
                    use.accept((PlayerView) f.get(tab));
                } catch (IllegalAccessException e) {
                    TierTagger.getLogger().warn("[trailer] cannot reach the figure", e);
                }
                return;
            }
        }
    }

    private static Object getField(Object owner, String name) {
        try {
            Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (ReflectiveOperationException e) {
            TierTagger.getLogger().warn("[trailer] no field {} on {}", name, owner.getClass().getSimpleName());
            return null;
        }
    }

    private static void setField(Object owner, String name, Object value) {
        try {
            Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(owner, value);
        } catch (ReflectiveOperationException e) {
            TierTagger.getLogger().warn("[trailer] cannot set {} on {}", name, owner.getClass().getSimpleName());
        }
    }

    // --- what is drawn over the picture ---

    private static void overlay(GuiGraphics g, int width, int height) {
        if (!active || !started) return;
        long now = Util.getMillis();
        if (caption != null) {
            float a = Math.min(1f, (now - captionAt) / 250f);
            if (a > 0f) {
                Minecraft mc = Minecraft.getInstance();
                Component text = Component.literal(caption);
                // never wider than the screen
                float fit = width * 0.94f / Math.max(1, mc.font.width(text));
                float scale = HubTheme.sharp(Math.min(captionSpot.scale, fit));
                int textH = Math.round(9 * scale);
                int y = captionSpot.y(height, textH);
                if (captionSpot == Spot.HUB) {
                    g.fill(0, y - 2, width, y + textH + 1, HubTheme.argb(0x0D0718, Math.round(170 * a)));
                } else {
                    g.fillGradient(0, y - textH, width, y + textH * 2, 0x00000000, HubTheme.argb(0x0D0718, Math.round(200 * a)));
                }
                HubTheme.centered(g, mc.font, text, width / 2, y, HubTheme.argb(0xFFFFFF, Math.round(255 * a)), scale);
            }
        }
        float clear = fadeAt(now);
        if (clear < 1f) g.fill(0, 0, width, height, HubTheme.argb(0x000000, Math.round(255 * (1f - clear))));
    }

    /** 1 = no black, 0 = all black. */
    private static float fadeAt(long now) {
        if (fadeTo == 0) return 1f;
        if (now <= fadeFrom) return fadeStart;
        if (now >= fadeTo) return fadeEnd;
        float k = (now - fadeFrom) / (float) (fadeTo - fadeFrom);
        return fadeStart + (fadeEnd - fadeStart) * k;
    }
}
