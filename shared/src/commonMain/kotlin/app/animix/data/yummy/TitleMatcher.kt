package app.animix.data.yummy

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Scores how well two anime titles match, 0..100.
 *
 * Word-level F1 score, with a heavy penalty when a season number ("2", "III")
 * appears in one title and not the other, so "Title 2" never matches "Title".
 */
internal object TitleMatcher {

    fun score(query: String, target: String): Int {
        val q = normalize(query)
        val t = normalize(target)
        if (q.isEmpty() || t.isEmpty()) return 0
        if (q == t) return 100

        val wordsQ = q.split(' ')
        val wordsT = t.split(' ')
        val numbersQ = wordsQ.filter(::isSeasonToken)
        val numbersT = wordsT.filter(::isSeasonToken)
        val missingNumber = if (numbersQ.isNotEmpty()) numbersQ.any { it !in numbersT } else numbersT.isNotEmpty()

        val matches = wordsQ.count { it in wordsT }
        val recall = matches.toDouble() / wordsQ.size
        val precision = matches.toDouble() / wordsT.size
        var score = if (recall + precision == 0.0) 0.0 else 2 * recall * precision / (recall + precision) * 100
        if (missingNumber) score -= 60
        if (!missingNumber && q.length > 4 && t.contains(q)) score += 8
        return score.coerceIn(0.0, 100.0).roundToInt()
    }

    fun best(queries: List<String>, targets: List<String>): Int {
        var best = 0
        for (q in queries) for (t in targets) best = max(best, score(q, t))
        return best
    }

    private val year = Regex("""[(\[{]?\d{4}[)\]}]?""")
    private val punctuation = Regex("""[^\p{L}\p{N}\s]""")
    private val spaces = Regex("""\s+""")

    fun normalize(value: String): String =
        value.lowercase()
            .replace('ё', 'е')
            .replace(year, " ")
            .replace(punctuation, " ")
            .replace(spaces, " ")
            .trim()

    private fun isSeasonToken(word: String) =
        word.all(Char::isDigit) || word == "ii" || word == "iii" || word == "iv"
}
