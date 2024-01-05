plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    val kotlinTelegramBotVersion = properties["kotlin-telegram-bot.version"] as String

    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:$kotlinTelegramBotVersion")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("${project.group}.rpgcbot.MainKt")
}

tasks.test {
    useJUnitPlatform()
}