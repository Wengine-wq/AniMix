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
    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        settings.load()
        library.load()
        achievements.load()
        watchFlow.load()
        downloads.load()
        initialized = true
    }

    /** A failed startup must not flush uninitialized default state over files on disk. */
    suspend fun flushUserData() {
        if (!initialized) return
        val stores = listOf<Pair<String, suspend () -> Unit>>(
            "WatchFlow" to watchFlow::flush, "Library" to library::flush,
            "Achievements" to achievements::flush, "Settings" to settings::flush,
        )
        for ((name, flush) in stores) {
            try { flush() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) { app.animix.core.AppLog.failure(name, "flush_failed", error) }
        }
    }

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

    val pulse = app.animix.data.pulse.PulseRepository(DiskStore(Platform.dataDir), scope,
        detail = { title -> if (title.source == app.animix.model.AnimeSource.Shikimori) shikimori.liveDetail(title.id)
            else anime.detail(title, forceRefresh = true) },
        editor = app.animix.data.pulse.PolzaPulseClient(),
        topics = { title -> shikimori.topics(title.id) })

    /** «Подобрано для вас» from the local library. */
    val recommendations = app.animix.data.Recommendations(shikimori, anime)

    /** Short-lived direct streams; independent of the catalog's stale/offline cache. */
    val kodik = KodikResolver(http)
    /** «Умное соединение» and «Экономия трафика». */
    val connection = app.animix.data.ConnectionPolicy(settings)
    val downloads = DownloadManager(scope, kodik, http, OfflineFiles("${Platform.dataDir}/downloads"), parallelism = { connection.downloadParallelism() }, onCompleted = achievements::downloadCompleted)

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
