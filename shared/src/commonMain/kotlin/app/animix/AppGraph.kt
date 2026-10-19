package app.animix

import app.animix.core.AppSettings
import app.animix.core.DiskStore
import app.animix.core.Platform
import app.animix.data.AnimeRepository
import app.animix.data.cache.ResponseCache
import app.animix.data.library.Library
import app.animix.data.achievements.Achievements
import app.animix.data.createHttpClient
import app.animix.data.playback.KodikResolver
import app.animix.data.downloads.DownloadManager
import app.animix.data.downloads.OfflineFiles
import app.animix.data.shikimori.ShikimoriApi
import app.animix.data.skips.SkipTimes
import app.animix.data.shikimori.ShikimoriRepository
import app.animix.data.yummy.YummyApi
import app.animix.data.yummy.YummyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Everything the app needs, built once at start. */
class AppGraph {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http = createHttpClient()

    val settings = AppSettings(DiskStore(Platform.dataDir), scope)

    /** The local profile and lists, stored on this device. */
    val library = Library(DiskStore(Platform.dataDir), scope)

    /** Achievements, earned from the local library, settings and in-app events. */
    val achievements = Achievements(DiskStore(Platform.dataDir), scope, library, settings)

    val watchFlow = app.animix.data.watchflow.WatchFlow(DiskStore(Platform.dataDir), scope)

    private val apiCache = ResponseCache(DiskStore("${Platform.cacheDir}/api"))

    val shikimori = ShikimoriRepository(ShikimoriApi(http), apiCache)

    val yummy = YummyRepository(YummyApi(http), apiCache)

    /** The combined catalog screens use: Shikimori plus YummyAnime-only titles. */
    val anime = AnimeRepository(shikimori, yummy)

    /** «Подобрано для вас» from the local library. */
    val recommendations = app.animix.data.Recommendations(shikimori, anime)

    /** Short-lived direct streams; independent of the catalog's stale/offline cache. */
    val kodik = KodikResolver(http)
    /** «Умное соединение» and «Экономия трафика». */
    val connection = app.animix.data.ConnectionPolicy(settings)
    val downloads = DownloadManager(scope, kodik, http, OfflineFiles("${Platform.dataDir}/downloads"), parallelism = { connection.downloadParallelism() })

    /** Opening and ending times from YummyAnime and AniSkip, remembered for offline viewing. */
    val skips = SkipTimes(http, DiskStore(Platform.dataDir), scope)

    val flowPlanner = app.animix.data.watchflow.FlowPlanner(yummy, anime, downloads, skips, library)

    /** Importing lists: Shikimori (signed in) and public Anixart lists. */
    val shikimoriAccount = app.animix.data.importer.ShikimoriAccount(http, DiskStore(Platform.dataDir))
    val anixart = app.animix.data.importer.AnixartClient(http)
    val anixartMatcher = app.animix.data.importer.AnixartMatcher(http, yummy, DiskStore(Platform.dataDir))
    val imports = app.animix.data.importer.ImportJobs(scope, library, shikimoriAccount, anixart, anixartMatcher)

    suspend fun clearCache() {
        apiCache.clear()
        kodik.clear()
    }
}
