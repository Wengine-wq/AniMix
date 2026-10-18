package app.animix.data.importer

import app.animix.core.AppJson
import app.animix.core.DiskStore
import app.animix.data.library.ImportedTitle
import app.animix.data.library.ListStatus
import app.animix.data.shikimori.AnimeDto
import app.animix.data.shikimori.ShikimoriApi
import app.animix.data.shikimori.toAnime
import app.animix.data.yummy.TitleMatcher
import app.animix.data.yummy.YummyRepository
import app.animix.model.Anime
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class AnixartProfile(val id: Long, val login: String, val avatar: String? = null)

/** Anixart list ids (as in its apps and the open-source AniAnglia client) and the AniMix status. */
enum class AnixartList(val id: Int, val status: ListStatus) {
    Watching(1, ListStatus.Watching),
    Planned(2, ListStatus.Planned),
    Completed(3, ListStatus.Completed),
    OnHold(4, ListStatus.OnHold),
    Dropped(5, ListStatus.Dropped),
}

/** What Anixart tells about a release; enough to find the same title elsewhere. */
data class AnixartRelease(
    val id: Long,
    val list: AnixartList,
    val titleRu: String,
    val titleOriginal: String,
    val titleAlt: List<String>,
    val year: Int?,
    /** First air date, when Anixart knows it ("c 07.04.2010 по …"). */
    val startDate: String?,
    val episodesTotal: Int?,
    val category: String,
    val studio: String,
    val lastViewEpisode: Int?,
) {
    val displayTitle: String get() = titleRu.ifBlank { titleOriginal }
    val episodesWatched: Int get() = if (list == AnixartList.Completed) episodesTotal ?: lastViewEpisode ?: 0 else lastViewEpisode ?: 0
    val names: List<String> get() = (listOf(titleOriginal, titleRu) + titleAlt).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

class AnixartUnavailable : Exception("Anixart не отвечает. Попробуйте позже.")

/**
 * Public Anixart lists, read by nickname: no Anixart password ever passes through AniMix.
 * Lists hidden by the owner's privacy settings are not visible.
 */
class AnixartClient(private val http: HttpClient) {
    private var working: String? = null

    private suspend fun request(path: String, body: String? = null): JsonObject {
        for (base in listOfNotNull(working) + BASES.filter { it != working }) {
            try {
                val response = if (body == null) http.get("$base/$path") { expectSuccess = false; header(HttpHeaders.UserAgent, AGENT) }
                else http.post("$base/$path") { expectSuccess = false; header(HttpHeaders.UserAgent, AGENT); contentType(ContentType.Application.Json); setBody(body) }
                if (!response.status.isSuccess()) continue
                val json = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
                working = base
                return json
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
            }
        }
        throw AnixartUnavailable()
    }

    suspend fun searchProfiles(nickname: String): List<AnixartProfile> {
        val json = request("search/profiles/0", JsonObject(mapOf("query" to JsonPrimitive(nickname.trim()))).toString())
        return json["content"]?.jsonArray.orEmpty().mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
            AnixartProfile(id, obj["login"]?.jsonPrimitive?.contentOrNull.orEmpty(), obj["avatar"]?.jsonPrimitive?.contentOrNull)
        }
    }

    suspend fun counts(profile: Long): Map<AnixartList, Int> = AnixartList.entries.associateWith { list ->
        request("profile/list/all/$profile/${list.id}/0")["total_count"]?.jsonPrimitive?.intOrNull ?: 0
    }

    suspend fun releases(profile: Long, list: AnixartList): List<AnixartRelease> {
        val result = mutableListOf<AnixartRelease>()
        for (page in 0 until 200) {
            val json = request("profile/list/all/$profile/${list.id}/$page")
            val content = json["content"]?.jsonArray.orEmpty()
            content.forEach { item -> parse(item.jsonObject, list)?.let(result::add) }
            val pages = json["total_page_count"]?.jsonPrimitive?.intOrNull ?: 0
            if (content.isEmpty() || page + 1 >= pages) break
        }
        return result
    }

    private fun parse(obj: JsonObject, list: AnixartList): AnixartRelease? {
        fun text(key: String) = obj[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.trim().orEmpty()
        val id = text("id").toLongOrNull() ?: return null
        val date = Regex("""(\d{2})\.(\d{2})\.(\d{4})""").find(text("release_date"))?.destructured?.let { (d, m, y) -> "$y-$m-$d" }
        return AnixartRelease(
            id = id,
            list = list,
            titleRu = text("title_ru"),
            titleOriginal = text("title_original"),
            titleAlt = text("title_alt").split(Regex("""\s*[/;|]\s*|\n""")).filter { it.isNotBlank() },
            year = text("year").toIntOrNull(),
            startDate = date,
            episodesTotal = text("episodes_total").toIntOrNull()?.takeIf { it > 0 },
            category = (obj["category"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            studio = text("studio"),
            lastViewEpisode = text("last_view_episode").toIntOrNull(),
        )
    }

    private companion object {
        // Mirrors the Anixart apps use; the first one that answers is remembered.
        val BASES = listOf("https://api.anixart.tv", "https://api-s.anixsekai.com", "https://api-alt.anixart.app")
        const val AGENT = "AnixartApp/9.0 (AniMix import)"
    }
}

/** How sure a match is, for the result screen. */
data class MatchedRelease(val release: AnixartRelease, val anime: Anime?)

/**
 * Finds the Shikimori (or YummyAnime-only) title for each Anixart release.
 *
 * Earlier versions demanded an exact name, so renamed seasons, punctuation and
 * transliteration differences were lost. Now every name Anixart has is compared with every
 * name Shikimori has (Russian, romaji, English, Japanese, synonyms) by word overlap, and the
 * first air date, format, episode count and studio confirm or reject the candidate.
 * Searches go in batches of aliased GraphQL queries to stay within Shikimori's limits.
 */
class AnixartMatcher(http: HttpClient, private val yummy: YummyRepository, private val store: DiskStore) {
    private val shikimori = ShikimoriApi(http)
    private val lock = Mutex()
    private var cache: MutableMap<String, Anime>? = null

    private suspend fun remembered(): MutableMap<String, Anime> = lock.withLock {
        cache ?: (store.read(FILE)?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() }?.toMutableMap() ?: mutableMapOf())
            .also { cache = it }
    }

    suspend fun match(releases: List<AnixartRelease>, onProgress: (done: Int, total: Int) -> Unit): List<MatchedRelease> {
        val known = remembered()
        val found = HashMap<Long, Anime>()
        releases.forEach { release -> known[release.id.toString()]?.let { found[release.id] = it } }
        var pending = releases.filter { it.id !in found && !isLiveAction(it) }
        onProgress(found.size, releases.size)

        // Pass 1: original titles. Pass 2: Russian and alternative names, shortened forms.
        for (pass in 0..1) {
            if (pending.isEmpty()) break
            val requests = pending.flatMap { release -> queriesFor(release, pass).map { release to it } }
            val candidates = HashMap<Long, MutableList<AnimeDto>>()
            for (chunk in requests.chunked(BATCH)) {
                val results = retrying { shikimori.searchMany(chunk.map { it.second }) }
                chunk.zip(results).forEach { (pair, list) -> candidates.getOrPut(pair.first.id) { mutableListOf() } += list }
                pending.forEach { release -> best(release, candidates[release.id].orEmpty())?.let { found.putIfAbsent(release.id, it) } }
                onProgress(found.size, releases.size)
                delay(350)
            }
            pending = pending.filter { it.id !in found }
        }

        // Pass 3: titles only YummyAnime has (no Shikimori entry at all).
        for (release in pending) {
            val hit = runCatching { yummy.searchOnlyOnYummy(release.titleRu.ifBlank { release.titleOriginal }) }.getOrDefault(emptyList())
                .firstOrNull { candidate ->
                    TitleMatcher.best(release.names, listOf(candidate.title, candidate.originalTitle)) >= 90 &&
                        (release.year == null || candidate.year == null || candidate.year == release.year)
                }
            if (hit != null) found[release.id] = hit
            onProgress(found.size, releases.size)
        }

        lock.withLock {
            releases.forEach { release -> found[release.id]?.let { known[release.id.toString()] = it } }
            store.write(FILE, AppJson.encodeToString(serializer, known))
        }
        return releases.map { MatchedRelease(it, found[it.id]) }
    }

    fun toImported(matches: List<MatchedRelease>): List<ImportedTitle> = matches.mapNotNull { match ->
        val anime = match.anime ?: return@mapNotNull null
        ImportedTitle(anime, match.release.list.status, match.release.episodesWatched)
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        repeat(3) { attempt ->
            try { return block() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { delay(1_500L * (attempt + 1)) }
        }
        return block()
    }

    private fun best(release: AnixartRelease, candidates: List<AnimeDto>): Anime? =
        candidates.distinctBy { it.id }
            .map { it to score(release, it) }
            .filter { (_, score) -> score >= ACCEPT }
            .maxByOrNull { it.second }
            ?.first?.toAnime()

    internal companion object {
        const val FILE = "anixart-matches.json"
        // Shikimori allows a query complexity of 190; one search costs about 29.
        const val BATCH = 6
        const val ACCEPT = 70
        val serializer = MapSerializer(String.serializer(), Anime.serializer())

        /**
         * Pass 0 searches the original title only (one request slot per release keeps a long list
         * within Shikimori's 90 requests a minute); pass 1 tries everything else for what is left.
         */
        fun queriesFor(release: AnixartRelease, pass: Int): List<String> = when (pass) {
            0 -> listOf(release.titleOriginal.ifBlank { release.titleRu })
            else -> listOf(release.titleRu) + release.titleAlt +
                listOf(release.titleOriginal, release.titleRu).flatMap { listOf(withoutMarkers(it), shorten(it)) }
        }.map { it.trim() }.filter { it.length >= 2 }.distinct()

        private val markers = Regex("""\b(\d+(st|nd|rd|th)\s+season|season\s+\d+|tv-?\d+|ova-?\d*|movie|gekijouban|фильм)\b""", RegexOption.IGNORE_CASE)

        /** «Title: Arc TV-1» → «Title: Arc»: drops season and format markers, keeps the subtitle. */
        fun withoutMarkers(title: String): String = title.replace(markers, " ").replace(Regex("""\s+"""), " ").trim()

        /** «Title: Subtitle», «Title (TV-2)» and «Title 2nd Season» are searched by their main part too. */
        fun shorten(title: String): String = withoutMarkers(title.substringBefore(':').substringBefore(" - ").substringBefore('('))

        /** Live-action dramas share names with anime but never have a Shikimori anime entry. */
        fun isLiveAction(release: AnixartRelease) = release.category.lowercase().contains("дорам")

        private val kinds = mapOf(
            "сериал" to setOf("tv", "tv_13", "tv_24", "tv_48"),
            "фильм" to setOf("movie"),
            "ova" to setOf("ova"),
            "ona" to setOf("ona"),
            "спешл" to setOf("special", "tv_special"),
            "клип" to setOf("music", "pv", "cm"),
        )

        /**
         * 0..~170. A name match alone reaches 70 only when it is strong; the first air date,
         * format, episode count and studio lift or sink weaker ones.
         */
        fun score(release: AnixartRelease, anime: AnimeDto): Int {
            val names = listOfNotNull(anime.name, anime.russian, anime.english, anime.japanese) + anime.synonyms.orEmpty()
            val targets = names.filter { it.isNotBlank() }
            // «TV-1» or «OVA» in the Anixart name must not sink a match; a stripped name counts a little less.
            val name = maxOf(TitleMatcher.best(release.names, targets), TitleMatcher.best(release.names.map(::withoutMarkers), targets) - 15)
            if (name < 35) return 0
            var score = name
            val date = anime.airedOn?.date
            val year = anime.airedOn?.year
            when {
                release.startDate != null && date != null && release.startDate == date -> score += 45
                release.year != null && year != null && release.year == year -> score += 15
                release.year != null && year != null && kotlin.math.abs(release.year - year) == 1 -> score -= 10
                // A title of the same name years apart is another title (a remake, a sequel), never this one.
                release.year != null && year != null -> return 0
            }
            val expected = kinds.entries.firstOrNull { release.category.lowercase().contains(it.key) }?.value
            if (expected != null && anime.kind != null) score += if (anime.kind in expected) 8 else if (expected.first() == "tv" && anime.kind == "ona") -2 else -10
            val episodes = release.episodesTotal
            if (episodes != null && anime.episodes > 0) score += if (episodes == anime.episodes) 10 else -6
            val studio = release.studio.lowercase()
            if (studio.isNotBlank() && anime.studios.orEmpty().any { it.name.isNotBlank() && studio.contains(it.name.lowercase()) }) score += 8
            return score
        }
    }
}
