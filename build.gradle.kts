plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.3"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

intellij {
    // Target PhpStorm 2024.3 with bundled PHP plugin
    type.set("PS")
    version.set("2024.3")
    plugins.set(listOf("com.jetbrains.php"))
}

// Configure plugin metadata from gradle.properties
version = providers.gradleProperty("pluginVersion").get()

// Build tasks
tasks {
    patchPluginXml {
        sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
        untilBuild.set(providers.gradleProperty("pluginUntilBuild").orNull)
        pluginDescription.set(
            """
            Hidden Refactoring: attach IDE-level comments to PHP classes, methods, and files without modifying source code.
            """.trimIndent()
        )
        changeNotes.set("Initial scaffold with persistent comments storage and Add Comment action.")
    }

    buildSearchableOptions {
        enabled = false
    }
}
