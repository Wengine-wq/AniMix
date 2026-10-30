package app.animix.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

actual fun deviceAbis(): List<String> = emptyList()

actual suspend fun downloadAndInstall(asset: UpdateAsset, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
    val dir = File(System.getProperty("java.io.tmpdir"), "AniMix-update").apply { mkdirs() }
    val file = download(asset, File(dir, asset.name), onProgress)
    when (file.extension.lowercase()) {
        "msi" -> { ProcessBuilder("msiexec", "/i", file.absolutePath).start(); "Установщик запущен. Закройте AniMix, чтобы он завершил обновление." }
        "exe" -> { ProcessBuilder(file.absolutePath).directory(dir).start(); "Установщик запущен. Закройте AniMix, чтобы он завершил обновление." }
        else -> installFromZip(file, dir)
    }
}

/**
 * A portable build updates itself: the archive is unpacked next to the download, and a small
 * PowerShell script waits for this AniMix to close, copies the new files over its folder and starts
 * it again. User data lives in %LOCALAPPDATA%\AniMix3 and is not touched.
 */
private fun installFromZip(zip: File, dir: File): String {
    val exe = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }
        ?: return "Архив скачан в ${zip.absolutePath}. Распакуйте его поверх папки AniMix."
    val installDir = exe.parentFile
    val unpacked = File(dir, "unpacked").apply { deleteRecursively(); mkdirs() }
    ZipFile(zip).use { archive ->
        val root = unpacked.canonicalFile.toPath()
        archive.entries().asSequence().forEach { entry ->
            val target = File(unpacked, entry.name).canonicalFile
            check(target.toPath().startsWith(root)) { "Unsafe archive entry" }
            if (entry.isDirectory) target.mkdirs()
            else { target.parentFile.mkdirs(); archive.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } } }
        }
    }
    // The archive holds one folder with AniMix.exe; copy that folder's contents.
    val source = unpacked.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name.equals("AniMix.exe", ignoreCase = true) }?.parentFile
        ?: return "В архиве обновления нет AniMix.exe. Скачайте релиз со страницы GitHub."
    val script = File(dir, "apply-update.ps1")
    script.writeText("""
        |${'$'}ErrorActionPreference = 'Stop'
        |Wait-Process -Id ${ProcessHandle.current().pid()} -ErrorAction SilentlyContinue
        |Start-Sleep -Seconds 1
        |robocopy "${source.absolutePath}" "${installDir.absolutePath}" /E /R:5 /W:2 /NFL /NDL /NJH /NJS | Out-Null
        |Start-Process -FilePath "${exe.absolutePath}"
        |""".trimMargin(), Charsets.UTF_8)
    ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.absolutePath)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    return "Обновление готово. Закройте AniMix — новая версия установится и запустится сама."
}

/** Streams a release file to [target] (via a .part file), following GitHub's redirect to its storage. */
private fun download(asset: UpdateAsset, target: File, onProgress: (Float) -> Unit): File {
    if (target.isFile && asset.size > 0 && target.length() == asset.size) return target.also { onProgress(1f) }
    val part = File(target.parentFile, target.name + ".part")
    val connection = java.net.URI(asset.url).toURL().openConnection() as java.net.HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("User-Agent", "AniMix-updater")
    try {
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size
        connection.inputStream.use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
    } finally { connection.disconnect() }
    check(asset.size <= 0 || part.length() == asset.size) { "Incomplete download" }
    target.delete()
    check(part.renameTo(target)) { "Could not save the update" }
    return target
}
