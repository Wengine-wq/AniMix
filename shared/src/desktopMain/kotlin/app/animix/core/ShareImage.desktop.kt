package app.animix.core

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File

actual fun ImageBitmap.encodePng(): ByteArray =
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)

actual suspend fun sharePicture(png: ByteArray, name: String, title: String): String? = withContext(Dispatchers.IO) {
    val home = File(System.getProperty("user.home"))
    val dir = listOf(File(home, "Pictures"), File(home, "Изображения")).firstOrNull { it.isDirectory } ?: home
    val file = File(dir, name)
    file.writeBytes(png)
    runCatching { java.awt.Desktop.getDesktop().browseFileDirectory(file) }.recoverCatching { java.awt.Desktop.getDesktop().open(dir) }
    "Картинка сохранена: ${file.absolutePath}"
}
