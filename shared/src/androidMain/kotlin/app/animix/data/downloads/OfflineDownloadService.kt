package app.animix.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import app.animix.core.AndroidPlatform

private object DownloadServiceState {
    var timeout: () -> Unit = {}
    var started = false
    var title = "Загрузка серий"
    var percent: Int? = null
}

internal actual fun downloadActivity(active: Boolean, onTimeout: () -> Unit) {
    Handler(Looper.getMainLooper()).post {
        val context = AndroidPlatform.context
        val intent = Intent(context, OfflineDownloadService::class.java)
        DownloadServiceState.timeout = onTimeout
        if (active && !DownloadServiceState.started) {
            try {
                context.startForegroundService(intent)
                DownloadServiceState.started = true
            } catch (_: IllegalStateException) { onTimeout() }
        } else if (!active && DownloadServiceState.started) {
            DownloadServiceState.started = false
            context.stopService(intent)
        }
    }
}

internal actual fun downloadProgress(title: String, percent: Int?) {
    Handler(Looper.getMainLooper()).post {
        DownloadServiceState.title = title
        DownloadServiceState.percent = percent
        if (!DownloadServiceState.started) return@post
        val context = AndroidPlatform.context
        context.getSystemService(NotificationManager::class.java)?.notify(OfflineDownloadService.NOTIFICATION, OfflineDownloadService.notification(context))
    }
}

/**
 * Keeps the queue alive with the screen off: a foreground service, plus a CPU wake lock and
 * a Wi-Fi lock so sockets do not stall while the device sleeps.
 */
class OfflineDownloadService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки AniMix", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notification(this))
        holdLocks()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun holdLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AniMix:downloads")
                ?.apply { setReferenceCounted(false); acquire(LOCK_LIMIT_MS) }
        }
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(mode, "AniMix:downloads")
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadServiceState.timeout()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { DownloadServiceState.started = false; releaseLocks(); super.onDestroy() }

    internal companion object {
        const val CHANNEL = "animix_downloads"
        const val NOTIFICATION = 201
        /** Safety net: the dataSync service itself is limited to 6 hours a day. */
        const val LOCK_LIMIT_MS = 6L * 60 * 60 * 1000

        fun notification(context: Context): Notification {
            val percent = DownloadServiceState.percent
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(DownloadServiceState.title)
                .setContentText(if (percent != null) "$percent % · управление на вкладке «Загрузки»" else "Готовим серию к загрузке…")
                .setProgress(100, percent ?: 0, percent == null)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .apply { if (open != null) setContentIntent(open) }
                .build()
        }
    }
}
