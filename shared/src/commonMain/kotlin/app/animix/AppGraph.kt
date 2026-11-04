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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import app.animix.data.library.level

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
        account.load()
        accountSync.start()
        social.start()
        notifier.start()
        initialized = true
        // New episodes of «Смотрю»: soon after start, then every half hour while AniMix runs.
        scope.launch {
            kotlinx.coroutines.delay(20_000)
            while (true) {
                runCatching { notifier.check() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                kotlinx.coroutines.delay(30 * 60_000L)
            }
        }
    }

    /** What the Android background check needs: settings, the library and the notifier, nothing that polls. */
    suspend fun prepareForBackground() {
        settings.load()
        library.load()
        notifier.start()
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

    /** The profile level, one value for every screen: confirmed playback plus earned awards. */
    val level: kotlinx.coroutines.flow.StateFlow<app.animix.data.library.ProfileLevel> =
        kotlinx.coroutines.flow.combine(library.state, achievements.progress) { lib, awards -> lib.level(awards.count { it.earned }) }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, app.animix.data.library.LibraryData().level(0))

    val watchFlow = app.animix.data.watchflow.WatchFlow(DiskStore(Platform.dataDir), scope)

    private val apiCache = ResponseCache(DiskStore("${Platform.cacheDir}/api"))

    val shikimori = ShikimoriRepository(ShikimoriApi(http), apiCache)

    val yummy = YummyRepository(YummyApi(http), apiCache)

    /** The combined catalog screens use: Shikimori plus YummyAnime-only titles. */
    val anime = AnimeRepository(shikimori, yummy)

    /** AniPulse: the week's digest, counted on the device from the lists and the catalog. */
    val pulse by lazy { app.animix.data.pulse.PulseDigest(shikimori, recommendations, library, news = { animeNews.week() }) }
    /** Anime news of the week from Shikimori, YummyAnime and GoHa.ru. */
    val animeNews by lazy { app.animix.data.news.AnimeNews(http, shikimori, apiCache) }

    /** «Уведомления»: new episodes of ongoing titles in «Смотрю», and in their chosen dubbing. */
    val notifier = app.animix.data.notify.EpisodeNotifier(DiskStore(Platform.dataDir), library, settings,
        cards = { ids -> shikimori.byIds(ids) }, source = { title -> yummy.watchSource(title, forceRefresh = true) },
        post = app.animix.data.notify.SystemNotifications::show)

    /** «Подобрано для вас» from the local library. */
    val recommendations = app.animix.data.Recommendations(shikimori, anime)

    /** Short-lived direct streams; independent of the catalog's stale/offline cache. */
    val kodik = KodikResolver(http)
    /** «Умное соединение» and «Экономия трафика». */
    val connection = app.animix.data.ConnectionPolicy(settings)
    val downloads: DownloadManager = DownloadManager(scope, kodik, http, OfflineFiles("${Platform.dataDir}/downloads"), parallelism = { connection.downloadParallelism() }, onCompleted = { id ->
        downloads.state.value.firstOrNull { it.id == id }?.let { achievements.downloadCompleted(it.anime.key, it.episode.number) }
    })

    /** Opening and ending times from YummyAnime and AniSkip, remembered for offline viewing. */
    val skips = SkipTimes(http, DiskStore(Platform.dataDir), scope)

    val flowPlanner = app.animix.data.watchflow.FlowPlanner(yummy, anime, downloads, skips, library)

    /** Importing lists: Shikimori (signed in) and public Anixart lists. */
    val shikimoriAccount = app.animix.data.importer.ShikimoriAccount(http, DiskStore(Platform.dataDir))
    val anixart = app.animix.data.importer.AnixartClient(http)
    val anixartMatcher = app.animix.data.importer.AnixartMatcher(http, yummy, DiskStore(Platform.dataDir))
    /** New versions from GitHub Releases, offered on the home screen. */
    val updates = app.animix.data.update.AppUpdates(http, scope)
    /** «Миграция с AniMix 2.x» in Settings → «Аккаунт». */
    /** The online AniMix profile on the account server. */
    val account = app.animix.data.account.AniMixAccount(http, DiskStore(Platform.dataDir), scope)
    val accountSync = app.animix.data.account.AccountSync(account, library, achievements, settings, DiskStore(Platform.dataDir), scope,
        titles = { ids -> ids.chunked(50).flatMap { shikimori.byIds(it) } })
    val social = app.animix.data.account.AccountSocial(account, DiskStore(Platform.dataDir), scope)
    val legacy = app.animix.data.legacy.LegacyMigration(scope, library, achievements, account, titles = { ids -> shikimori.byIds(ids) })
    val imports = app.animix.data.importer.ImportJobs(scope, library, shikimoriAccount, anixart, anixartMatcher)

    suspend fun clearCache() {
        apiCache.clear()
        kodik.clear()
    }
}
