package app.animix.model

import app.animix.core.RichText
import kotlinx.serialization.Serializable

@Serializable
enum class AnimeKind(val label: String) {
    Tv("Сериал"), Movie("Фильм"), Ova("OVA"), Ona("ONA"), Special("Спешл"), Other("Другое");

    companion object {
        fun from(raw: String?): AnimeKind = when (raw?.lowercase()) {
            "tv", "tv_13", "tv_24", "tv_48" -> Tv
            "movie" -> Movie
            "ova" -> Ova
            "ona" -> Ona
            "special", "tv_special" -> Special
            else -> Other
        }
    }
}

@Serializable
enum class AnimeStatus(val label: String) {
    Announced("Анонс"), Ongoing("Онгоинг"), Released("Вышел"), Unknown("");

    companion object {
        fun from(raw: String?): AnimeStatus = when (raw?.lowercase()) {
            "anons" -> Announced
            "ongoing" -> Ongoing
            "released" -> Released
            else -> Unknown
        }
    }
}

/** Where a catalog entry comes from. Shikimori is the base; YummyAnime fills in what Shikimori lacks. */
@Serializable
enum class AnimeSource { Shikimori, Yummy }

/** A catalog entry, as shown in lists and grids. [id] is the id within its [source]. */
@Serializable
data class Anime(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val englishTitle: String?,
    val posterUrl: String?,
    val score: Double?,
    val kind: AnimeKind,
    val status: AnimeStatus,
    val episodes: Int,
    val episodesAired: Int,
    val year: Int?,
    val genres: List<String>,
    val source: AnimeSource = AnimeSource.Shikimori,
) {
    /** Unique across sources; use for list keys and storage. */
    val key: String get() = if (source == AnimeSource.Yummy) "y$id" else "s$id"

    companion object {
        /** An entry known only by id, filled in once its details load. */
        fun placeholder(id: Int, source: AnimeSource = AnimeSource.Shikimori) = Anime(
            id = id, title = "", originalTitle = "", englishTitle = null, posterUrl = null, score = null,
            kind = AnimeKind.Other, status = AnimeStatus.Unknown, episodes = 0, episodesAired = 0, year = null, genres = emptyList(), source = source,
        )
    }

    /** Episodes that can be watched right now. */
    val availableEpisodes: Int
        get() = when {
            status == AnimeStatus.Ongoing && episodesAired > 0 -> episodesAired
            episodes > 0 -> episodes
            else -> episodesAired
        }
}

@Serializable
data class AnimeDetail(
    val anime: Anime,
    val description: RichText,
    val durationMinutes: Int?,
    val rating: String?,
    val studios: List<String>,
    val screenshots: List<String>,
    val nextEpisodeAt: String?,
    /** Related titles in AniMix's watch order (see TitleLinks). */
    val related: List<Anime>,
    /** Shikimori discussion topic of this anime. */
    val topicId: Int? = null,
    val commentsCount: Int = 0,
    /** Shikimori's relation of each related title («Продолжение», «Предыстория»), by [Anime.key]. */
    val relatedLabels: Map<String, String> = emptyMap(),
    /** The studios with their Shikimori ids, for the page of everything a studio made. */
    val studioLinks: List<Studio> = emptyList(),
    /** The promo video the catalog lists for this title; it opens where the video is hosted. */
    val trailer: Trailer? = null,
)

@Serializable
data class Trailer(val url: String, val imageUrl: String? = null)

@Serializable
data class Studio(val id: Int, val name: String, val imageUrl: String? = null)

/** One item of the week's news in AniPulse: an announcement, trailer, premiere date or other news. */
@Serializable
data class NewsItem(
    val id: Long,
    val title: String,
    val text: String,
    val createdAt: String,
    /** «Анонс», «Трейлер», «Премьера» or «Новость». */
    val kind: String,
    val anime: Anime? = null,
    val imageUrl: String? = null,
    val url: String,
    /** «Shikimori», «YummyAnime» or «GoHa.ru». */
    val source: String = "Shikimori",
)

@Serializable
data class Genre(val id: String, val title: String, val isDemographic: Boolean)

/** A Shikimori news topic or generated event of one anime. [event]: anons, ongoing, released, episode; null for written news. */
@Serializable
data class AnimeTopic(
    val id: Long,
    val title: String,
    val text: String,
    val createdAt: String,
    val event: String? = null,
    val episode: Int? = null,
)
