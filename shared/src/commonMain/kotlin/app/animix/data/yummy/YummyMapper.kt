package app.animix.data.yummy

import app.animix.model.Anime
import app.animix.model.AnimeDetail
import app.animix.model.AnimeKind
import app.animix.model.AnimeSource
import app.animix.model.AnimeStatus
import app.animix.model.Review
import app.animix.model.ReviewSource
import app.animix.core.formatDate
import app.animix.core.RichText
import app.animix.core.RichTextParser

private fun String.absolute(): String = if (startsWith("//")) "https:$this" else this

private val latin = Regex("[A-Za-z]")

internal fun YummyRelease.toAnime(): Anime = Anime(
    id = anime_id,
    title = title,
    originalTitle = other_titles?.firstOrNull { latin.containsMatchIn(it) } ?: title,
    englishTitle = null,
    posterUrl = (poster?.fullsize ?: poster?.big ?: poster?.medium)?.absolute(),
    score = (rating?.shikimori_rating ?: rating?.average)?.takeIf { it > 0 },
    kind = AnimeKind.from(type?.alias),
    status = AnimeStatus.from(anime_status?.alias),
    episodes = episodes?.count ?: 0,
    episodesAired = episodes?.aired ?: 0,
    year = year?.takeIf { it > 0 },
    genres = genres.orEmpty().mapNotNull { it.title?.takeIf(String::isNotBlank) },
    source = AnimeSource.Yummy,
)

internal fun YummyReview.toReview(): Review = Review(
    id = "y$review_id",
    author = author?.nickname ?: nickname.orEmpty(),
    avatarUrl = (author?.avatars?.small ?: avatar?.small)?.absolute(),
    body = RichTextParser.parseHtml(text_html ?: text_preview.orEmpty()),
    score = rating?.average?.takeIf { it > 0 },
    date = formatDate(create_date),
    likes = likes?.likes,
    source = ReviewSource.Yummy,
)

internal fun YummyRelease.toDetail(): AnimeDetail {
    val count = episodes?.count?.takeIf { it > 0 }
    return AnimeDetail(
        anime = toAnime(),
        description = description?.let(RichTextParser::parseBbCode) ?: RichText.Empty,
        // YummyAnime reports the running time of the whole release, in seconds.
        durationMinutes = if (duration != null && count != null) (duration / count / 60).takeIf { it in 1..240 } else null,
        rating = min_age?.title,
        studios = studios.orEmpty().mapNotNull { it.title },
        screenshots = random_screenshots.orEmpty().mapNotNull { it.sizes?.full ?: it.sizes?.small }.map { it.absolute() },
        nextEpisodeAt = null,
        related = emptyList(),
    )
}
