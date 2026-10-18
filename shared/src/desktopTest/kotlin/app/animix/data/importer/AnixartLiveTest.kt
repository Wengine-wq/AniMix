package app.animix.data.importer

import app.animix.core.DiskStore
import app.animix.data.cache.ResponseCache
import app.animix.data.createHttpClient
import app.animix.data.yummy.YummyApi
import app.animix.data.yummy.YummyRepository
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertTrue

/** Real Anixart and Shikimori: run with ANIMIX_NETWORK_TEST=1 to measure how many titles are found. */
class AnixartLiveTest {
    @Test fun publicListsAreMatchedToShikimori() = runBlocking {
        assumeTrue("Set ANIMIX_NETWORK_TEST=1", System.getenv("ANIMIX_NETWORK_TEST") == "1")
        val profileId = System.getenv("ANIXART_PROFILE")?.toLongOrNull() ?: 20735L
        val folder = Files.createTempDirectory("animix-anixart-").toFile()
        val http = createHttpClient()
        try {
            val client = AnixartClient(http)
            val yummy = YummyRepository(YummyApi(http), ResponseCache(DiskStore(folder.absolutePath)))
            val matcher = AnixartMatcher(http, yummy, DiskStore(folder.absolutePath))
            val releases = AnixartList.entries.flatMap { client.releases(profileId, it) }.distinctBy { it.id }
            val started = System.currentTimeMillis()
            val matches = matcher.match(releases) { _, _ -> }
            val found = matches.count { it.anime != null }
            println("Anixart profile $profileId: matched $found of ${releases.size} in ${(System.currentTimeMillis() - started) / 1000} s")
            matches.filter { it.anime == null }.forEach { println("  not found: ${it.release.titleRu} / ${it.release.titleOriginal} (${it.release.year}, ${it.release.category})") }
            matches.filter { it.anime != null }.take(400).forEach { println("  ${it.release.titleOriginal} (${it.release.year}) -> ${it.anime!!.originalTitle} (${it.anime!!.year}) ${it.anime!!.key}") }
            assertTrue(releases.isEmpty() || found * 100 / releases.size >= 90, "Matched only $found of ${releases.size}")
        } finally {
            http.close()
            folder.deleteRecursively()
        }
    }
}
