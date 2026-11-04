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

    suspend fun week(): List<NewsItem> = cache.get("news:week:v2", ttl = 30.minutes, ListSerializer(NewsItem.serializer())) {
        coroutineScope {
            val sources = listOf(
                async { safe { shikimori.weeklyNews() } },
                async { safe { yummy() } },
                async { safe { goha() } },
            ).awaitAll()
            val cutoff = kotlin.time.Clock.System.now().toEpochMilliseconds() - 8L * 86_400_000
            merge(sources.flatten().filter { millis(it.createdAt)?.let { at -> at >= cutoff } ?: true }
                .filter { it.anime != null || isAnimeNews(it.title, it.text) })
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
            val lead = cleanLead(post["content_preview"]?.jsonPrimitive?.contentOrNull.orEmpty())
            NewsItem(YUMMY_IDS + id, unescape(title), lead, Instant.fromEpochSeconds(at).toString(), kindOf(title, lead),
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
            val lead = cleanLead(unescape(description).replace(Regex("""<a [^>]*>Читать дальше[^<]*</a>"""), ""))
            val at = tag("pubDate")?.let(::rfc822) ?: return@mapNotNull null
            NewsItem(GOHA_IDS + (link.hashCode().toLong() and 0x7fffffff), title, lead, at, kindOf(title, lead), imageUrl = image, url = link, source = "GoHa.ru")
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

/**
 * «Анонс», «Трейлер», «Премьера» or «Новость». The headline decides; a video named only in the lead
 * still makes it a trailer. A plain «второго сезона» is no premiere: only a date, a start or a return is.
 */
fun kindOf(title: String, text: String = ""): String {
    val lower = title.lowercase().replace('ё', 'е')
    val lead = text.lowercase().take(240)
    val trailer = listOf("трейлер", "тизер", "ролик", "промо-видео", "промовидео", " pv")
    return when {
        trailer.any { it in lower } -> "Трейлер"
        listOf("дата выхода", "дату выхода", "премьер", "выйдет", "стартует", "старт ", "вернется", "выход ").any { it in lower } -> "Премьера"
        listOf("анонс", "адаптац", "экранизац", "получит аниме", "получит продолжение", "продлил", "новый сезон", "продолжение").any { it in lower } -> "Анонс"
        trailer.any { it in lead } -> "Трейлер"
        else -> "Новость"
    }
}

/**
 * News of a manga, a novel or a game that does not say a word about an anime is not for an anime week.
 */
fun isAnimeNews(title: String, text: String): Boolean {
    val all = (title + " " + text.take(400)).lowercase().replace('ё', 'е')
    val print = listOf("манг", "ранобэ", "ранобе", "новелл", "вебтун", "комикс", "игр").any { it in title.lowercase() }
    val screen = listOf("аниме", "экраниз", "адаптац", "сериал", "фильм", "сезон", "серия", "серии", "трейлер", "студи").any { it in all }
    return !print || screen
}

/** Previews arrive with tags and entities; the digest shows clean sentences. */
fun cleanLead(raw: String): String =
    RichTextParser.parseHtml(raw.replace(Regex("<(br|/p|/div|/li|/h\\d|/blockquote)[^>]*>", RegexOption.IGNORE_CASE), " ")).plain.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

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
            subject(other) == key && other.kind == item.kind && day != null && millis(other.createdAt)?.let { kotlin.math.abs(it - day) < 2 * 86_400_000L } == true
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
