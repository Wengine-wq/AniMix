package app.animix.core

private val lineBreakTags = Regex("""<\s*(br|/p|/div|/h[1-6]|/li|/blockquote)\s*/?>""", RegexOption.IGNORE_CASE)
private val tags = Regex("""<[^>]+>""")
private val manyBreaks = Regex("""\n[ \t]*\n(\s*\n)+""")
private val spaces = Regex("""[ \t ]+""")

/** Turns review/comment HTML into readable plain text with paragraph breaks. */
fun htmlToText(html: String): String =
    html.replace(lineBreakTags, "\n")
        .replace(tags, "")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&laquo;", "«")
        .replace("&raquo;", "»")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace(spaces, " ")
        .replace(manyBreaks, "\n\n")
        .lines().joinToString("\n") { it.trim() }
        .trim()

/** Unix seconds to "dd.MM.yyyy" (UTC; a day off near midnight is fine for review dates). */
fun formatDate(epochSeconds: Long): String {
    // Civil-from-days (Howard Hinnant), avoids a date-time dependency for one format.
    val z = epochSeconds.floorDiv(86_400) + 719_468
    val era = z.floorDiv(146_097)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return "${day.toString().padStart(2, '0')}.${month.toString().padStart(2, '0')}.$year"
}

/** ISO date-time ("2024-05-01T10:00:00+03:00") to "dd.MM.yyyy". */
fun formatIsoDate(iso: String): String {
    val parts = iso.take(10).split('-')
    return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else iso
}
