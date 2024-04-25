pluginManagement {
    repositories {
        gradlePluginPortal()
    }

    plugins {
        val kotlinVersion = extra["kotlin.version"] as String

        kotlin("jvm").version(kotlinVersion)
        kotlin("plugin.serialization").version(kotlinVersion)
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
}

rootProject.name = "rpgc-bot"