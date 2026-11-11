package app.animix.core

import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

actual fun ImageBitmap.encodePng(): ByteArray =
    ByteArrayOutputStream().also { asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

actual suspend fun sharePicture(png: ByteArray, name: String, title: String): String? {
    val context = AndroidPlatform.context
    val file = withContext(Dispatchers.IO) {
        // Only this folder of the cache is shared, and only the last card is kept in it.
        val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        File(dir, name).apply { writeBytes(png) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    return null
}
