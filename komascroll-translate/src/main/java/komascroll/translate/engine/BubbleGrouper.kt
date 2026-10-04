package komascroll.translate.engine

import kotlin.math.max

/**
 * Groups OCR lines into speech bubbles by proximity and orders them for reading.
 *
 * Manga text is often vertical (columns read top-to-bottom, right-to-left), so each line's
 * orientation is inferred from its shape and only lines of compatible orientation are merged.
 */
object BubbleGrouper {

    data class Group(val lines: List<OcrLine>, val vertical: Boolean) {
        val box: Box = lines.map { it.box }.reduce(Box::union)

        /** Lines joined in reading order; CJK lines are concatenated, others joined with spaces. */
        val text: String = joinLines(lines.map { it.text.trim() })
    }

    /** How far (in character sizes) two lines may be apart and still belong to one bubble. */
    private const val GAP_FACTOR = 0.6f

    /** A line is vertical when it is clearly taller than wide (and has 2+ characters). */
    private const val VERTICAL_RATIO = 1.5f

    /** Detections from overlapping OCR chunks that overlap this much are treated as duplicates. */
    private const val DUPLICATE_IOU = 0.6f

    fun group(lines: List<OcrLine>): List<Group> {
        val candidates = dedupe(lines.filter { it.text.isNotBlank() && it.box.width > 0 && it.box.height > 0 })
        if (candidates.isEmpty()) return emptyList()

        val orientation = candidates.map(::orientationOf)
        val parent = IntArray(candidates.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                if (!compatible(orientation[i], orientation[j])) continue
                if (areNear(candidates[i], orientation[i], candidates[j], orientation[j])) {
                    parent[find(i)] = find(j)
                }
            }
        }

        return candidates.indices
            .groupBy(::find)
            .values
            .map { members ->
                val memberLines = members.map { candidates[it] }
                val votes = members.mapNotNull { orientation[it] }
                val vertical = votes.isNotEmpty() && votes.count { it } * 2 > votes.size
                Group(order(memberLines, vertical), vertical)
            }
            .filter { group -> group.text.any(Char::isLetter) }
            .sortedWith(compareBy({ it.box.top }, { -it.box.right }))
    }

    /** true = vertical, false = horizontal, null = undecidable (single character). */
    internal fun orientationOf(line: OcrLine): Boolean? {
        if (line.text.trim().codePointCount(0, line.text.trim().length) < 2) return null
        return line.box.height >= line.box.width * VERTICAL_RATIO
    }

    private fun compatible(a: Boolean?, b: Boolean?) = a == null || b == null || a == b

    private fun charSize(line: OcrLine, vertical: Boolean?): Int = when (vertical) {
        true -> line.box.width
        false -> line.box.height
        null -> minOf(line.box.width, line.box.height)
    }

    private fun areNear(a: OcrLine, va: Boolean?, b: OcrLine, vb: Boolean?): Boolean {
        val gap = (max(charSize(a, va), charSize(b, vb)) * GAP_FACTOR).toInt().coerceAtLeast(1)
        return a.box.expand(gap).intersects(b.box)
    }

    /** Vertical bubbles read right-to-left by column; horizontal ones top-to-bottom by line. */
    private fun order(lines: List<OcrLine>, vertical: Boolean): List<OcrLine> =
        if (vertical) {
            lines.sortedWith(compareByDescending<OcrLine> { it.box.centerX }.thenBy { it.box.top })
        } else {
            lines.sortedWith(compareBy<OcrLine> { it.box.centerY }.thenBy { it.box.left })
        }

    private fun dedupe(lines: List<OcrLine>): List<OcrLine> {
        val kept = mutableListOf<OcrLine>()
        for (line in lines.sortedByDescending { it.text.length }) {
            if (kept.none { it.box.iou(line.box) >= DUPLICATE_IOU }) kept += line
        }
        return kept
    }

    internal fun joinLines(parts: List<String>): String {
        val builder = StringBuilder()
        for (part in parts) {
            if (part.isEmpty()) continue
            if (builder.isNotEmpty()) {
                val previous = builder.last()
                when {
                    // Japanese and Chinese have no spaces between words.
                    isCjk(previous) && isCjk(part.first()) -> Unit
                    // Rejoin words hyphenated across lines.
                    previous == '-' && part.first().isLowerCase() -> builder.setLength(builder.length - 1)
                    else -> builder.append(' ')
                }
            }
            builder.append(part)
        }
        return builder.toString()
    }

    /** Han, kana and CJK punctuation (Hangul is excluded: Korean separates words with spaces). */
    internal fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }
}
