package app.animix.data.account

import app.animix.core.DiskStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

class AccountSessionTest {
    private suspend fun session(disk: DiskStore) = disk.writeChecked("animix-account.json",
        """{"accessToken":"old","refreshToken":"refresh","user":{"id":"u1","name":"User"}}""")

    @Test fun concurrentUnauthorizedRequestsRefreshOnlyOnce() = runBlocking<Unit> {
        val root = Files.createTempDirectory("animix-auth-").toFile(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val count = AtomicInteger()
        val engine = MockEngine { request ->
            val headers = headersOf(HttpHeaders.ContentType, "application/json")
            if (request.url.encodedPath == "/v1/auth/refresh") {
                count.incrementAndGet(); delay(100)
                respond("""{"access_token":"new","refresh_token":"rotated"}""", headers = headers)
            } else if (request.headers[HttpHeaders.Authorization] == "Bearer old") respond("{}", HttpStatusCode.Unauthorized, headers)
            else respond("{}", headers = headers)
        }
        val http = HttpClient(engine) { expectSuccess = true }
        try {
            val disk = DiskStore(root.absolutePath); session(disk)
            val account = AniMixAccount(http, disk, scope, "https://test").apply { load() }
            coroutineScope { List(12) { async { account.getJson("/v1/me/sync", "u1") } }.awaitAll() }
            assertEquals(1, count.get())
            assertNotNull(account.state.value.user)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); http.close(); root.deleteRecursively() }
    }

    @Test fun signOutDuringRefreshCannotRestoreTheSession() = runBlocking<Unit> {
        val root = Files.createTempDirectory("animix-auth-").toFile(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { request ->
            val headers = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.encodedPath) {
                "/v1/auth/refresh" -> { entered.complete(Unit); resume.await(); respond("""{"access_token":"new"}""", headers = headers) }
                "/v1/auth/logout" -> respond("{}", headers = headers)
                else -> respond("{}", HttpStatusCode.Unauthorized, headers)
            }
        }) { expectSuccess = true }
        try {
            val disk = DiskStore(root.absolutePath); session(disk)
            val account = AniMixAccount(http, disk, scope, "https://test").apply { load() }
            val pending = async { runCatching { account.getJson("/v1/me/sync", "u1") } }
            withTimeout(5000) { entered.await() }
            account.signOut(); resume.complete(Unit)
            assertTrue(pending.await().isFailure)
            withTimeout(5000) { while (disk.readChecked("animix-account.json") != null) delay(20) }
            assertNull(account.state.value.user)
            assertNull(AniMixAccount(http, disk, scope, "https://test").apply { load() }.state.value.user)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); http.close(); root.deleteRecursively() }
    }

    @Test fun lookalikeOAuthCallbackIsNotAccepted() = runBlocking<Unit> {
        val root = Files.createTempDirectory("animix-auth-").toFile(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient(MockEngine { error("Spoof callback must not reach the exchange") })
        try {
            val account = AniMixAccount(http, DiskStore(root.absolutePath), scope).apply { load() }
            account.signInUrl()
            assertFalse(account.handlesCallback("animix://oauth/callback-evil?ticket=x&state=y"))
            assertFalse(account.handlesCallback("animix://oauth@evil.test/callback?ticket=x&state=y"))
            assertNull(account.state.value.user)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); http.close(); root.deleteRecursively() }
    }
}
