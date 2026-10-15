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
