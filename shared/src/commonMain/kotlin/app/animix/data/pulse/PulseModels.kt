package app.animix.data.pulse

import app.animix.core.nowMillis
import app.animix.data.library.LibraryData
import app.animix.data.library.LibraryEntry
import app.animix.data.library.ListStatus
import app.animix.model.Anime
import app.animix.model.AnimeTopic
import kotlinx.serialization.Serializable
import kotlin.time.Instant

private const val DayMs = 86_400_000L

@Serializable
data class PulseWindow(val id: Long, val from: Long, val until: Long)

fun pulseWindow(now: Long = nowMillis()): PulseWindow {
    val day = now / DayMs
    return PulseWindow(day - (day + 3) % 7, now - 7 * DayMs, now)
}

fun pulseDate(time: Long): String = Instant.fromEpochMilliseconds(time).toString().take(10)
fun pulseDateLabel(time: Long): String = pulseDate(time).split('-').let { "${it[2]}.${it[1]}.${it[0]}" }

/** No profile identity, reviews, scores, tokens, local paths or individual viewing timestamps. */
fun pulseInterests(library: LibraryData): List<LibraryEntry> {
    val eligible = library.entries.values.filter { it.anime.title.isNotBlank() && it.status != ListStatus.Dropped &&
        (it.status != null || it.favorite || it.episodesWatched > 0 || it.viewing.isNotEmpty()) }
    val ranked = eligible.sortedWith(compareBy<LibraryEntry> {
        when { it.status == ListStatus.Watching -> 0; it.favorite -> 1; it.status == ListStatus.Planned -> 2
            it.status == ListStatus.Completed -> 3; else -> 4 }
    }.thenByDescending { it.updatedAt })
    val past = ranked.firstOrNull { it.status == ListStatus.Completed }
    return (ranked.take(3) + listOfNotNull(past) + ranked.drop(3)).distinctBy { it.anime.key }.take(4)
}

/** [weekEpisodes]: episodes Shikimori dated inside the issue's window, the only proof of a weekly increase. */
@Serializable
data class PulseTitle(val anime: Anime, val list: String, val watched: Int, val fresh: Boolean = true,
    val nextEpisodeAt: String? = null, val previousAired: Int? = null, val studios: List<String> = emptyList(),
    val weekEpisodes: List<Int> = emptyList())

/** [date]: publication day (UTC) known from the source itself, not from a model. */
@Serializable
data class PulseSource(val id: String, val title: String, val url: String,
    val excerpt: String = "", val animeKeys: List<String> = emptyList(), val catalog: Boolean = false,
    val date: String? = null)

@Serializable
data class PulseArticle(val category: String, val title: String, val text: String,
    val animeKeys: List<String>, val sourceIds: List<String>, val date: String? = null, val quote: String = "")

@Serializable
data class PulseEditorial(val headline: String, val intro: String, val articles: List<PulseArticle>)

data class PulseEdited(val editorial: PulseEditorial, val sources: List<PulseSource>, val costRub: Double? = null)

/** [aiEdited] false: built from Shikimori alone, [note] says why; such an issue can be rebuilt the same week. */
@Serializable
data class PulseIssue(val window: PulseWindow, val createdAt: Long, val titles: List<PulseTitle>,
    val editorial: PulseEditorial, val sources: List<PulseSource>, val generationCostRub: Double? = null,
    val aiEdited: Boolean = true, val note: String? = null)

@Serializable
data class PulseArchive(val issues: List<PulseIssue> = emptyList())

data class PulseState(val loaded: Boolean = false, val issues: List<PulseIssue> = emptyList(),
    val busy: Boolean = false, val stage: String = "", val error: String? = null)

open class PulseException(message: String) : Exception(message)

/** No AI access on this device. Issues still come from Shikimori. */
class PulseNotConnected : PulseException("ИИ-обзор на этом устройстве не подключён")

/** Only explicit source dates; a model-supplied date is never proof of freshness. */
fun pulseSourceDates(text: String): Set<String> = buildSet {
    Regex("(20\\d{2})[-/年](\\d{1,2})[-/月](\\d{1,2})").findAll(text).forEach { match ->
        add("${match.groupValues[1]}-${match.groupValues[2].padStart(2, '0')}-${match.groupValues[3].padStart(2, '0')}")
    }
    val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    Regex("(?i)\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+(\\d{1,2}),?\\s+(20\\d{2})").findAll(text).forEach { match ->
        val month = months.indexOf(match.groupValues[1].lowercase().take(3)) + 1
        add("${match.groupValues[3]}-${month.toString().padStart(2, '0')}-${match.groupValues[2].padStart(2, '0')}")
    }
}

