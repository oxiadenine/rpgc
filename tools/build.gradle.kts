import java.net.URI

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

tasks.register<DownloadH2JarTask>("downloadH2Jar") {
    h2Version.set(libs.versions.h2database.get())
}