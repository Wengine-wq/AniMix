import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.file.DirectoryProperty

abstract class PrepareWindowsVlc : DefaultTask() {
    @get:OutputDirectory abstract val destination: DirectoryProperty
    @get:Input val archiveSha256 = "fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323"
    @get:Input val archiveUrl = "https://download.videolan.org/pub/videolan/vlc/3.0.24/win64/vlc-3.0.24-win64.zip"

    @TaskAction fun prepare() {
        val output = destination.get().asFile.canonicalFile
        val marker = output.resolve(".animix-runtime-sha256")
        if (marker.isFile && marker.readText() == archiveSha256 && output.resolve("libvlc.dll").isFile) return
        val archive = temporaryDir.resolve("vlc.zip")
        logger.lifecycle("Preparing verified VLC 3.0.24 Windows runtime")
        URI(archiveUrl).toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 30_000 }.getInputStream().use { input ->
            archive.outputStream().use { input.copyTo(it) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val read = input.read(bytes); if (read < 0) break; digest.update(bytes, 0, read) } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == archiveSha256) { "VLC archive checksum mismatch" }
        output.mkdirs()
        ZipFile(archive).use { zip -> zip.entries().asSequence().forEach { entry ->
            val relative = entry.name.substringAfter('/', "")
            if (relative.isNotBlank()) {
                val target = output.resolve(relative).canonicalFile
                check(target.toPath().startsWith(output.toPath())) { "Unsafe VLC archive entry" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        } }
        marker.writeText(archiveSha256)
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

val prepareWindowsVlc = tasks.register<PrepareWindowsVlc>("prepareWindowsVlc") {
    onlyIf { System.getProperty("os.name").startsWith("Windows") }
    destination.set(layout.buildDirectory.dir("native-resources/windows/vlc"))
}
tasks.matching { it.name in setOf("run", "prepareAppResources", "createDistributable") }.configureEach { dependsOn(prepareWindowsVlc) }
tasks.withType<JavaExec>().configureEach {
    dependsOn(prepareWindowsVlc)
    systemProperty("animix.vlc.dir", layout.buildDirectory.dir("native-resources/windows/vlc").get().asFile.absolutePath)
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna.platform)
}

compose.desktop {
    application {
        mainClass = "app.animix.desktop.MainKt"
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("native-resources"))
            modules("java.net.http", "jdk.unsupported")
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "AniMix"
            packageVersion = "1.4.0"
            description = "AniMix New Horizons"
            vendor = "AniMix"
            windows {
                iconFile.set(project.file("icons/animix.ico"))
                menuGroup = "AniMix"
                shortcut = true
                upgradeUuid = "6f1c3f5e-7a2b-4f0e-9a51-3d7c2f8b9e10"
            }
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
