package app.animix.data.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.animix.core.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual fun deviceAbis(): List<String> = Build.SUPPORTED_ABIS.toList()

actual suspend fun downloadAndInstall(asset: UpdateAsset, onProgress: (Float) -> Unit): String? {
    val context = AndroidPlatform.context
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { it.name != asset.name }?.forEach { it.delete() }
        download(asset, File(dir, asset.name), onProgress)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Разрешите AniMix устанавливать приложения и нажмите «Обновить» ещё раз — файл уже скачан."
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    return null
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
