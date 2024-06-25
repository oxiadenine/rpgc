plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    val kotlinxCoroutinesVersion = properties["kotlinx-coroutines.version"] as String
    val kotlinxSerializationVersion = properties["kotlinx-serialization.version"] as String
    val ktorVersion = properties["ktor.version"] as String
    val typesafeConfigVersion = properties["typesafe-config.version"] as String
    val jsoupVersion = properties["jsoup.version"] as String
    val retrofitVersion = properties["retrofit.version"] as String
    val kotlinTelegramBotVersion = properties["kotlin-telegram-bot.version"] as String
    val exposedVersion = properties["exposed.version"] as String
    val hikaricpVersion = properties["hikaricp.version"] as String
    val h2databaseVersion = properties["h2database.version"] as String

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("com.typesafe:config:$typesafeConfigVersion")
    implementation("org.jsoup:jsoup:$jsoupVersion")
    implementation("com.squareup.retrofit2:retrofit:$retrofitVersion")
    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:$kotlinTelegramBotVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("com.zaxxer:HikariCP:$hikaricpVersion")
    implementation("com.h2database:h2:$h2databaseVersion")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("${project.group}.rpgcbot.MainKt")
}

tasks {
    test {
        useJUnitPlatform()
    }

    jar {
        archiveBaseName.set(project.name)
        archiveVersion.set("")

        manifest.attributes["Main-Class"] = application.mainClass.get()

        from(configurations.runtimeClasspath.get().map { file: File ->
            if (file.isDirectory) file else zipTree(file)
        })

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}