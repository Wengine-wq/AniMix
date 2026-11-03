package app.animix.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * «Уведомления» while AniMix is closed: every three hours, on any network, the same check the open app
 * runs every half hour. It reads only the library and settings; nothing else starts in the background.
 */
class EpisodeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as AniMixApplication).graph
        return runCatching {
            graph.prepareForBackground()
            graph.notifier.check(minIntervalMs = 25 * 60_000L)
            Result.success()
        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; Result.retry() }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EpisodeWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("new-episodes", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
