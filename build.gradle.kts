plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.ktor)
    implementation(libs.typesafe.config)
    implementation(libs.jsoup)
    implementation(libs.openhtmltopdf.java2d)
    implementation(libs.retrofit)
    implementation(libs.kotlin.telegram.bot)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikaricp)
    implementation(libs.h2)
    implementation(libs.logback.classic)

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