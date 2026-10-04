package komascroll.panels

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A panel as fractions (0..1) of the page width and height. */
@Serializable
data class PanelBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height

    /** Grows the box by [fraction] of its size on every side, staying within the page. */
    fun padded(fraction: Float): PanelBox {
        val dx = width * fraction
        val dy = height * fraction
        return PanelBox(
            (left - dx).coerceAtLeast(0f),
            (top - dy).coerceAtLeast(0f),
            (right + dx).coerceAtMost(1f),
            (bottom + dy).coerceAtMost(1f),
        )
    }
}

/** Panels of one page in reading order, with how confident the detector is in them. */
@Serializable
data class PanelLayout(val panels: List<PanelBox>, val confidence: Float) {
    /** Whether guided view should use these panels (otherwise the full page is shown). */
    val isUsable: Boolean get() = panels.size >= 2 && confidence >= MIN_CONFIDENCE

    companion object {
        const val MIN_CONFIDENCE = 0.75f
        val EMPTY = PanelLayout(emptyList(), 0f)
    }
}

/**
 * Classical panel detection by recursive XY-cut along gutters, no ML or OpenCV needed.
 *
 * The page's gutter colour is taken from its border (white or black pages both work), every pixel
 * that differs from it is "ink", and regions are split recursively along rows/columns that are
 * (almost) free of ink. The recursion yields the reading order directly: horizontal cuts read
 * top-to-bottom, vertical cuts read right-to-left for manga ([rightToLeft]) or left-to-right.
 *
 * Works on a small grayscale copy of the page (a few hundred pixels per side is plenty).
 */
object PanelDetector {

    /** Gray difference from the gutter colour that counts as ink. */
    private const val INK_THRESHOLD = 48

    /** A gutter row/column may contain up to this share of ink pixels (scan noise, speed lines). */
    private const val GUTTER_INK_RATIO = 0.01f

    /** Minimum gutter thickness as a share of the page's shorter side. */
    private const val MIN_GUTTER = 0.008f

    /** Regions smaller than this share of the page are not split further. */
    private const val MIN_SPLIT_AREA = 0.04f

    /** Leaves smaller than this share of the page are dropped (page numbers, specks). */
    private const val MIN_PANEL_AREA = 0.012f

    private const val MAX_DEPTH = 8
    private const val MAX_PANELS = 16

    /**
     * [gray] holds [width] × [height] luminance values (0..255), row by row.
     */
    fun detect(gray: IntArray, width: Int, height: Int, rightToLeft: Boolean): PanelLayout {
        require(gray.size >= width * height) { "Pixel buffer too small" }
        if (width < 16 || height < 16) return PanelLayout.EMPTY

        val background = borderMedian(gray, width, height)
        val ink = BooleanArray(width * height) { abs(gray[it] - background) > INK_THRESHOLD }
        val totalInk = ink.count { it }
        if (totalInk == 0) return PanelLayout.EMPTY

        val page = Region(0, 0, width, height)
        val minGutter = max(2, (min(width, height) * MIN_GUTTER).toInt())
        val leaves = mutableListOf<Region>()
        split(ink, width, page, minGutter, rightToLeft, depth = 0, out = leaves)

        val pageArea = width.toFloat() * height
        val panels = leaves
            .filter { it.area / pageArea >= MIN_PANEL_AREA }
            .take(MAX_PANELS + 1)

        if (panels.isEmpty()) return PanelLayout.EMPTY

        val inkInside = panels.sumOf { countInk(ink, width, it) }
        val coverage = inkInside.toFloat() / totalInk
        val countFactor = when (panels.size) {
            1 -> 0f
            in 2..10 -> 1f
            in 11..MAX_PANELS -> 0.7f
            else -> 0.3f
        }
        // A "panel" covering almost the whole page means no real structure was found.
        val largest = panels.maxOf { it.area } / pageArea
        val sizeFactor = if (largest > 0.9f) 0.3f else 1f

        return PanelLayout(
            panels = panels.take(MAX_PANELS).map {
                PanelBox(it.left / width.toFloat(), it.top / height.toFloat(), it.right / width.toFloat(), it.bottom / height.toFloat())
            },
            confidence = coverage * countFactor * sizeFactor,
        )
    }

