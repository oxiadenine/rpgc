pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "rpgc"

include("common")
include("telegramBot", "discordBot")
include("api")
include("tools")