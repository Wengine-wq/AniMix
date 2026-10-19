package app.animix.data.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import app.animix.data.library.ProfileMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

@Composable
actual fun rememberProfileImagePicker(onPicked: (ProfileImageKind, ProfileMedia) -> Unit, onError: (String) -> Unit): ProfileImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val reportError by rememberUpdatedState(onError)
    var pending by rememberSaveable { mutableStateOf<ProfileImageKind?>(null) }
    var processing by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        val kind = pending
        pending = null
        if (uri != null && kind != null) scope.launch {
            processing = true
            try {
                val media = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000)
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
                    val decoded = resolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: error("Cannot decode image")
                    val orientation = runCatching { resolver.openInputStream(uri)?.use {
                        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    } }.getOrNull()
                    val matrix = Matrix().apply {
                        when (orientation) {
                            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                        }
                    }
                    val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    val result = Bitmap.createBitmap(kind.width, kind.height, Bitmap.Config.ARGB_8888)
                    try {
                        val scale = max(kind.width.toFloat() / oriented.width, kind.height.toFloat() / oriented.height)
                        val left = (kind.width - oriented.width * scale) / 2
                        val top = (kind.height - oriented.height * scale) / 2
                        Canvas(result).drawBitmap(oriented, null, RectF(left, top, left + oriented.width * scale, top + oriented.height * scale), Paint(Paint.FILTER_BITMAP_FLAG))
                        val file = File(newProfileImageFile(kind).toString())
                        file.outputStream().use { check(result.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        ProfileMedia(localFileName = file.name)
                    } finally {
                        result.recycle()
                        if (oriented !== decoded) oriented.recycle()
                        decoded.recycle()
                    }
                }
                picked(kind, media)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { reportError("Не удалось открыть изображение. Попробуйте другой файл.") }
            finally { processing = false }
        }
    }
    return ProfileImagePicker(processing || pending != null) { kind ->
        if (!processing && pending == null) {
            pending = kind
            try { launcher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
            catch (_: Exception) { pending = null; reportError("Не удалось открыть выбор фотографии.") }
        }
    }
}
