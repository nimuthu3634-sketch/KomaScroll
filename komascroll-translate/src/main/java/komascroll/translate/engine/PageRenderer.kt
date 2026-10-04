package komascroll.translate.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [PageTranslation] onto a page image of any resolution: paints over the original text
 * where the bubble background is near-uniform (otherwise draws a readable plate), then typesets
 * the translation, auto-fitted to the bubble, in a comic-style font (Comic Neue, OFL).
 *
 * Glyphs the font lacks (e.g. CJK when translating into those languages) fall back to the system font.
 */
class PageRenderer(context: Context) {

    private val typeface: Typeface = runCatching {
        Typeface.createFromAsset(context.applicationContext.assets, "fonts/ComicNeue-Bold.ttf")
    }.getOrDefault(Typeface.DEFAULT_BOLD)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** Returns a new bitmap; [base] is not modified. */
    fun render(base: Bitmap, translation: PageTranslation): Bitmap {
        val output = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val scaleX = output.width / translation.imageWidth.toFloat()
        val scaleY = output.height / translation.imageHeight.toFloat()

        for (bubble in translation.bubbles) {
            if (bubble.translated.isBlank()) continue
            val textBox = bubble.textBox.toRectF(scaleX, scaleY)
            val layoutBox = bubble.layoutBox.toRectF(scaleX, scaleY)
            val charSize = if (bubble.vertical) textBox.width() else textBox.height()
            val pad = max(2f, min(charSize * 0.15f, 12f * scaleX))

            val fill = bubble.fillColor
            val textColor: Int
            if (fill != null) {
                // Repaint the text area with the sampled bubble colour, staying inside the bubble.
                val cover = RectF(textBox).apply { inset(-pad, -pad) }
                if (!cover.intersect(RectF(layoutBox).apply { inset(-pad, -pad) })) cover.set(textBox)
                fillPaint.color = fill
                canvas.drawRoundRect(cover, pad, pad, fillPaint)
                textColor = if (BackgroundAnalyzer.luminance(fill) > 0.5f) Color.BLACK else Color.WHITE
            } else {
                // Busy background: keep the art but put the translation on a light plate.
                fillPaint.color = PLATE_COLOR
                canvas.drawRoundRect(RectF(layoutBox), pad * 2, pad * 2, fillPaint)
                textColor = Color.BLACK
            }

            drawFitted(canvas, bubble.translated, layoutBox, textColor, output.width)
        }
        return output
    }

    private fun drawFitted(canvas: Canvas, text: String, area: RectF, color: Int, pageWidth: Int) {
        val maxWidth = (area.width() * 0.9f).toInt().coerceAtLeast(1)
        val maxHeight = area.height() * 0.9f
        val minSize = max(MIN_TEXT_SIZE, pageWidth / 110f)
        val maxSize = max(minSize, min(area.height() * 0.45f, pageWidth / 18f))

        textPaint.typeface = typeface
        textPaint.color = color

        // Binary search for the largest size whose wrapped layout fits the bubble.
        var low = minSize
        var high = maxSize
        var best = layoutFor(text, minSize, maxWidth)
        repeat(SEARCH_STEPS) {
            val mid = (low + high) / 2
            val candidate = layoutFor(text, mid, maxWidth)
            if (candidate.height <= maxHeight) {
                best = candidate
                low = mid
            } else {
                high = mid
            }
        }

        canvas.save()
        val left = area.centerX() - maxWidth / 2f
        val top = area.centerY() - best.height / 2f
        canvas.translate(left, top)
        best.draw(canvas)
        canvas.restore()
    }

    private fun layoutFor(text: String, size: Float, width: Int): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.95f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()
    }

    private fun Box.toRectF(scaleX: Float, scaleY: Float) =
        RectF(left * scaleX, top * scaleY, right * scaleX, bottom * scaleY)

    private companion object {
        const val MIN_TEXT_SIZE = 9f
        const val SEARCH_STEPS = 8
        val PLATE_COLOR = Color.argb(235, 255, 255, 255)
    }
}
