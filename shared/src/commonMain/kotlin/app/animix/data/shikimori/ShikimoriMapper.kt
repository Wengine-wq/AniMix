package app.animix.data.shikimori

import app.animix.model.Anime
import app.animix.model.AnimeDetail
import app.animix.model.AnimeKind
import app.animix.model.AnimeTopic
import app.animix.model.AnimeStatus
import app.animix.model.Genre
import app.animix.model.Review
import app.animix.model.ReviewSource
import app.animix.core.formatIsoDate
import app.animix.core.RichText
import app.animix.core.RichTextParser

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

internal fun AnimeDto.toDetail(): AnimeDetail {
    val ordered = app.animix.data.TitleLinks.orderRelated(related.orEmpty().mapNotNull { item ->
        item.anime?.toAnime()?.let { app.animix.data.TitleLinks.Related(it, item.relationKind, item.relationText?.takeIf(String::isNotBlank)) }
    })
    return AnimeDetail(
    anime = toAnime(),
    description = descriptionHtml?.takeIf { it.isNotBlank() }?.let(RichTextParser::parseHtml)
        ?: description?.let(RichTextParser::parseBbCode) ?: RichText.Empty,
    durationMinutes = duration?.takeIf { it > 0 },
    rating = rating?.takeIf { it != "none" }?.let(::ratingLabel),
    studios = studios.orEmpty().map { it.name },
    screenshots = screenshots.orEmpty().mapNotNull { it.originalUrl ?: it.x332Url },
    nextEpisodeAt = nextEpisodeAt,
    related = ordered.map { it.anime },
    topicId = topic?.id?.toIntOrNull(),
    commentsCount = topic?.commentsCount ?: 0,
    relatedLabels = ordered.mapNotNull { r -> r.label?.let { r.anime.key to it } }.toMap(),
)
}

private fun absoluteShikimori(path: String): String =
    if (path.startsWith("http")) path else "${ShikimoriApi.BASE_URL}${if (path.startsWith("/")) path else "/$path"}"

internal fun RestAnimeDto.toAnime(): Anime = Anime(
    id = id,
    title = russian?.takeIf { it.isNotBlank() } ?: name,
    originalTitle = name,
    englishTitle = null,
    posterUrl = image?.original?.let(::absoluteShikimori),
    score = score?.toDoubleOrNull()?.takeIf { it > 0 },
    kind = AnimeKind.from(kind),
    status = AnimeStatus.from(status),
    episodes = episodes,
    episodesAired = episodes_aired,
    year = aired_on?.take(4)?.toIntOrNull(),
    genres = emptyList(),
)

internal fun CommentDto.toReview(): Review = Review(
    id = "s$id",
    author = user?.nickname.orEmpty(),
    avatarUrl = (user?.image?.get("x80") ?: user?.image?.get("x48") ?: user?.avatar)?.let(::absoluteShikimori),
    body = html_body?.let(RichTextParser::parseHtml)?.takeUnless { it.isBlank } ?: RichTextParser.parseBbCode(body),
    score = null,
    date = formatIsoDate(created_at),
    likes = null,
    source = ReviewSource.Shikimori,
)

internal fun NewsTopicDto.toTopic(): AnimeTopic = AnimeTopic(
    id = id,
    title = topic_title.orEmpty().trim(),
    text = (html_body?.let(RichTextParser::parseHtml)?.takeUnless { it.isBlank } ?: RichTextParser.parseBbCode(body.orEmpty())).plain,
    createdAt = created_at,
    event = event?.takeIf { it.isNotBlank() },
    episode = episode,
)

private val firstImage = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)

/** A feed topic as AniPulse shows it; topics about something other than anime are left out. */
internal fun NewsTopicDto.toNews(): app.animix.model.NewsItem? {
    val title = topic_title.orEmpty().trim().takeIf { it.isNotEmpty() } ?: return null
    if (linked_type != null && linked_type != "Anime") return null
    val text = (html_body?.let(RichTextParser::parseHtml)?.takeUnless { it.isBlank } ?: RichTextParser.parseBbCode(body.orEmpty())).plain
    fun absolute(url: String?) = url?.takeIf { it.isNotBlank() && !it.contains("missing_") }
        ?.let { if (it.startsWith("http")) it else ShikimoriApi.BASE_URL + it }
    val anime = linked?.takeIf { it.id > 0 }?.let { link ->
        app.animix.model.Anime.placeholder(link.id).copy(title = link.russian?.takeIf(String::isNotBlank) ?: link.name.orEmpty(),
            originalTitle = link.name.orEmpty(), posterUrl = absolute(link.image?.original))
    }
    val kind = app.animix.data.news.kindOf(title)
    return app.animix.model.NewsItem(id, title, text, created_at, kind, anime,
        imageUrl = absolute(html_body?.let { firstImage.find(it)?.groupValues?.get(1) }) ?: anime?.posterUrl,
        url = "${ShikimoriApi.BASE_URL}/forum/news/$id")
}

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
