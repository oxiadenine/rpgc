plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":common"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.ktor)
    implementation(libs.typesafe.config)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("${project.group}.${rootProject.name}.${project.name}.MainKt")
}

tasks {
    test {
        useJUnitPlatform()
    }

    jar {
        archiveBaseName.set("${rootProject.name}-${project.name}")
        archiveVersion.set("")

        manifest.attributes["Main-Class"] = application.mainClass.get()

        from(configurations.runtimeClasspath.get().map { file: File ->
            if (file.isDirectory) file else zipTree(file)
        })

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}