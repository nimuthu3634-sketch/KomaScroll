package komascroll.translate.engine

import kotlin.math.abs
import kotlin.math.max

/**
 * ARGB pixels of a rectangular region of a page. [originX]/[originY] are the region's position in
 * the full image, so callers can keep working in page coordinates.
 */
class PixelRegion(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val originX: Int = 0,
    val originY: Int = 0,
) {
    init {
        require(pixels.size >= width * height) { "Pixel buffer too small" }
    }

    val bounds: Box get() = Box(originX, originY, originX + width, originY + height)

    fun pixelAt(x: Int, y: Int): Int = pixels[(y - originY) * width + (x - originX)]
}

/**
 * Classical (non-ML) analysis of the area around detected text: decides whether a bubble has a
 * near-uniform background that can be repainted, and estimates how far that background extends.
 */
object BackgroundAnalyzer {

    data class Sample(val color: Int, val uniformity: Float) {
        val isUniform: Boolean get() = uniformity >= UNIFORM_THRESHOLD
    }

    /** Share of ring pixels that must match the median colour for a background to count as uniform. */
    const val UNIFORM_THRESHOLD = 0.88f

    /** Maximum per-channel difference for two colours to count as "the same" background. */
    const val COLOR_TOLERANCE = 28

    private const val MAX_SAMPLES = 4000

    /**
     * Samples a ring [ringWidth] pixels wide just outside [box] and returns its median colour and
     * how uniform it is. Pixels outside [region] are ignored.
     */
    fun sampleRing(region: PixelRegion, box: Box, ringWidth: Int): Sample? {
        val outer = box.expand(ringWidth).intersection(region.bounds) ?: return null
        val total = outer.area - (box.intersection(outer)?.area ?: 0L)
        if (total <= 0) return null
        val step = max(1, kotlin.math.sqrt(total.toDouble() / MAX_SAMPLES).toInt())

        val samples = ArrayList<Int>()
        var y = outer.top
        while (y < outer.bottom) {
            var x = outer.left
            while (x < outer.right) {
                val insideText = x >= box.left && x < box.right && y >= box.top && y < box.bottom
                if (!insideText) samples += region.pixelAt(x, y)
                x += step
            }
            y += step
        }
        if (samples.isEmpty()) return null

        val median = medianColor(samples)
        val matching = samples.count { isSimilar(it, median, COLOR_TOLERANCE) }
        return Sample(median, matching.toFloat() / samples.size)
    }

    /**
     * Flood-fills the background colour outward from the ring around [box], staying within
     * [maxExpand] pixels of it, and returns the bounding box of the filled area: an estimate of the
     * bubble's interior. Falls back to [box] when nothing matches.
     */
    fun estimateInterior(region: PixelRegion, box: Box, color: Int, maxExpand: Int): Box {
        val limit = box.expand(maxExpand).intersection(region.bounds) ?: return box
        val w = limit.width
        val h = limit.height
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0

        fun offer(x: Int, y: Int) {
            if (x < limit.left || x >= limit.right || y < limit.top || y >= limit.bottom) return
            val index = (y - limit.top) * w + (x - limit.left)
            if (visited[index]) return
            visited[index] = true
            // Text pixels inside the box are passed through so the fill can reach every gap.
            val inside = x >= box.left && x < box.right && y >= box.top && y < box.bottom
            if (inside || isSimilar(region.pixelAt(x, y), color, COLOR_TOLERANCE)) {
                queue[tail++] = index
            }
        }

        for (x in box.left until box.right) {
            offer(x, box.top)
            offer(x, box.bottom - 1)
        }
        for (y in box.top until box.bottom) {
            offer(box.left, y)
            offer(box.right - 1, y)
        }

        var minX = box.left
        var minY = box.top
        var maxX = box.right
        var maxY = box.bottom
        while (head < tail) {
            val index = queue[head++]
            val x = limit.left + index % w
            val y = limit.top + index / w
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x + 1 > maxX) maxX = x + 1
            if (y + 1 > maxY) maxY = y + 1
            offer(x + 1, y)
            offer(x - 1, y)
            offer(x, y + 1)
            offer(x, y - 1)
        }
        return Box(minX, minY, maxX, maxY)
    }

    fun isSimilar(a: Int, b: Int, tolerance: Int): Boolean =
        abs(red(a) - red(b)) <= tolerance &&
            abs(green(a) - green(b)) <= tolerance &&
            abs(blue(a) - blue(b)) <= tolerance

    /** Relative luminance in 0..1, used to pick black or white text. */
    fun luminance(color: Int): Float = (0.2126f * red(color) + 0.7152f * green(color) + 0.0722f * blue(color)) / 255f

    private fun medianColor(colors: List<Int>): Int {
        fun channelMedian(selector: (Int) -> Int): Int {
            val values = IntArray(colors.size) { selector(colors[it]) }
            values.sort()
            return values[values.size / 2]
        }
        return argb(255, channelMedian(::red), channelMedian(::green), channelMedian(::blue))
    }

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
}