/** [evidence]: dated Shikimori news the editor may retell next to what its own search finds. */
fun interface PulseEditor {
    suspend fun edit(titles: List<PulseTitle>, window: PulseWindow, evidence: List<PulseSource>, stage: (String) -> Unit): PulseEdited
}

/** Ignore model-invented references, off-topic entries and news from outside this issue's window. */
fun validatePulse(editorial: PulseEditorial, sources: List<PulseSource>, titles: List<PulseTitle>, window: PulseWindow): PulseEditorial {
    val sourceIds = sources.map { it.id }.toSet()
    val keys = titles.map { it.anime.key }.toSet()
    val articles = editorial.articles.take(16).mapNotNull { item ->
        if (item.category !in setOf("news", "fact", "next") || item.title.isBlank() || item.text.isBlank()) return@mapNotNull null
        if (item.animeKeys.isEmpty() || item.animeKeys.any { it !in keys } || item.sourceIds.isEmpty() || item.sourceIds.any { it !in sourceIds }) return@mapNotNull null
        val quote = item.quote.replace(Regex("\\s+"), " ").trim()
        if (quote.length !in 25..600 || item.sourceIds.none { id -> sources.any { source ->
                source.id == id && source.animeKeys.containsAll(item.animeKeys) &&
                    source.excerpt.replace(Regex("\\s+"), " ").contains(quote)
            } }) return@mapNotNull null
        if (item.category != "fact") {
            val date = item.date ?: return@mapNotNull null
            if (runCatching { Instant.parse("${date}T00:00:00Z") }.isFailure) return@mapNotNull null
            val low = if (item.category == "next") pulseDate(window.until) else pulseDate(window.from)
            val high = if (item.category == "next") pulseDate(window.until + 7 * DayMs) else pulseDate(window.until)
            if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) || date !in low..high) return@mapNotNull null
            if (item.sourceIds.none { id -> sources.any { source -> source.id == id && (item.category == "news" && source.date == date ||
                    date in pulseSourceDates(if (item.category == "next") source.excerpt else source.url + " " + source.excerpt)) } }) return@mapNotNull null
        }
        // Catalog cards only repeat the title page; they are not material for an article.
        if (sources.filter { it.id in item.sourceIds }.all { it.catalog }) return@mapNotNull null
        item.copy(title = item.title.take(180), text = item.text.take(2200), sourceIds = item.sourceIds.distinct().take(4))
    }
    return editorial.copy(headline = editorial.headline.take(150), intro = editorial.intro.take(1600), articles = articles)
}

/** One Shikimori topic and the title in the user's lists it is about (itself, or a related title). */
data class PulseTopic(val interest: String, val anime: Anime, val topic: AnimeTopic)

/**
 * The part of an issue that needs no AI: Shikimori events as short articles, weekly episodes per title,
 * written news as [news] evidence for the editor and as plain [newsArticles] when the editor is unavailable.
 */
data class PulseDigest(val titles: List<PulseTitle>, val events: List<PulseArticle>, val newsArticles: List<PulseArticle>,
    val sources: List<PulseSource>, val news: List<PulseSource>)

fun pulseTopicTime(createdAt: String): Long? = runCatching { Instant.parse(createdAt).toEpochMilliseconds() }.getOrNull()

private fun quoted(anime: Anime) = "«${anime.title}»"

private fun episodeRange(episodes: List<Int>): String = episodes.sorted().let { list ->
    if (list.size == 1) "${list[0]} серия" else if (list.last() - list.first() == list.size - 1) "серии ${list.first()}–${list.last()}"
    else "серии ${list.joinToString()}"
}

/** First paragraphs of a news text up to [limit] characters, cut on a sentence where possible. */
fun pulseLead(text: String, limit: Int = 420): String {
    val clean = text.replace("[спойлер]", "").replace(Regex("[ \t]+"), " ").replace(Regex("\n{2,}"), "\n\n").trim()
    if (clean.length <= limit) return clean
    val cut = clean.take(limit)
    val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "), cut.lastIndexOf('\n'))
    return if (end > limit / 3) cut.take(end + 1).trim() else cut.trimEnd() + "…"
}

