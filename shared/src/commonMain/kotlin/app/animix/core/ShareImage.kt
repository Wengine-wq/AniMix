package app.animix.core

import androidx.compose.ui.graphics.ImageBitmap

/** PNG bytes of a picture drawn by the app (a card to share). */
expect fun ImageBitmap.encodePng(): ByteArray

/**
 * Hands a picture made by AniMix to the person: the share sheet on Android, a saved file in «Изображения»
 * on Windows. Returns what happened in words for a line under the button, or null when the share sheet opened.
 */
expect suspend fun sharePicture(png: ByteArray, name: String, title: String): String?
