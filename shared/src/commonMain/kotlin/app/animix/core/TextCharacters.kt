package app.animix.core

// Both supported platforms (Android ICU and desktop JDK) implement Unicode grapheme matching.
private val characterPattern = Regex("\\X")

/** Count visible characters, keeping surrogate pairs, combining marks and joined emoji together. */
fun String.characterCount(): Int = characterPattern.findAll(this).count()

fun String.takeCharacters(limit: Int): String {
    require(limit >= 0)
    if (limit == 0) return ""
    var end = 0
    val matches = characterPattern.findAll(this).iterator()
    repeat(limit) {
        if (!matches.hasNext()) return this
        end = matches.next().range.last + 1
    }
    return substring(0, end)
}
