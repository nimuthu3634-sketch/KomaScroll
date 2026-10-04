package komascroll.library.wrapped

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import komascroll.core.KomaScrollBranding
import komascroll.i18n.KSR
import tachiyomi.core.common.i18n.stringResource
import java.io.File

/** Renders a Reading Wrapped summary as a shareable 1080×1350 image. */
object WrappedShareCard {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private const val MARGIN = 90f

    fun shareIntent(context: Context, summary: ReadingWrapped.Summary): Intent {
        val bitmap = render(context, summary)
        val directory = File(context.cacheDir, "komascroll_share").apply { mkdirs() }
        val file = File(directory, "wrapped-${summary.year}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val share = file.getUriCompat(context).toShareIntent(
            context,
            type = "image/png",
            message = context.stringResource(KSR.strings.wrapped_share_message, summary.year),
        )
        return Intent.createChooser(share, context.stringResource(KSR.strings.wrapped_share))
    }

    private fun render(context: Context, summary: ReadingWrapped.Summary): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint().apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                HEIGHT.toFloat(),
                KomaScrollBranding.SEED_COLOR,
                Color.rgb(0, 50, 45),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), background)

        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fun paint(size: Float, typeface: Typeface = Typeface.DEFAULT, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.alpha = alpha
            textSize = size
            this.typeface = typeface
        }

        var y = 170f
        canvas.drawText(context.stringResource(KSR.strings.wrapped_card_title, summary.year), MARGIN, y, paint(64f, bold))
        y += 60f
        canvas.drawText("KomaScroll", MARGIN, y, paint(36f, alpha = 190))

        val stats = listOf(
            summary.chaptersRead.toString() to context.stringResource(KSR.strings.wrapped_chapters),
            (summary.minutesRead / 60).toString() to context.stringResource(KSR.strings.wrapped_hours),
            summary.pagesRead.toString() to context.stringResource(KSR.strings.wrapped_pages),
            summary.longestStreak.toString() to context.stringResource(KSR.strings.wrapped_streak_days),
        )
        y += 110f
        stats.chunked(2).forEach { row ->
            row.forEachIndexed { column, (value, label) ->
                val x = MARGIN + column * (WIDTH - 2 * MARGIN) / 2
                canvas.drawText(value, x, y, paint(96f, bold))
                canvas.drawText(label, x, y + 52f, paint(34f, alpha = 200))
            }
            y += 200f
        }

        if (summary.topSeries.isNotEmpty()) {
            canvas.drawText(context.stringResource(KSR.strings.wrapped_top_series), MARGIN, y, paint(40f, bold))
            y += 60f
            summary.topSeries.take(3).forEachIndexed { index, series ->
                canvas.drawText("${index + 1}. ${ellipsize(series.name, 30)}", MARGIN, y, paint(38f))
                y += 54f
            }
            y += 30f
        }
        if (summary.topGenres.isNotEmpty()) {
            canvas.drawText(context.stringResource(KSR.strings.wrapped_top_genres), MARGIN, y, paint(40f, bold))
            y += 60f
            canvas.drawText(ellipsize(summary.topGenres.take(3).joinToString(" · ") { it.name }, 44), MARGIN, y, paint(38f))
        }
        return bitmap
    }

    private fun ellipsize(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1) + "…"
}
