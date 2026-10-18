package app.animix.data

import app.animix.core.AppSettings

/** True on a metered connection (mobile data, hotspot). */
expect fun isNetworkMetered(): Boolean

/**
 * «Умное соединение»: how hard the app may use the network right now.
 * With it on, the next episode is resolved ahead and downloads fetch several parts at once;
 * with «Экономия трафика» on a metered network, playback starts at 720p at most and downloads
 * use fewer parallel connections.
 */
class ConnectionPolicy(private val settings: AppSettings) {
    private val smart: Boolean get() = settings.state.value.smartConnection

    /** Traffic saving applies only on a metered network, and only when the viewer keeps it on. */
    val savingTraffic: Boolean get() = settings.state.value.trafficSaver && isNetworkMetered()

    /** Resolve the next episode while the current one plays, so it starts at once. */
    val prefetch: Boolean get() = smart && !settings.state.value.batterySaver

    fun downloadParallelism(): Int = when {
        settings.state.value.batterySaver -> 1
        !smart -> 1
        savingTraffic -> 2
        else -> 4
    }

    /** Highest playback quality to start with, in lines; null when there is no limit. */
    fun playbackCap(): Int? = if (savingTraffic) 720 else null
}
