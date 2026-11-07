package app.animix.data.pulse

import app.animix.model.NewsItem

/**
 * A story of the week as AniPulse tells it: the news item, a short summary of two or three sentences
 * taken from the source's own lead (nothing invented), and whether it concerns the viewer's lists.
 */
data class PulseStory(val item: NewsItem, val summary: String, val mine: Boolean)

/**
 * The editorial part of an issue, written on the device from facts only: an opening paragraph about
 * the week, the main story, a few more stories worth reading, and the rest of the feed for those who want it.
 */
data class PulseIssue(
    val intro: String,
    val lead: PulseStory?,
    val stories: List<PulseStory>,
    val rest: List<NewsItem>,
)

/** How interesting a story is for this viewer: their titles first, then announcements, premieres and trailers, fresh ones first. */
internal fun storyScore(item: NewsItem, mine: Set<String>, nowMillis: Long): Double {
    var score = when (item.kind) { "Анонс" -> 5.0; "Премьера" -> 4.0; "Трейлер" -> 3.0; else -> 1.0 }
    if (item.anime?.key in mine) score += 6.0
    if (item.imageUrl != null) score += 1.5
    if (item.text.length >= 120) score += 1.0
    val age = app.animix.data.news.millis(item.createdAt)?.let { ((nowMillis - it) / 86_400_000.0).coerceIn(0.0, 8.0) } ?: 4.0
    return score + (7.0 - age) * 0.3
}

private val Boilerplate = listOf("источник", "читать далее", "читать полностью", "подробнее", "фото:", "скриншот", "http", "www.",
    "подписывайтесь", "telegram", "телеграм", "ссылка")

/**
 * Two or three sentences of the lead that tell the story: sentences that only repeat the headline or are
 * boilerplate («Источник», links) are left out; the result stays under [limit] characters and ends a sentence.
 */
fun storySummary(title: String, text: String, limit: Int = 300): String {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    if (clean.isEmpty()) return ""
    val sentences = Regex("""[^.!?…]+(?:[.!?…]+|$)(?:\s*[»"”)])?""").findAll(clean).map { it.value.trim() }.filter { it.length >= 3 }.toList()
    val headWords = words(title)
    val picked = mutableListOf<String>()
    var length = 0
    for (sentence in sentences) {
        val lower = sentence.lowercase()
        if (Boilerplate.any { it in lower }) continue
        // A first sentence that is the headline again says nothing new.
        val overlap = words(sentence).let { w -> if (w.isEmpty()) 1.0 else w.count { it in headWords }.toDouble() / w.size }
        if (picked.isEmpty() && overlap > 0.8 && sentences.size > 1) continue
        if (length + sentence.length > limit && picked.isNotEmpty()) break
        picked += sentence
        length += sentence.length + 1
        if (length >= limit * 0.55 && picked.size >= 2) break
    }
    var summary = picked.joinToString(" ")
    if (summary.length > limit) {
        // One very long sentence: cut at a word and say so with an ellipsis.
        summary = summary.take(limit).substringBeforeLast(' ').trimEnd(',', ';', ':', '—', '-', ' ') + "…"
    } else if (summary.isNotEmpty() && summary.last() !in ".!?…»\")") summary += "."
    return summary
}

private fun words(text: String): Set<String> =
    Regex("[\\p{L}\\p{N}]{3,}").findAll(text.lowercase().replace('ё', 'е')).map { it.value.take(6) }.toSet()

/**
 * Picks the issue's stories from the week's news: the main story (with a picture, unless a clearly more
 * interesting story has none), then up to four more, never two about the same title in a row of picks.
 */
fun pickStories(news: List<NewsItem>, mine: Set<String>, nowMillis: Long, more: Int = 4): Triple<PulseStory?, List<PulseStory>, List<NewsItem>> {
    if (news.isEmpty()) return Triple(null, emptyList(), emptyList())
    val ranked = news.sortedByDescending { storyScore(it, mine, nowMillis) }
    val best = ranked.first()
    val pictured = ranked.firstOrNull { it.imageUrl != null }
    val lead = if (pictured != null && storyScore(pictured, mine, nowMillis) >= storyScore(best, mine, nowMillis) - 2.5) pictured else best
    val chosen = mutableListOf<NewsItem>()
    val titles = mutableSetOf<String>()
    lead.anime?.key?.let(titles::add)
    for (item in ranked) {
        if (chosen.size >= more) break
        if (item == lead) continue
        val key = item.anime?.key
        if (key != null && key in titles) continue
        if (storySummary(item.title, item.text).isEmpty() && item.imageUrl == null && item.kind == "Новость") continue
        chosen += item
        key?.let(titles::add)
    }
    fun story(item: NewsItem) = PulseStory(item, storySummary(item.title, item.text), item.anime?.key in mine)
    val rest = news.filter { it != lead && it !in chosen }
    return Triple(story(lead), chosen.map(::story), rest)
}

