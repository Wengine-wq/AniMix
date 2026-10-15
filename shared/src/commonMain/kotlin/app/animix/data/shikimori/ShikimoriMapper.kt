package app.animix.data.shikimori

import app.animix.model.Anime
import app.animix.model.AnimeDetail
import app.animix.model.AnimeKind
import app.animix.model.AnimeStatus
import app.animix.model.Genre

internal fun AnimeDto.toAnime(): Anime = Anime(
    id = id.toIntOrNull() ?: 0,
    title = russian?.takeIf { it.isNotBlank() } ?: name,
    originalTitle = name,
    englishTitle = english?.takeIf { it.isNotBlank() },
    posterUrl = poster?.main2xUrl ?: poster?.mainUrl ?: poster?.originalUrl,
    score = score?.takeIf { it > 0 },
    kind = AnimeKind.from(kind),
    status = AnimeStatus.from(status),
    episodes = episodes,
    episodesAired = episodesAired,
    year = airedOn?.year,
    genres = genres.orEmpty()
        .filter { it.kind == "genre" || it.kind == "theme" }
        .mapNotNull { it.russian?.takeIf(String::isNotBlank) },
)

internal fun AnimeDto.toDetail(): AnimeDetail = AnimeDetail(
    anime = toAnime(),
    description = description?.let(::stripBbCode).orEmpty(),
    durationMinutes = duration?.takeIf { it > 0 },
    rating = rating?.takeIf { it != "none" }?.let(::ratingLabel),
    studios = studios.orEmpty().map { it.name },
    screenshots = screenshots.orEmpty().mapNotNull { it.originalUrl ?: it.x332Url },
    nextEpisodeAt = nextEpisodeAt,
    related = related.orEmpty().mapNotNull { it.anime?.toAnime() }.filter { it.id > 0 }.distinctBy { it.id },
)

internal fun GenreDto.toGenre(): Genre? {
    val label = russian?.takeIf(String::isNotBlank) ?: return null
    return Genre(id = id, title = label, isDemographic = kind == "demographic")
}

private val bbTag = Regex("""\[/?[a-z_]+(?:=[^\]]*)?]""", RegexOption.IGNORE_CASE)

/** Shikimori descriptions link characters with BBCode; keep only the text. */
internal fun stripBbCode(text: String): String =
    text.replace(bbTag, "")
        .replace(Regex("[ \t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

private fun ratingLabel(raw: String): String = when (raw) {
    "g" -> "G"
    "pg" -> "PG"
    "pg_13" -> "PG-13"
    "r" -> "R-17"
    "r_plus" -> "R+"
    "rx" -> "Rx"
    else -> raw.uppercase()
}