    private data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val area get() = width.toFloat() * height
    }

    private fun split(
        ink: BooleanArray,
        stride: Int,
        region: Region,
        minGutter: Int,
        rightToLeft: Boolean,
        depth: Int,
        out: MutableList<Region>,
    ) {
        val trimmed = trimToInk(ink, stride, region) ?: return
        val pageArea = (ink.size).toFloat()
        if (depth >= MAX_DEPTH || trimmed.area / pageArea < MIN_SPLIT_AREA) {
            out += trimmed
            return
        }

        val rowCuts = gutterRuns(ink, stride, trimmed, horizontal = true, minGutter)
        val columnCuts = gutterRuns(ink, stride, trimmed, horizontal = false, minGutter)
        if (rowCuts.isEmpty() && columnCuts.isEmpty()) {
            out += trimmed
            return
        }

        // Cut along the orientation with the widest gutter; ties favour rows (manga tiers).
        val widestRow = rowCuts.maxOfOrNull { it.last - it.first + 1 } ?: 0
        val widestColumn = columnCuts.maxOfOrNull { it.last - it.first + 1 } ?: 0
        val children = if (widestRow >= widestColumn) {
            slices(trimmed.top, trimmed.bottom, rowCuts).map { (start, end) ->
                Region(trimmed.left, start, trimmed.right, end)
            }
        } else {
            val columns = slices(trimmed.left, trimmed.right, columnCuts).map { (start, end) ->
                Region(start, trimmed.top, end, trimmed.bottom)
            }
            if (rightToLeft) columns.reversed() else columns
        }
        children.forEach { split(ink, stride, it, minGutter, rightToLeft, depth + 1, out) }
    }

    /** Interior runs of near-empty rows (or columns) at least [minGutter] thick. */
    private fun gutterRuns(
        ink: BooleanArray,
        stride: Int,
        region: Region,
        horizontal: Boolean,
        minGutter: Int,
    ): List<IntRange> {
        val lines = if (horizontal) region.top until region.bottom else region.left until region.right
        val lineLength = if (horizontal) region.width else region.height
        val allowed = (lineLength * GUTTER_INK_RATIO).toInt()

        val runs = mutableListOf<IntRange>()
        var runStart = -1
        for (line in lines) {
            val count = if (horizontal) {
                countInk(ink, stride, Region(region.left, line, region.right, line + 1))
            } else {
                countInk(ink, stride, Region(line, region.top, line + 1, region.bottom))
            }
            if (count <= allowed) {
                if (runStart < 0) runStart = line
            } else if (runStart >= 0) {
                if (line - runStart >= minGutter) runs += runStart until line
                runStart = -1
            }
        }
        // Runs touching the region edge are margins, already removed by trimming.
        return runs.filter { it.first > lines.first && it.last < lines.last }
    }

    /** The pieces of [start]..[end] between the [cuts]. */
    private fun slices(start: Int, end: Int, cuts: List<IntRange>): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var cursor = start
        for (cut in cuts.sortedBy { it.first }) {
            if (cut.first > cursor) result += cursor to cut.first
            cursor = cut.last + 1
        }
        if (cursor < end) result += cursor to end
        return result
    }

    private fun trimToInk(ink: BooleanArray, stride: Int, region: Region): Region? {
        var left = region.right
        var right = region.left
        var top = region.bottom
        var bottom = region.top
        for (y in region.top until region.bottom) {
            val row = y * stride
            for (x in region.left until region.right) {
                if (ink[row + x]) {
                    if (x < left) left = x
                    if (x + 1 > right) right = x + 1
                    if (y < top) top = y
                    if (y + 1 > bottom) bottom = y + 1
                }
            }
        }
        return if (left < right && top < bottom) Region(left, top, right, bottom) else null
    }

    private fun countInk(ink: BooleanArray, stride: Int, region: Region): Int {
        var count = 0
        for (y in region.top until region.bottom) {
            val row = y * stride
            for (x in region.left until region.right) if (ink[row + x]) count++
        }
        return count
    }

    /** Median gray level of a thin frame around the page: the gutter/background colour. */
    private fun borderMedian(gray: IntArray, width: Int, height: Int): Int {
        val frame = max(1, min(width, height) / 50)
        val values = ArrayList<Int>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x < frame || y < frame || x >= width - frame || y >= height - frame) values += gray[y * width + x]
            }
        }
        values.sort()
        return values[values.size / 2]
    }
}
