plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.14-SNAPSHOT"
    id("io.freefair.lombok") version "9.1.0"
}

version = "${project.property("mod_version")}+mc${project.property("minecraft_version")}"
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
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")

    // Fabric API modules the mod uses; players install Fabric API itself.
    val fabricApiVersion = project.property("fabric_api_version") as String
    for (module in listOf(
        "fabric-command-api-v2",
        "fabric-key-binding-api-v1",
        "fabric-lifecycle-events-v1",
        "fabric-screen-api-v1",
        "fabric-rendering-v1",
        "fabric-resource-loader-v0",
    )) {
        modImplementation(fabricApi.module(module, fabricApiVersion))
    }
    // fabric.mod.json depends on "fabric-api" itself, so the dev client needs the whole thing
    modLocalRuntime("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // ModMenu is optional: only its API is needed to compile the settings button.
    modCompileOnly("com.terraformersmc:modmenu:${project.property("modmenu_version")}") { isTransitive = false }

    modRuntimeOnly("me.djtheredstoner:DevAuth-fabric:${project.property("devauth_version")}")
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
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.processResources {
    inputs.property("version", project.version)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// The released jar is named after the game version only (VTL-Core+mc1.21.11.jar); the mod
// version stays in fabric.mod.json, where Mod Menu shows it.
tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("remapJar") {
    archiveFileName.set("${base.archivesName.get()}+mc${project.property("minecraft_version")}.jar")
}

tasks.jar {
    from("LICENSE") {
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
    source("src/main/java/com/kevin/tiertagger/queue/QueueTuning.java")
    source("src/main/java/com/kevin/tiertagger/queue/QueueMessages.java")
    source("src/main/java/com/kevin/tiertagger/queue/QueueClient.java")
    source("src/test/java/com/kevin/tiertagger/queue/QueueTuningCheck.java")
    source("src/test/java/com/kevin/tiertagger/queue/QueueProtocolCheck.java")
    source("src/main/java/com/kevin/tiertagger/config/JsonConfigStore.java")
    source("src/main/java/com/kevin/tiertagger/config/LegacyTomlImport.java")
    source("src/test/java/com/kevin/tiertagger/config/ConfigCheck.java")
    source("src/main/java/com/kevin/tiertagger/hub/Kit.java")
    source("src/main/java/com/kevin/tiertagger/hub/HubState.java")
    source("src/test/java/com/kevin/tiertagger/hub/HubStateCheck.java")
    source("src/main/java/com/kevin/tiertagger/queue/QuickJoin.java")
    source("src/test/java/com/kevin/tiertagger/queue/QuickJoinCheck.java")
    source("src/main/java/com/kevin/tiertagger/hub/TextFit.java")
    source("src/main/java/com/kevin/tiertagger/queue/TicketLog.java")
    source("src/test/java/com/kevin/tiertagger/queue/TicketLogCheck.java")
    source("src/main/java/com/kevin/tiertagger/queue/LinkRequest.java")
    source("src/test/java/com/kevin/tiertagger/queue/LinkRequestCheck.java")
    source("src/main/java/com/kevin/tiertagger/hub/anim/Pose.java")
    source("src/main/java/com/kevin/tiertagger/hub/anim/VtlModelAnimator.java")
    source("src/main/java/com/kevin/tiertagger/hub/anim/TierChange.java")
    source("src/main/java/com/kevin/tiertagger/hub/fx/VtlParticleSystem.java")
    source("src/test/java/com/kevin/tiertagger/hub/anim/AnimatorCheck.java")
    source("src/test/java/com/kevin/tiertagger/hub/anim/TierChangeCheck.java")
    source("src/test/java/com/kevin/tiertagger/hub/fx/ParticleCheck.java")
    source("src/test/java/com/kevin/tiertagger/hub/TextFitCheck.java")
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