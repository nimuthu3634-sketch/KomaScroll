package komascroll.upscale.reader

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.dpToPx
import komascroll.i18n.KSR
import komascroll.upscale.UpscaleManager
import komascroll.upscale.engine.UpscaleEngine
import tachiyomi.core.common.i18n.stringResource
import uy.kohesive.injekt.injectLazy
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Glue between a reader page holder and [UpscaleManager]: picks the upscaled stream when one is
 * cached, re-renders the page when upscaling finishes, and shows a small corner badge with the state.
 * Tapping the badge while a page is being upscaled cancels it.
 */
class UpscaleBadge(private val container: FrameLayout) {

    private val manager: UpscaleManager by injectLazy()

    private var badge: TextView? = null

    /** Whether the image currently displayed by the holder is the upscaled one. */
    private var showingUpscaled = false

    /** Returns the stream of the upscaled page if available, remembering which version is shown. */
    fun upscaledStream(page: ReaderPage): (() -> InputStream)? {
        val stream = manager.upscaledStream(page)
        showingUpscaled = stream != null
        return stream
    }

    /**
     * Follows the upscaling state of [page] until cancelled, calling [onUpscaled] when a newer,
     * upscaled image becomes available. Must run on the main thread.
     */
    suspend fun track(page: ReaderPage, onUpscaled: suspend () -> Unit) {
        manager.stateFlow(page).collect { state ->
            if (state is UpscaleManager.State.Done && !showingUpscaled) onUpscaled()
            render(page, state)
        }
    }

    /** Hides the badge, e.g. when a recycled holder is rebound to another page. */
    fun reset() {
        showingUpscaled = false
        badge?.isVisible = false
    }

    private fun render(page: ReaderPage, state: UpscaleManager.State) {
        val context = container.context
        val text = when {
            showingUpscaled -> context.stringResource(KSR.strings.upscale_badge_done, manager.scale)
            state is UpscaleManager.State.Running ->
                context.stringResource(
                    KSR.strings.upscale_badge_running,
                    state.scale,
                    (state.progress * 100).roundToInt(),
                )
            state is UpscaleManager.State.Failed ->
                if (state.reason == UpscaleEngine.FailureReason.NO_BACKEND) {
                    context.stringResource(KSR.strings.upscale_badge_no_backend)
                } else {
                    context.stringResource(KSR.strings.upscale_badge_failed)
                }
            else -> null
        }
        if (text == null) {
            badge?.isVisible = false
            return
        }

        val view = badge ?: createBadge().also { badge = it }
        view.text = text
        val running = state is UpscaleManager.State.Running && !showingUpscaled
        if (running) {
            view.setOnClickListener { manager.cancel(page) }
        } else {
            view.setOnClickListener(null)
            view.isClickable = false
        }
        view.isVisible = true
        view.bringToFront()
    }

    private fun createBadge(): TextView {
        val context = container.context
        return TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
            val horizontal = 8.dpToPx
            val vertical = 3.dpToPx
            setPadding(horizontal, vertical, horizontal, vertical)
            background = GradientDrawable().apply {
                cornerRadius = 10.dpToPx.toFloat()
                setColor(BADGE_BACKGROUND)
            }
            contentDescription = context.stringResource(KSR.strings.upscale_badge_description)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                val margin = 8.dpToPx
                setMargins(margin, margin, margin, margin)
            }
            container.addView(this, params)
        }
    }

    private companion object {
        val BADGE_BACKGROUND = Color.argb(160, 0, 0, 0)
    }
}
