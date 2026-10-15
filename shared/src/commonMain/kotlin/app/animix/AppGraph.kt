package app.animix

import app.animix.core.AppSettings
import app.animix.core.DiskStore
import app.animix.core.Platform
import app.animix.data.AnimeRepository
import app.animix.data.cache.ResponseCache
import app.animix.data.library.Library
import app.animix.data.createHttpClient
import app.animix.data.shikimori.ShikimoriApi
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

    private val apiCache = ResponseCache(DiskStore("${Platform.cacheDir}/api"))

    val shikimori = ShikimoriRepository(ShikimoriApi(http), apiCache)

    val yummy = YummyRepository(YummyApi(http), apiCache)

    /** The combined catalog screens use: Shikimori plus YummyAnime-only titles. */
    val anime = AnimeRepository(shikimori, yummy)

    suspend fun clearCache() = apiCache.clear()
}
