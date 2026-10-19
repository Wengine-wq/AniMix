package app.animix.data.profile

import androidx.compose.runtime.*
import app.animix.data.library.ProfileMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.geom.AffineTransform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import org.w3c.dom.Node
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.io.File
import kotlin.math.max

@Composable
actual fun rememberProfileImagePicker(onPicked: (ProfileImageKind, ProfileMedia) -> Unit, onError: (String) -> Unit): ProfileImagePicker {
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val error by rememberUpdatedState(onError)
    var busy by remember { mutableStateOf(false) }
    return ProfileImagePicker(busy) { kind ->
        if (!busy) scope.launch {
            busy = true
            try {
                val source = withContext(Dispatchers.Swing) {
                    JFileChooser().apply {
                        dialogTitle = if (kind == ProfileImageKind.Avatar) "Аватар профиля" else "Баннер профиля"
                        fileFilter = FileNameExtensionFilter("Изображения · PNG, JPG, GIF", "png", "jpg", "jpeg", "gif")
                        isAcceptAllFileFilterUsed = false
                    }.let { if (it.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) it.selectedFile else null }
                }
                if (source != null) picked(kind, withContext(Dispatchers.IO) { prepareProfileImage(source, kind) })
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error("Не удалось открыть изображение. Выберите PNG или JPG размером до 25 МБ.") }
            finally { busy = false }
        }
    }
}

internal fun prepareProfileImage(source: File, kind: ProfileImageKind, output: File? = null): ProfileMedia {
    require(source.length() in 1..25L * 1024 * 1024)
    val image = ImageIO.createImageInputStream(source).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("Unsupported image")
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000)
            var sample = 1
            while (max(width, height) / sample > 3200) sample *= 2
            val orientation = runCatching { exifOrientation(reader.getImageMetadata(0).getAsTree("javax_imageio_jpeg_image_1.0")) }.getOrDefault(1)
            orientImage(reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }), orientation)
        } finally { reader.dispose() }
    }
    val result = BufferedImage(kind.width, kind.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = result.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        val scale = max(kind.width.toDouble() / image.width, kind.height.toDouble() / image.height)
        val width = (image.width * scale).toInt()
        val height = (image.height * scale).toInt()
        graphics.drawImage(image, (kind.width - width) / 2, (kind.height - height) / 2, width, height, null)
        val file = output ?: File(newProfileImageFile(kind).toString())
        check(ImageIO.write(result, "png", file))
        return ProfileMedia(localFileName = file.name)
    } finally { graphics.dispose(); image.flush(); result.flush() }
}

private fun exifOrientation(node: Node): Int {
    val bytes = (node as? IIOMetadataNode)?.userObject as? ByteArray
    if (bytes != null && bytes.size >= 14 && bytes.take(6) == listOf<Byte>(69, 120, 105, 102, 0, 0)) {
        return runCatching {
            val data = ByteBuffer.wrap(bytes).order(if (bytes[6] == 73.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            require(data.getShort(8).toInt() == 42)
            val directory = 6 + data.getInt(10)
            val count = data.getShort(directory).toInt() and 0xffff
            for (index in 0 until count) {
                val offset = directory + 2 + index * 12
                if ((data.getShort(offset).toInt() and 0xffff) == 0x112) return@runCatching data.getShort(offset + 8).toInt().coerceIn(1, 8)
            }
            1
        }.getOrDefault(1)
    }
    var child = node.firstChild
    while (child != null) {
        val value = exifOrientation(child)
        if (value != 1) return value
        child = child.nextSibling
    }
    return 1
}

private fun orientImage(image: BufferedImage, orientation: Int): BufferedImage {
    val w = image.width.toDouble()
    val h = image.height.toDouble()
    val transform = when (orientation) {
        2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
        3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
        4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
        5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
        6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
        7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
        8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
        else -> return image
    }
    val result = BufferedImage(if (orientation >= 5) image.height else image.width,
        if (orientation >= 5) image.width else image.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = result.createGraphics()
    try { graphics.drawImage(image, transform, null) } finally { graphics.dispose(); image.flush() }
    return result
}
