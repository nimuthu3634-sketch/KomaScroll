package komascroll.insights

import java.text.Normalizer

/** Fuzzy title comparison used to pick search results that may be the same series. */
object TitleSimilarity {

    private val BRACKETS = Regex("""[(\[{].*?[)\]}]""")
    private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")

    /** Lower-case, accent-free, bracket-free, single-spaced form of a title. */
    fun normalize(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
            .replace(BRACKETS, " ")
            .replace(NON_ALPHANUMERIC, " ")
            .trim()

    /** Sørensen–Dice coefficient over character bigrams of the normalised titles (0..1). */
    fun similarity(a: String, b: String): Float {
        val x = normalize(a).replace(" ", "")
        val y = normalize(b).replace(" ", "")
        if (x.isEmpty() || y.isEmpty()) return 0f
        if (x == y) return 1f
        if (x.length < 2 || y.length < 2) return 0f
        val bigramsA = x.windowed(2).groupingBy { it }.eachCount()
        val bigramsB = y.windowed(2).groupingBy { it }.eachCount()
        val shared = bigramsA.entries.sumOf { (bigram, count) -> minOf(count, bigramsB[bigram] ?: 0) }
        return 2f * shared / (x.length - 1 + y.length - 1)
    }
}
