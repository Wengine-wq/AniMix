package app.animix

import app.animix.core.DiskStore
import app.animix.data.AnimeRepository
import app.animix.data.cache.ResponseCache
import app.animix.data.shikimori.*
import app.animix.data.yummy.*
import app.animix.model.Anime
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.builtins.ListSerializer
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

class CatalogAvailabilityTest {
    @Test fun primaryResultsAreVisibleImmediatelyAndYummyStillJoinsAfterMoreThanTenSeconds() = runTest {
        val disk = DiskStore("build/test-catalog-${Random.nextInt(1_000_000_000)}")
        val cache = ResponseCache(disk)
        val query = CatalogQuery(search = "Фрирен")
        val primary = listOf(Anime.placeholder(52991).copy(title = "Фрирен"))
        cache.get(query.cacheKey, 1.hours, ListSerializer(Anime.serializer())) { primary }
        val release = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine {
            waiting.complete(Unit)
            release.await()
            respond("""{"response":[{"anime_id":17,"title":"Фрирен: дополняющий тайтл"}]}""", HttpStatusCode.OK)
        })
        try {
            val repository = AnimeRepository(ShikimoriRepository(ShikimoriApi(http), cache), YummyRepository(YummyApi(http), cache))
            val visible = CompletableDeferred<List<Anime>>()
            val request = async { repository.catalog(query, onBase = { visible.complete(it) }) }
            assertEquals(primary, visible.await())
            waiting.await()
            delay(15_000)
            assertFalse(request.isCompleted)
            release.complete(Unit)
            val combined = request.await()
            assertEquals(listOf("y17", "s52991"), combined.map { it.key })
            assertEquals(primary.single(), combined.last())
        } finally { http.close(); disk.clear() }
    }

    @Test fun yummyOnlyTitlesAreReturnedEvenWhenTheBaseCatalogHasNoMatches() = runTest {
        val disk = DiskStore("build/test-catalog-yummy-${Random.nextInt(1_000_000_000)}")
        val cache = ResponseCache(disk)
        val query = CatalogQuery(search = "Уникальный")
        cache.get(query.cacheKey, 1.hours, ListSerializer(Anime.serializer())) { emptyList() }
        val http = HttpClient(MockEngine {
            respond("""{"response":[{"anime_id":18,"title":"Уникальный тайтл"},{"anime_id":19,"title":"Уже в основе","remote_ids":{"shikimori_id":52991}}]}""", HttpStatusCode.OK)
        })
        try {
            val repository = AnimeRepository(ShikimoriRepository(ShikimoriApi(http), cache), YummyRepository(YummyApi(http), cache))
            assertEquals(listOf("y18"), repository.catalog(query).map { it.key })
        } finally { http.close(); disk.clear() }
    }

    @Test fun cancellingTheSearchCancelsYummyInsteadOfReturningAnIncompleteSuccess() = runTest {
        val disk = DiskStore("build/test-catalog-cancel-${Random.nextInt(1_000_000_000)}")
        val cache = ResponseCache(disk)
        val query = CatalogQuery(search = "Фрирен")
        cache.get(query.cacheKey, 1.hours, ListSerializer(Anime.serializer())) { listOf(Anime.placeholder(52991).copy(title = "Фрирен")) }
        val waiting = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine {
            waiting.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        try {
            val repository = AnimeRepository(ShikimoriRepository(ShikimoriApi(http), cache), YummyRepository(YummyApi(http), cache))
            val request = async { repository.catalog(query) }
            waiting.await()
            request.cancelAndJoin()
            cancelled.await()
            assertTrue(request.isCancelled)
        } finally { http.close(); disk.clear() }
    }
}
