package app.animix.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.animix.core.AndroidPlatform

actual object SystemNotifications {
    private const val CHANNEL = "episodes"

    actual fun show(notice: EpisodeNotice) {
        val context = AndroidPlatform.context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Новые серии", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Онгоинги из «Смотрю» и серии в выбранной озвучке"
                })
        }
        // The animix:// link opens AniMix on that episode, as any other link to the app does.
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(notice.link())).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, notice.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val (title, text) = notice.text()
        val icon = context.applicationInfo.icon
        runCatching {
            NotificationManagerCompat.from(context).notify(notice.id.hashCode(), NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(icon).setContentTitle(title).setContentText(text)
                .setContentIntent(pending).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION).build())
        }
    }
}
