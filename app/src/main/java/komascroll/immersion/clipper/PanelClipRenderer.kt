package komascroll.immersion.clipper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.min

/** Image work for the panel clipper: decoding a page, blurring spoilers and cropping the clip. */
object PanelClipRenderer {

    /** Pages are decoded at most this many pixels so large webtoon strips stay within memory. */
    private const val MAX_PIXELS = 8_000_000L

    /** Blurred regions are shrunk until their shorter side is about this many pixels. */
    private const val BLUR_RESOLUTION = 10

    fun decode(bytes: ByteArray): Bitmap? {
        val decoder = ImageDecoder.newInstance(ByteArrayInputStream(bytes)) ?: return null
        if (decoder.width <= 0 || decoder.height <= 0) {
            decoder.recycle()
            return null
        }
        var sampleSize = 1
        while (decoder.width.toLong() * decoder.height / (sampleSize.toLong() * sampleSize) > MAX_PIXELS) {
            sampleSize *= 2
        }
        val decoded = try {
            decoder.decode(sampleSize = sampleSize)
        } finally {
            decoder.recycle()
        } ?: return null
        return if (decoded.config == Bitmap.Config.HARDWARE) {
            decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
        } else {
            decoded
        }
    }

    /** A copy of [source] with every rectangle in [blurs] blurred beyond recognition. */
    fun withBlurs(source: Bitmap, blurs: List<Rect>): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        if (blurs.isEmpty()) return output
        val canvas = Canvas(output)
        for (rect in blurs) {
            val region = Rect(rect).apply { intersect(0, 0, source.width, source.height) }
            if (region.width() < 2 || region.height() < 2) continue
            val blurred = blur(source, region)
            canvas.drawBitmap(blurred, region.left.toFloat(), region.top.toFloat(), null)
            blurred.recycle()
        }
        return output
    }

    /** Crops [crop] out of [source] after blurring [blurs] (both in source pixels). */
    fun render(source: Bitmap, crop: Rect, blurs: List<Rect>): Bitmap {
        val bounded = Rect(crop).apply { intersect(0, 0, source.width, source.height) }
        val blurred = withBlurs(source, blurs.filter { Rect.intersects(it, bounded) })
        if (bounded.width() == source.width && bounded.height() == source.height) return blurred
        return Bitmap.createBitmap(blurred, bounded.left, bounded.top, bounded.width(), bounded.height())
            .also { if (it !== blurred) blurred.recycle() }
    }

    /**
     * Scales the region down to a few pixels and back up twice with filtering, which smears text
     * and faces into soft colour patches (a cheap, strong blur that needs no RenderScript).
     */
    private fun blur(source: Bitmap, region: Rect): Bitmap {
        val width = region.width()
        val height = region.height()
        val factor = max(2, min(width, height) / BLUR_RESOLUTION)
        val crop = Bitmap.createBitmap(source, region.left, region.top, width, height)
        val tiny = Bitmap.createScaledBitmap(crop, max(1, width / factor), max(1, height / factor), true)
        if (crop !== source) crop.recycle()
        val mid = Bitmap.createScaledBitmap(tiny, max(1, width / max(1, factor / 4)), max(1, height / max(1, factor / 4)), true)
        tiny.recycle()
        val full = Bitmap.createScaledBitmap(mid, width, height, true)
        if (mid !== full) mid.recycle()
        return full
    }
}
