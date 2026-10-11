plugins {
    // applies fabric-loom-remap below Minecraft 26 and fabric-loom (no remapping) from 26
    id("dev.kikugie.loom-back-compat")
    id("io.freefair.lombok") version "9.1.0"
}

// Stonecutter runs this script once per node (versions/<node>); the node's properties pick the game.
stonecutter {
    // 1.21.9 moved PlayerSkin and renamed PlayerRenderState to AvatarRenderState
    replacements.string(current.parsed >= "1.21.9") {
        replace("import net.minecraft.client.resources.PlayerSkin;", "import net.minecraft.world.entity.player.PlayerSkin;")
        replace("PlayerRenderState", "AvatarRenderState")
        // authlib 7 made GameProfile a record; the client's services moved under Minecraft#services
        replace("getGameProfile().getName()", "getGameProfile().name()")
        replace("getProfile().getName()", "getProfile().name()")
        replace("getGameProfile().getId()", "getGameProfile().id()")
        replace("getProfile().getId()", "getProfile().id()")
        replace("mc.getMinecraftSessionService()", "mc.services().sessionService()")
    }
    // 26.1 (unobfuscated): GUI drawing became render state extraction, and Fabric API took Mojang's names
    replacements.string(current.parsed >= "26") {
        replace("GuiGraphics", "GuiGraphicsExtractor")
        replace("import net.minecraft.client.gui.components.PlayerFaceRenderer;", "import net.minecraft.client.gui.components.PlayerFaceExtractor;")
        replace("PlayerFaceRenderer.draw(", "PlayerFaceExtractor.extractRenderState(")
        replace("ClientCommandManager", "ClientCommands")
        replace("net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper", "net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper")
        replace("KeyBindingHelper.registerKeyBinding(", "KeyMappingHelper.registerKeyMapping(")
        replace("import net.fabricmc.fabric.api.resource.ResourceManagerHelper;", "import net.fabricmc.fabric.api.resource.v1.ResourceLoader;")
        replace("import net.fabricmc.fabric.api.resource.ResourcePackActivationType;", "import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;")
        replace("ResourceManagerHelper.registerBuiltinResourcePack(", "ResourceLoader.registerBuiltinPack(")
        replace("ResourcePackActivationType.NORMAL", "PackActivationType.NORMAL")
        // render overrides became extract* (whole declaration lines: our own render methods keep their names)
        replace("public void render(GuiGraphics g, int mouseX, int mouseY, float delta)", "public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta)")
        replace("public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta)", "public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta)")
        replace("public void render(GuiGraphics g, DeltaTracker delta)", "public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta)")
        replace("super.render(", "super.extractRenderState(")
        replace("renderBackground(", "extractBackground(")
        replace("renderWidget(", "extractWidgetRenderState(")
        replace("Screens.getButtons(", "Screens.getWidgets(")
        // GuiGraphicsExtractor's drawing calls (on the GuiGraphics variable only: HubTheme has its own
        // drawString and text helpers, and every rule also runs backwards on the older nodes)
        replace("g.drawString(", "g.text(")
        replace("graphics.drawCenteredString(", "graphics.centeredText(")
        replace("g.renderOutline(", "g.outline(")
        replace("g.renderItem(", "g.item(")
        replace("g.submitEntityRenderState(", "g.entity(")
        replace("this.codeField.render(", "this.codeField.extractRenderState(")
        // other 26.1 renames
        replace("mc.resizeDisplay()", "mc.resizeGui()")
        // (not getChat()...: one pass runs every group, and 26.2 rewrites mc.gui.getChat())
        replace(".addMessage(message);", ".addClientSystemMessage(message);")
        replace("mc.player.displayClientMessage(message, true)", "mc.player.sendOverlayMessage(message)")
        replace("getSource().getWorld()", "getSource().getLevel()")
    }
    // 26.2: the open screen and screen changes moved to Minecraft#gui, the HUD parts to Gui#hud
    replacements.string(current.parsed >= "26.2") {
        replace("mc.screen", "mc.gui.screen()")
        replace("Minecraft.getInstance().screen", "Minecraft.getInstance().gui.screen()")
        replace("mc.setScreen(", "mc.gui.setScreen(")
        replace("Minecraft.getInstance().setScreen(", "Minecraft.getInstance().gui.setScreen(")
        replace("mc.getToastManager()", "mc.gui.toastManager()")
        replace("Minecraft.getInstance().getToastManager()", "Minecraft.getInstance().gui.toastManager()")
        replace("mc.gui.getChat()", "mc.gui.hud.getChat()")
        replace("mc.gui.setTitle(", "mc.gui.hud.setTitle(")
        replace("mc.gui.setTimes(", "mc.gui.hud.setTimes(")
        replace("mc.gui.setSubtitle(", "mc.gui.hud.setSubtitle(")
        replace("mc.options.hideGui", "mc.gui.hud.isHidden()")
        replace("import net.minecraft.world.entity.EntityType;", "import net.minecraft.world.entity.EntityTypes;")
        replace("EntityType.PLAYER", "EntityTypes.PLAYER")
        replace("mc.getMainRenderTarget()", "mc.gameRenderer.mainRenderTarget()")
    }
    // 26.3: opening links moved to Blaze3D; the vanilla registries split into world and reloadable lookups
    replacements.string(current.parsed >= "26.3") {
        replace("Util.getPlatform().openUri(uri);", "com.mojang.blaze3d.Blaze3D.openUri(uri);")
        replace("Util.getPlatform().openUri(url);", "com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(url));")
        replace("VanillaRegistries.createLookup()", "VanillaRegistries.createWorldLookup()")
    }
    // GuiGraphics#renderOutline was called submitOutline in 1.21.9 and 1.21.10 only
    replacements.string(current.parsed >= "1.21.9" && current.parsed < "1.21.11") {
        replace("g.renderOutline(", "g.submitOutline(")
    }
    // 1.21.11 renamed ResourceLocation to Identifier and moved Util and GameRules
    replacements.string(current.parsed >= "1.21.11") {
        replace("ResourceLocation", "Identifier")
        replace("import net.minecraft.Util;", "import net.minecraft.util.Util;")
        replace("net.minecraft.world.level.GameRules", "net.minecraft.world.level.gamerules.GameRules")
    }
}

