package app.animix.core

import android.content.Context
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/** Holds the application context; set once from Application.onCreate. */
object AndroidPlatform {
    internal lateinit var context: Context
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}

actual object Platform {
    actual val name: String = "Android"

    actual val dataDir: String get() = AndroidPlatform.context.filesDir.absolutePath

    actual val cacheDir: String get() = AndroidPlatform.context.cacheDir.absolutePath
}

actual fun createHttpEngine(): HttpClientEngine = OkHttp.create()

actual fun deviceName(): String {
    val maker = android.os.Build.MANUFACTURER.orEmpty().trim().replaceFirstChar { it.uppercase() }
    val model = android.os.Build.MODEL.orEmpty().trim()
    // Many models already start with the maker («Pixel» does not, «SM-S911B» does not, «Redmi Note 12» does).
    val device = if (model.startsWith(maker, ignoreCase = true) || maker.isEmpty()) model else "$maker $model"
    return "${device.ifBlank { "Android" }} · Android ${android.os.Build.VERSION.RELEASE}"
}

actual fun localDayIndex(millis: Long): Long =
    Math.floorDiv(millis + java.util.TimeZone.getDefault().getOffset(millis), 86_400_000L)

actual fun utcOffsetMillis(millis: Long): Long = java.util.TimeZone.getDefault().getOffset(millis).toLong()
