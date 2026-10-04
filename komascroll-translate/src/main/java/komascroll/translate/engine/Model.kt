package komascroll.translate.engine

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** Integer rectangle in image pixels. Plain Kotlin so the layout logic is unit-testable off-device. */
@Serializable
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Long get() = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)

    fun expand(dx: Int, dy: Int = dx) = Box(left - dx, top - dy, right + dx, bottom + dy)

    fun intersects(other: Box) =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun union(other: Box) = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    fun intersection(other: Box): Box? {
        val l = max(left, other.left)
        val t = max(top, other.top)
        val r = min(right, other.right)
        val b = min(bottom, other.bottom)
        return if (l < r && t < b) Box(l, t, r, b) else null
    }

    fun clampTo(width: Int, height: Int) =
        Box(left.coerceIn(0, width), top.coerceIn(0, height), right.coerceIn(0, width), bottom.coerceIn(0, height))

    fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom

    /** Intersection over union, used to drop duplicate detections from overlapping OCR chunks. */
    fun iou(other: Box): Float {
        val inter = intersection(other)?.area ?: return 0f
        return inter.toFloat() / (area + other.area - inter)
    }
}

/** One line of text as reported by OCR. */
data class OcrLine(val text: String, val box: Box)

/** One translated speech bubble (or caption) on a page. Coordinates are in original-image pixels. */
@Serializable
data class TranslatedBubble(
    /** Union of the recognized text lines. */
    val textBox: Box,
    /** Area the translation may be typeset into (the estimated bubble interior). */
    val layoutBox: Box,
    val vertical: Boolean,
    val original: String,
    val translated: String,
    /** Sampled background colour when it is near-uniform (the text is painted over); null otherwise. */
    val fillColor: Int?,
)

/** Everything needed to redraw a translated page onto any resolution of the same image. */
@Serializable
data class PageTranslation(
    val imageWidth: Int,
    val imageHeight: Int,
    val sourceLanguage: String,
    val targetLanguage: String,
    val engine: String,
    val bubbles: List<TranslatedBubble>,
) {
    /** The bubble under a point given as fractions (0..1) of the page width and height, if any. */
    fun bubbleAt(fractionX: Float, fractionY: Float): TranslatedBubble? {
        val x = fractionX * imageWidth
        val y = fractionY * imageHeight
        val slop = imageWidth / 100
        return bubbles.firstOrNull { it.layoutBox.expand(slop).contains(x, y) || it.textBox.expand(slop).contains(x, y) }
    }

    companion object {
        const val FORMAT_VERSION = 1
    }
}
