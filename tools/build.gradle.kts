import java.net.URI

plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    val downloadH2JarTask = register<DownloadH2JarTask>("downloadH2Jar") {
        h2Version.set(libs.versions.h2database.get())
    }

    jar {
        archiveBaseName.set("${rootProject.name}-${project.name}")
        archiveVersion.set("")

        from(zipTree(downloadH2JarTask.get().jarFile))
        from(configurations.runtimeClasspath.get().map { file ->
            if (file.isDirectory) file else zipTree(file)
        })

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        dependsOn(downloadH2JarTask)
    }
}

abstract class DownloadH2JarTask : DefaultTask() {
    @get:Input
    abstract val h2Version: Property<String>

    @OutputDirectory
    val downloadsDir: Provider<Directory> = project.layout.buildDirectory.dir("downloads")

    @OutputFile
    val jarFile: File = File(downloadsDir.get().asFile, "h2.jar")

    @TaskAction
    fun action() {
        val url = URI("""
            https://github.com/h2database/h2database/releases/download/
            version-${h2Version.get()}/h2-${h2Version.get()}.jar
        """.trimIndent().replace("\n", "")).toURL()

        jarFile.writeBytes(url.readBytes())
    }
}