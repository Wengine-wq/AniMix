package app.animix.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.animix.core.AndroidPlatform

private object DownloadServiceState { var timeout: () -> Unit = {}; var started = false }

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

class OfflineDownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("animix_downloads", "Загрузки AniMix", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "animix_downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AniMix · Загрузка серий")
            .setContentText("Очередь работает. Управление — на вкладке «Загрузки».")
            .setOngoing(true).build()
        startForeground(201, notification)
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadServiceState.timeout()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { DownloadServiceState.started = false; super.onDestroy() }
}
