plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.11"

/** Builds every node and copies the jars to build/libs/<mod version>/. */
tasks.register("chiseledBuild") {
    group = "build"
    description = "Builds the VTL Core jar for every Minecraft version"
    dependsOn(stonecutter.tasks.named("buildAndCollect"))
}
