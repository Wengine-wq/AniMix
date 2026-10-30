package app.animix.data

import app.animix.core.AppJson
import app.animix.core.Platform
import app.animix.core.createHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.fromHttpToGmtDate
import io.ktor.serialization.kotlinx.json.json

const val AppVersion = "3.0.0"

fun createHttpClient(): HttpClient = HttpClient(createHttpEngine()) {
    expectSuccess = true
    install(ContentNegotiation) { json(AppJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
    install(HttpRequestRetry) {
        maxRetries = 2
        retryOnServerErrors()
        retryOnException(retryOnTimeout = true)
        exponentialDelay(base = 2.0, maxDelayMs = 4_000)
    }
    HttpResponseValidator {
        // Server time anchors rewards that depend on the date (streaks, marathons, months).
        validateResponse { response ->
            response.headers[HttpHeaders.Date]?.let { date ->
                runCatching { date.fromHttpToGmtDate().timestamp }.getOrNull()?.let(app.animix.core.TrustedClock::observeServerTime)
            }
        }
    }
    defaultRequest {
        headers.append(HttpHeaders.UserAgent, "AniMix/$AppVersion (${Platform.name})")
        headers.append(HttpHeaders.Accept, "application/json")
    }
}
