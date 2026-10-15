package app.animix.model

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
    val description: String,
    val durationMinutes: Int?,
    val rating: String?,
    val studios: List<String>,
    val screenshots: List<String>,
    val nextEpisodeAt: String?,
    val related: List<Anime>,
    /** Shikimori discussion topic of this anime. */
    val topicId: Int? = null,
    val commentsCount: Int = 0,
)

@Serializable
data class Genre(val id: String, val title: String, val isDemographic: Boolean)
