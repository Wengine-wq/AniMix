package app.animix.data.news

import app.animix.core.AppJson
import app.animix.core.RichTextParser
import app.animix.data.cache.ResponseCache
import app.animix.data.shikimori.ShikimoriRepository
import app.animix.model.NewsItem
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The week's anime news for AniPulse from Russian-language sources: the Shikimori news feed, the
 * news of YummyAnime and the anime section of GoHa.ru. Each source may fail on its own; the same
 * story told by two sources is shown once, the version with a picture preferred.
 */
class AnimeNews(private val http: HttpClient, private val shikimori: ShikimoriRepository, private val cache: ResponseCache) {

    suspend fun week(): List<NewsItem> = cache.get("news:week:all", ttl = 30.minutes, ListSerializer(NewsItem.serializer())) {
        coroutineScope {
            val sources = listOf(
                async { safe { shikimori.weeklyNews() } },
                async { safe { yummy() } },
                async { safe { goha() } },
            ).awaitAll()
            val cutoff = kotlin.time.Clock.System.now().toEpochMilliseconds() - 8L * 86_400_000
            merge(sources.flatten().filter { millis(it.createdAt)?.let { at -> at >= cutoff } ?: true })
        }
    }

    /** YummyAnime posts in «новости аниме»; their pictures are AVIF only, so the kind icon stands in. */
    private suspend fun yummy(): List<NewsItem> {
        val posts = AppJson.parseToJsonElement(http.get("https://api.yani.tv/posts?limit=40").bodyAsText())
            .jsonObject["response"]?.jsonArray.orEmpty()
        return posts.mapNotNull { element ->
            val post = element.jsonObject
            if (post["category"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull != "anime_news") return@mapNotNull null
            val id = post["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val title = post["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val at = post["created_at"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            NewsItem(YUMMY_IDS + id, title, post["content_preview"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                Instant.fromEpochSeconds(at).toString(), kindOf(title),
                url = "https://ru.yummyani.me/posts/$id", source = "YummyAnime")
        }
    }

    /** GoHa.ru: the anime section's RSS, its first picture and the lead paragraph. */
    private suspend fun goha(): List<NewsItem> {
        val text = http.get("https://www.goha.ru/rss/anime") { header("Accept", "application/rss+xml") }.bodyAsText()
        return Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(text).mapNotNull { match ->
            val item = match.groupValues[1]
            fun tag(name: String) = Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)
                ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim()
            val title = tag("title")?.let(::unescape)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val link = tag("link") ?: return@mapNotNull null
            val description = tag("description").orEmpty()
            val image = Regex("""<img[^>]+src="([^"]+)"""").find(description)?.groupValues?.get(1)
            val lead = RichTextParser.parseHtml(description.replace(Regex("""<a [^>]*>Читать дальше[^<]*</a>"""), "")).plain
            val at = tag("pubDate")?.let(::rfc822) ?: return@mapNotNull null
            NewsItem(GOHA_IDS + (link.hashCode().toLong() and 0x7fffffff), title, lead, at, kindOf(title), imageUrl = image, url = link, source = "GoHa.ru")
        }.toList()
    }

    private suspend fun safe(block: suspend () -> List<NewsItem>): List<NewsItem> = try { block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { emptyList() }

    private companion object {
        const val YUMMY_IDS = 1_000_000_000_000L
        const val GOHA_IDS = 2_000_000_000_000L
    }
}

/** «Анонс», «Трейлер», «Премьера» or «Новость» by the words of the headline. */
fun kindOf(title: String): String {
    val lower = title.lowercase()
    return when {
        listOf("анонс", "адаптац", "экранизац", "получит аниме", "новый сезон").any { it in lower } -> "Анонс"
        listOf("трейлер", "тизер", "ролик", "pv").any { it in lower } -> "Трейлер"
        listOf("дата", "премьер", "выход", "сезон", "старт", "вернется", "вернётся").any { it in lower } -> "Премьера"
        else -> "Новость"
    }
}

/**
 * One story per subject: two headlines that name the same «title» in quotes within two days are the same
 * news. The copy with a picture (or the longer text) is kept, newest first overall.
 */
internal fun merge(items: List<NewsItem>): List<NewsItem> {
    fun subject(item: NewsItem) = Regex("«([^»]{3,})»").find(item.title)?.groupValues?.get(1)?.lowercase()?.replace(Regex("[^\\p{L}\\p{N}]"), "")
    val kept = mutableListOf<NewsItem>()
    for (item in items.sortedWith(compareByDescending<NewsItem> { it.imageUrl != null }.thenByDescending { it.text.length })) {
        val key = subject(item)
        val day = millis(item.createdAt)
        val twin = key != null && kept.any { other ->
            subject(other) == key && kindOf(other.title) == item.kind && day != null && millis(other.createdAt)?.let { kotlin.math.abs(it - day) < 2 * 86_400_000L } == true
        }
        if (!twin) kept += item
    }
    return kept.sortedByDescending { millis(it.createdAt) ?: 0 }
}

internal fun millis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()

private val Months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** «Mon, 02 Nov 26 15:02:15 +0000» (two- or four-digit year) as ISO time. */
internal fun rfc822(value: String): String? {
    val match = Regex("""(\d{1,2}) (\w{3}) (\d{2,4}) (\d{2}):(\d{2}):(\d{2}) ([+-]\d{4})""").find(value) ?: return null
    val (day, month, year, hour, minute, second, zone) = match.destructured
    val m = Months.indexOf(month) + 1
    if (m == 0) return null
    val fullYear = if (year.length == 2) 2000 + year.toInt() else year.toInt()
    fun two(n: Int) = n.toString().padStart(2, '0')
    return runCatching { Instant.parse("$fullYear-${two(m)}-${two(day.toInt())}T$hour:$minute:$second${zone.take(3)}:${zone.takeLast(2)}").toString() }.getOrNull()
}

private fun unescape(text: String) = text.replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#039;", "'")