fun pulseDigest(titles: List<PulseTitle>, topics: List<PulseTopic>, window: PulseWindow): PulseDigest {
    val byKey = titles.associateBy { it.anime.key }
    val inWindow = topics.filter { it.interest in byKey }.mapNotNull { item ->
        pulseTopicTime(item.topic.createdAt)?.takeIf { it in window.from..window.until }?.let { item to it }
    }.distinctBy { it.first.topic.id }.sortedByDescending { it.second }
    val sources = mutableListOf<PulseSource>()
    fun source(interest: String, title: String, url: String, excerpt: String, time: Long) =
        PulseSource("K${sources.size + 1}", title, url, excerpt, listOf(interest), date = pulseDate(time)).also { sources += it }

    val events = mutableListOf<PulseArticle>()
    val episodes = inWindow.filter { it.first.topic.event == "episode" && it.first.topic.episode != null }
        .groupBy { it.first.interest to it.first.anime.key }
    // Own episodes go to the title row; episodes of a related title the user has not added become one note.
    val weekly = episodes.filterKeys { (interest, key) -> interest == key }
        .map { (key, list) -> key.first to list.mapNotNull { it.first.topic.episode }.distinct() }.toMap()
    episodes.filterKeys { (interest, key) -> interest != key }.forEach { (key, list) ->
        val anime = list.first().first.anime
        val numbers = list.mapNotNull { it.first.topic.episode }.distinct()
        val time = list.maxOf { it.second }
        val text = "Вышла ${episodeRange(numbers)} ${quoted(anime)}. Этот тайтл связан с ${quoted(byKey.getValue(key.first).anime)} из ваших списков."
        val ref = source(key.first, "Shikimori: ${anime.title}", "https://shikimori.io/animes/${anime.id}", text, time)
        events += PulseArticle("news", "${anime.title}: ${episodeRange(numbers)}", text, listOf(key.first), listOf(ref.id), ref.date, text)
    }
    inWindow.filter { it.first.topic.event in setOf("anons", "ongoing", "released") }
        .distinctBy { it.first.anime.key to it.first.topic.event }.forEach { (item, time) ->
            val own = item.anime.key == item.interest
            val anime = item.anime
            val link = if (own) "" else " Он связан с ${quoted(byKey.getValue(item.interest).anime)} из ваших списков."
            val (title, text) = when (item.topic.event) {
                "anons" -> "Анонс: ${anime.title}" to "На Shikimori появился анонс ${quoted(anime)} (${anime.kind.label.let { if (it.any(Char::isLowerCase)) it.lowercase() else it }}).$link"
                "ongoing" -> "Начался показ: ${anime.title}" to "Shikimori отметил старт показа ${quoted(anime)}.$link"
                else -> "Показ завершён: ${anime.title}" to ("${quoted(anime)} вышел полностью" +
                    (anime.episodes.takeIf { it > 1 }?.let { " — все $it серий доступны." } ?: ".") + link)
            }
            val ref = source(item.interest, "Shikimori: ${anime.title}", "https://shikimori.io/animes/${anime.id}", text, time)
            events += PulseArticle("news", title, text, listOf(item.interest), listOf(ref.id), ref.date, text)
        }

    val newsArticles = mutableListOf<PulseArticle>()
    val news = inWindow.filter { it.first.topic.event == null && it.first.topic.title.isNotBlank() && it.first.topic.text.length >= 40 }
        .take(8).map { (item, time) ->
            val ref = source(item.interest, item.topic.title.take(240), "https://shikimori.io/forum/news/${item.topic.id}",
                "${item.topic.title}\n${item.topic.text}".take(2400), time)
            newsArticles += PulseArticle("news", item.topic.title.take(180), pulseLead(item.topic.text), listOf(item.interest),
                listOf(ref.id), ref.date, item.topic.title)
            ref
        }
    return PulseDigest(titles.map { title -> weekly[title.anime.key]?.let { title.copy(weekEpisodes = it.sorted()) } ?: title },
        events, newsArticles, sources, news)
}

/** Headline and intro for an issue the editor did not write. */
fun pulsePlainEditorial(titles: List<PulseTitle>, articles: List<PulseArticle>): PulseEditorial {
    val episodes = titles.sumOf { it.weekEpisodes.size }
    val parts = listOfNotNull(episodes.takeIf { it > 0 }?.let { "новых серий: $it" },
        articles.count { it.category == "news" }.takeIf { it > 0 }?.let { "новостей и событий: $it" })
    val intro = if (parts.isEmpty()) "Состояние тайтлов из ваших списков на эту неделю."
        else "За неделю по вашим спискам — ${parts.joinToString(", ")}. Всё со ссылками на Shikimori."
    return PulseEditorial("Неделя ваших тайтлов", intro, articles)
}
