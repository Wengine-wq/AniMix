package app.animix.data.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopbackCallbackTest {
    @Test fun theBrowserRedirectIsCaughtAndAnsweredWithAPage() = runBlocking {
        kotlinx.coroutines.withTimeout(30_000) {
        val loopback = assertNotNullLoopback()
        try {
            assertTrue(loopback.returnUri.matches(Regex("""http://127\.0\.0\.1:\d+/oauth/callback""")))
            val result = async(Dispatchers.IO) { loopback.await() }
            val client = HttpClient.newHttpClient()
            // Stray requests (a favicon) do not end the wait.
            assertEquals(404, client.send(HttpRequest.newBuilder(URI(loopback.returnUri.replace("/oauth/callback", "/favicon.ico"))).timeout(java.time.Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode())
            val page = client.send(HttpRequest.newBuilder(URI("${loopback.returnUri}?ticket=t1&state=s1")).timeout(java.time.Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, page.statusCode())
            assertTrue("Вход выполнен" in page.body())
            assertEquals("${loopback.returnUri}?ticket=t1&state=s1", result.await())
        } finally { loopback.close() }
        }
    }

    private fun assertNotNullLoopback(): LoopbackCallback = openLoopbackCallback() ?: error("desktop must listen on loopback")
}