// The trailer recorder (dev only) is built for 1.21.11 alone.
val trailerNode = sc.current.version == "1.21.11"
if (!trailerNode) {
    sourceSets.main {
        java {
            exclude("com/kevin/tiertagger/dev/Trailer.java", "com/kevin/tiertagger/dev/TrailerCard.java",
                "com/kevin/tiertagger/dev/FrameRecorder.java", "com/kevin/tiertagger/dev/VirtualClock.java",
                "com/kevin/tiertagger/mixin/MixinGameRenderer.java")
        }
    }
}
version = "${project.property("mod_version")}+mc${project.property("minecraft_version")}"
val javaRelease = (project.property("java_release") as String).toInt()
group = project.property("maven_group") as String

repositories {
    maven {
        url = uri("https://maven.terraformersmc.com/releases")
    }
    maven {
        url = uri("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
    }
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")

    // Fabric API modules the mod uses; players install Fabric API itself.
    val fabricApiVersion = project.property("fabric_api_version") as String
    // Fabric API for 26.x renamed key-binding to key-mapping and dropped resource-loader-v0
    val unobfuscated = sc.current.parsed >= "26"
    for (module in listOf(
        "fabric-command-api-v2",
        if (unobfuscated) "fabric-key-mapping-api-v1" else "fabric-key-binding-api-v1",
        "fabric-lifecycle-events-v1",
        "fabric-screen-api-v1",
        "fabric-rendering-v1",
        if (unobfuscated) "fabric-resource-loader-v1" else "fabric-resource-loader-v0",
    )) {
        modImplementation(fabricApi.module(module, fabricApiVersion))
    }
    // fabric.mod.json depends on "fabric-api" itself, so the dev client needs the whole thing
    modLocalRuntime("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // ModMenu is optional: only its API is needed to compile the settings button.
    modCompileOnly("com.terraformersmc:modmenu:${project.property("modmenu_version")}") { isTransitive = false }

    // DevAuth (dev logins) is built for the obfuscated game; 26.x dev clients run without it
    if (sc.current.parsed < "26") modRuntimeOnly("me.djtheredstoner:DevAuth-fabric:${project.property("devauth_version")}")
}

// ./gradlew runClient -Pdevshots: the dev client walks through the hub states against the mock
// queue server and saves screenshots and kit card renders to run/vtl-shots (see TESTING.md).
// ./gradlew runClient -Ptrailer -Pffmpeg=<ffmpeg.exe>: films the trailer to run/vtl-trailer (needs ffmpeg).
loom {
    runs {
        named("client") {
            if (project.hasProperty("devshots")) {
                vmArg("-Dvtl.devshots=" + project.file("run/vtl-shots").invariantSeparatorsPath)
                // -PdevshotsSize=854x480 shoots a small window instead
                val size = (project.findProperty("devshotsSize") ?: "1920x1080").toString().split("x")
                programArgs("--width", size[0], "--height", size[1])
            }
            if (project.hasProperty("trailer")) {
                vmArg("-Dvtl.trailer=" + project.file("run/vtl-trailer/VTL-Core-trailer.mp4").invariantSeparatorsPath)
                vmArg("-Dvtl.trailer.ffmpeg=" + (project.findProperty("ffmpeg") ?: "ffmpeg"))
                programArgs("--width", "1920", "--height", "1080")
                // the player on camera; -PtrailerName/-PtrailerUuid pick another premium account (its skin is fetched by UUID)
                programArgs("--username", (project.findProperty("trailerName") ?: "MC_KENHERO").toString())
                programArgs("--uuid", (project.findProperty("trailerUuid") ?: "d94b0ecf-6888-40fd-a0d9-977ff49a9749").toString())
            }
        }
    }
}

base {
    archivesName = project.property("archives_base_name") as String
}

java {
    sourceCompatibility = JavaVersion.toVersion(javaRelease)
    targetCompatibility = JavaVersion.toVersion(javaRelease)
}

tasks.processResources {
    val values = mapOf(
        "version" to project.version.toString(),
        "mc_range" to project.property("mc_range") as String,
        "loader_min" to project.property("loader_min") as String,
        "mixin_java" to project.property("mixin_java") as String,
        // the trailer's frame hook exists only where the trailer recorder is built
        "trailer_mixin" to if (trailerNode) "\"MixinGameRenderer\"," else "",
    )
    inputs.properties(values)
    filteringCharset = "UTF-8"

    filesMatching(listOf("fabric.mod.json", "TierTagger.mixins.json")) {
        expand(values)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaRelease
}

// The released jar is named after the game version only (VTL-Core+mc1.21.11.jar); the mod
// version stays in fabric.mod.json, where Mod Menu shows it.
loomx.modJar.configure {
    archiveFileName.set("${base.archivesName.get()}+mc${project.property("minecraft_version")}.jar")
}

/** chiseledBuild (stonecutter.gradle.kts) collects every node's jar in build/libs/<mod version>/. */
tasks.register<Copy>("buildAndCollect") {
    group = "build"
    description = "Builds this node's jar and copies it to build/libs/<mod version>/"
    from(loomx.modJar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs/${project.property("mod_version")}"))
}

tasks.jar {
    from(rootProject.file("LICENSE")) {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

/**
 * QueueTuningCheck and QueueProtocolCheck are plain main()s run by their own tasks, not JUnit
 * tests, so the stock test task discovers nothing. Left enabled so real tests still run if any
 * are added later.
 */
tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
}

val queueCheckOutput = layout.buildDirectory.dir("queue-check")

/** Gson only: the queue core (QueueClient, QueueMessages, QueueTuning) uses no Minecraft types. */
val queueCheckClasspath = files(configurations.compileClasspath.map { cp -> cp.filter { it.name.startsWith("gson-") } })

val compileQueueCheck by tasks.registering(JavaCompile::class) {
    source(rootProject.file("src/main/java/com/kevin/tiertagger/queue/QueueTuning.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/queue/QueueMessages.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/queue/QueueClient.java"))
    source(rootProject.file("src/test/java/com/kevin/tiertagger/queue/QueueTuningCheck.java"))
    source(rootProject.file("src/test/java/com/kevin/tiertagger/queue/QueueProtocolCheck.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/config/JsonConfigStore.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/config/LegacyTomlImport.java"))
    source(rootProject.file("src/test/java/com/kevin/tiertagger/config/ConfigCheck.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/hub/Kit.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/hub/HubState.java"))
    source(rootProject.file("src/test/java/com/kevin/tiertagger/hub/HubStateCheck.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/queue/QuickJoin.java"))
    source(rootProject.file("src/test/java/com/kevin/tiertagger/queue/QuickJoinCheck.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/hub/TextFit.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/queue/TicketLog.java"))
    source(rootProject.file("src/test/java/com/kevin/tiertagger/queue/TicketLogCheck.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/queue/LinkRequest.java"))
    source(rootProject.file("src/test/java/com/kevin/tiertagger/queue/LinkRequestCheck.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/hub/anim/Pose.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/hub/anim/VtlModelAnimator.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/hub/anim/TierChange.java"))
    source(rootProject.file("src/main/java/com/kevin/tiertagger/hub/fx/VtlParticleSystem.java"))
    source(rootProject.file("src/test/java/com/kevin/tiertagger/hub/anim/AnimatorCheck.java"))
    source(rootProject.file("src/test/java/com/kevin/tiertagger/hub/anim/TierChangeCheck.java"))
    source(rootProject.file("src/test/java/com/kevin/tiertagger/hub/fx/ParticleCheck.java"))
    source(rootProject.file("src/test/java/com/kevin/tiertagger/hub/TextFitCheck.java"))
    classpath = queueCheckClasspath
    destinationDirectory.set(queueCheckOutput)
    options.encoding = "UTF-8"
}

/**
 * Runs the queue socket's self-checks. Deliberately not a JUnit task: the code under test is
 * pure JDK arithmetic with no Minecraft on the classpath, so this compiles and runs it directly
 * with no test framework to pull in.
 */
tasks.register<JavaExec>("queueTuningCheck") {
    group = "verification"
    description = "Runs the queue socket timing and dedupe self-checks"

    dependsOn(compileQueueCheck)
    classpath = files(queueCheckOutput)
    mainClass.set("com.kevin.tiertagger.queue.QueueTuningCheck")
}

/** What the VTL Hub shows per kit and which button it offers (no game). */
tasks.register<JavaExec>("hubStateCheck") {
    group = "verification"
    description = "Runs the VTL Hub state self-checks"

    dependsOn(compileQueueCheck)
    classpath = files(queueCheckOutput) + queueCheckClasspath
    mainClass.set("com.kevin.tiertagger.hub.HubStateCheck")
    dependsOn("quickJoinCheck", "textFitCheck", "ticketLogCheck", "animatorCheck", "linkRequestCheck")
}

/** The hub figure's animations, test results read as up/down, and the GUI particles. */
tasks.register<JavaExec>("animatorCheck") {
    group = "verification"
    description = "Runs the figure animation self-checks (and the tier change and particle checks)"

    dependsOn(compileQueueCheck, "tierChangeCheck", "particleCheck")
    classpath = files(queueCheckOutput)
    mainClass.set("com.kevin.tiertagger.hub.anim.AnimatorCheck")
}

tasks.register<JavaExec>("tierChangeCheck") {
    group = "verification"
    description = "Runs the promotion/demotion self-checks"

    dependsOn(compileQueueCheck)
    classpath = files(queueCheckOutput)
    mainClass.set("com.kevin.tiertagger.hub.anim.TierChangeCheck")
}

tasks.register<JavaExec>("particleCheck") {
    group = "verification"
    description = "Runs the GUI particle self-checks"

    dependsOn(compileQueueCheck)
    classpath = files(queueCheckOutput)
    mainClass.set("com.kevin.tiertagger.hub.fx.ParticleCheck")
}

/** The ticket chat log: order, duplicates, unread count, sending, closing. */
/** The link request VTL Core sends (bot V39): Mojang proof id and body. */
tasks.register<JavaExec>("linkRequestCheck") {
    group = "verification"
    description = "Runs the link request self-checks"

    dependsOn(compileQueueCheck)
    classpath = files(queueCheckOutput) + queueCheckClasspath
    mainClass.set("com.kevin.tiertagger.queue.LinkRequestCheck")
}

tasks.register<JavaExec>("ticketLogCheck") {
    group = "verification"
    description = "Runs the ticket chat log self-checks"

    dependsOn(compileQueueCheck)
    classpath = files(queueCheckOutput) + queueCheckClasspath
    mainClass.set("com.kevin.tiertagger.queue.TicketLogCheck")
}

/** Text scales in the hub: sharp on screen pixels and inside their box. */
tasks.register<JavaExec>("textFitCheck") {
    group = "verification"
    description = "Runs the hub text scale self-checks"

    dependsOn(compileQueueCheck)
    classpath = files(queueCheckOutput)
    mainClass.set("com.kevin.tiertagger.hub.TextFitCheck")
}

/** The J quick join: which announced queue a key press joins, and when it does nothing. */
tasks.register<JavaExec>("quickJoinCheck") {
    group = "verification"
    description = "Runs the quick join self-checks"

    dependsOn(compileQueueCheck)
    classpath = files(queueCheckOutput) + queueCheckClasspath
    mainClass.set("com.kevin.tiertagger.queue.QuickJoinCheck")
}

/** Config store and ukulib-era TOML import checks (Gson only, no game). */
tasks.register<JavaExec>("configCheck") {
    group = "verification"
    description = "Runs the config store and legacy TOML import self-checks"

    dependsOn(compileQueueCheck)
    classpath = files(queueCheckOutput) + queueCheckClasspath
    mainClass.set("com.kevin.tiertagger.config.ConfigCheck")
}

/**
 * Runs the real QueueClient against a live gateway through every protocol v2 message and close
 * code. Start the mock first: node 111/mock-server/mock-queue-server.js
 * Other gateway: -PqueueUrl=ws://host:port/mod/ws -PdevUrl=http://host:port/dev
 */
tasks.register<JavaExec>("queueProtocolCheck") {
    group = "verification"
    description = "Runs the queue client against the mock server (start it first)"

    dependsOn(compileQueueCheck)
    classpath = files(queueCheckOutput) + queueCheckClasspath
    mainClass.set("com.kevin.tiertagger.queue.QueueProtocolCheck")
    args(
        (project.findProperty("queueUrl") ?: "ws://127.0.0.1:3056/mod/ws").toString(),
        (project.findProperty("devUrl") ?: "http://127.0.0.1:3057/dev").toString()
    )
}