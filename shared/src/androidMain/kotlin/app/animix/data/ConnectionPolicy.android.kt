package app.animix.data

import android.net.ConnectivityManager
import app.animix.core.AndroidPlatform

actual fun isNetworkMetered(): Boolean = runCatching {
    AndroidPlatform.context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
}.getOrDefault(false)
