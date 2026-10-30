package app.animix.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual fun deviceAbis(): List<String> = emptyList()

actual suspend fun downloadAndInstall(asset: UpdateAsset, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
    val dir = File(System.getProperty("java.io.tmpdir"), "AniMix-update").apply { mkdirs() }
    val file = download(asset, File(dir, asset.name), onProgress)
    when (file.extension.lowercase()) {
        "msi" -> ProcessBuilder("msiexec", "/i", file.absolutePath).start()
        "exe" -> ProcessBuilder(file.absolutePath).directory(dir).start()
        else -> ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
    }
    when (file.extension.lowercase()) {
        "msi", "exe" -> "Установщик запущен. Закройте AniMix, чтобы он завершил обновление."
        else -> "Архив скачан и открыт в проводнике: распакуйте его поверх папки AniMix."
    }
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