/**
 * The opening paragraph of the issue, in plain Russian, from facts the app can check: the main story,
 * what else the week brought, new episodes in the lists, and how the viewer's own week went.
 */
fun issueIntro(digest: WeeklyDigest, lead: PulseStory?, stories: List<PulseStory>): String {
    val parts = mutableListOf<String>()
    if (lead != null) parts += "Главное на этой неделе: ${lead.item.title.trim().trimEnd('.')}."
    val counts = listOf("Анонс", "Трейлер", "Премьера").associateWith { kind -> digest.news.count { it.kind == kind && it != lead?.item } }
        .filterValues { it > 0 }
    if (counts.isNotEmpty()) {
        val phrase = counts.entries.map { (kind, n) -> kindCount(kind, n) }.let(::joinRussian)
        parts += "А ещё за неделю — $phrase."
    }
    val mineNews = (listOfNotNull(lead) + stories).filter { it.mine }.mapNotNull { it.item.anime?.title }.distinct()
    if (mineNews.isNotEmpty()) parts += "Есть новости и про ${quoted(mineNews.take(2))} из ваших списков."
    val fresh = digest.fresh.filter { it.unseen > 0 }
    when {
        fresh.size == 1 -> parts += "У ${quoted(listOf(fresh[0].anime.title))} " + (if (fresh[0].unseen == 1) "вышла новая серия." else "вас ждут новые серии: ${fresh[0].unseen}.")
        fresh.size > 1 -> parts += "Новые серии вышли у ${quoted(fresh.take(2).map { it.anime.title })}" + (if (fresh.size > 2) " и ещё ${fresh.size - 2} ${titlesWord(fresh.size - 2)}." else ".")
    }
    val week = digest.week
    if (week.minutes > 0) {
        val top = digest.watched.firstOrNull()?.anime?.title
        var sentence = "Вы провели за просмотром ${durationWords(week.minutes)}"
        if (week.episodes > 0) sentence += " и досмотрели ${week.episodes} ${episodesAcc(week.episodes)}"
        if (top != null && digest.watched.size > 1) sentence += ", больше всего — ${quoted(listOf(top))}"
        else if (top != null) sentence += " — всё это ${quoted(listOf(top))}"
        parts += "$sentence."
        digest.change?.let { change ->
            parts += when {
                change >= 15 -> "Это на $change % больше, чем неделей раньше."
                change <= -15 -> "Неделя вышла спокойнее прошлой."
                else -> "Почти столько же, сколько неделей раньше."
            }
        }
    } else if (parts.isNotEmpty()) parts += "Если выдастся свободный вечер — подборка недели в конце выпуска."
    return parts.joinToString(" ").ifEmpty { "Неделя только началась: новостей пока немного. Загляните позже — выпуск обновляется сам." }
}

private fun quoted(titles: List<String>): String = joinRussian(titles.map { "«${it.trim()}»" })

private fun joinRussian(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> items.dropLast(1).joinToString(", ") + " и " + items.last()
}

private fun kindCount(kind: String, n: Int): String {
    val word = when (kind) {
        "Анонс" -> plural(n, "анонс", "анонса", "анонсов")
        "Трейлер" -> plural(n, "трейлер", "трейлера", "трейлеров")
        else -> plural(n, "премьера", "премьеры", "премьер")
    }
    return if (n == 1) word else "$n $word"
}

private fun durationWords(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$minutes ${plural(minutes, "минуту", "минуты", "минут")}"
        rest == 0 -> "$hours ${plural(hours, "час", "часа", "часов")}"
        else -> "$hours ${plural(hours, "час", "часа", "часов")} $rest ${plural(rest, "минуту", "минуты", "минут")}"
    }
}

private fun episodesAcc(n: Int) = plural(n, "серию", "серии", "серий")
private fun titlesWord(n: Int) = plural(n, "тайтла", "тайтлов", "тайтлов")

private fun plural(n: Int, one: String, few: String, many: String): String = when {
    n % 100 in 11..14 -> many
    n % 10 == 1 -> one
    n % 10 in 2..4 -> few
    else -> many
}

/** The whole editorial part of an issue for [digest]. */
fun issueFrom(digest: WeeklyDigest, mine: Set<String>, nowMillis: Long): PulseIssue {
    val (lead, stories, rest) = pickStories(digest.news, mine, nowMillis)
    return PulseIssue(issueIntro(digest, lead, stories), lead, stories, rest)
}
