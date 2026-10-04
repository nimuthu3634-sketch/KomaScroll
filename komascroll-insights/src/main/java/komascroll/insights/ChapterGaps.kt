package komascroll.insights

import kotlin.math.floor

object ChapterGaps {

    /** The first whole chapter number missing between the lowest and highest known chapters. */
    fun firstMissing(chapterNumbers: Collection<Double>): Double? {
        val whole = chapterNumbers.filter { it >= 0.0 }.map { floor(it).toLong() }.distinct().sorted()
        for ((previous, next) in whole.zipWithNext()) {
            if (next - previous > 1) return (previous + 1).toDouble()
        }
        return null
    }
}
