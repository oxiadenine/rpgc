plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kord.core)
    implementation(libs.typesafe.config)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("${project.group}.${rootProject.name}.bot.MainKt")
}

tasks {
    test {
        useJUnitPlatform()
    }

    jar {
        archiveBaseName.set("${rootProject.name}-bot")
        archiveVersion.set("")

        manifest.attributes["Main-Class"] = application.mainClass.get()

        from(configurations.runtimeClasspath.get().map { file: File ->
            if (file.isDirectory) file else zipTree(file)
        })

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}