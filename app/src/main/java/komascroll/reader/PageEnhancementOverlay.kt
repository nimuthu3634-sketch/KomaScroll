package komascroll.reader

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.dpToPx
import komascroll.i18n.KSR
import komascroll.translate.TranslationManager
import komascroll.upscale.UpscaleManager
import komascroll.upscale.engine.UpscaleEngine
import kotlinx.coroutines.flow.combine
import tachiyomi.core.common.i18n.stringResource
import uy.kohesive.injekt.injectLazy
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Glue between a reader page holder and the KomaScroll Lab page features (AI upscaling, live
 * translation). Chooses which version of the page to show (translated > upscaled > original),
 * asks the holder to re-render when a better version appears, and shows small corner badges:
 * translation status top-left, upscaling status top-right. Tapping a badge while work is in
 * progress cancels it for that page.
 */
class PageEnhancementOverlay(private val container: FrameLayout) {

    private val upscaleManager: UpscaleManager by injectLazy()
    private val translationManager: TranslationManager by injectLazy()

    private enum class Shown { ORIGINAL, UPSCALED, TRANSLATED }

    private var shown = Shown.ORIGINAL
    private var shownTranslationBase: TranslationManager.Base? = null

    private var upscaleBadge: TextView? = null
    private var translationBadge: TextView? = null

    /** Stream of the best available enhanced version of [page], or null to show the original. */
    fun streamFor(page: ReaderPage): (() -> InputStream)? {
        translationManager.translatedStream(page)?.let { (stream, base) ->
            shown = Shown.TRANSLATED
            shownTranslationBase = base
            return stream
        }
        shownTranslationBase = null
        upscaleManager.upscaledStream(page)?.let {
            shown = Shown.UPSCALED
            return it
        }
        shown = Shown.ORIGINAL
        return null
    }

    /**
     * Follows both features' state for [page] until cancelled, calling [rerender] when a better
     * version than the one displayed becomes available. Must run on the main thread.
     */
    suspend fun track(page: ReaderPage, rerender: suspend () -> Unit) {
        combine(upscaleManager.stateFlow(page), translationManager.stateFlow(page), ::Pair)
            .collect { (upscale, translation) ->
                val betterVersion = when {
                    translation is TranslationManager.State.Done ->
                        shown != Shown.TRANSLATED || translation.base != shownTranslationBase
                    upscale is UpscaleManager.State.Done -> shown == Shown.ORIGINAL
                    else -> false
                }
                if (betterVersion) rerender()
                renderUpscaleBadge(page, upscale)
                renderTranslationBadge(page, translation)
            }
    }

    /** Hides the badges, e.g. when a recycled holder is rebound to another page. */
    fun reset() {
        shown = Shown.ORIGINAL
        shownTranslationBase = null
        upscaleBadge?.isVisible = false
        translationBadge?.isVisible = false
    }

    private fun renderUpscaleBadge(page: ReaderPage, state: UpscaleManager.State) {
        val context = container.context
        val showingUpscaled = shown == Shown.UPSCALED ||
            (shown == Shown.TRANSLATED && shownTranslationBase != TranslationManager.Base.ORIGINAL)
        val running = state is UpscaleManager.State.Running && !showingUpscaled
        val text = when {
            showingUpscaled -> context.stringResource(KSR.strings.upscale_badge_done, upscaleManager.scale)
            state is UpscaleManager.State.Running -> context.stringResource(
                KSR.strings.upscale_badge_running,
                state.scale,
                (state.progress * 100).roundToInt(),
            )
            state is UpscaleManager.State.Failed -> context.stringResource(
                if (state.reason == UpscaleEngine.FailureReason.NO_BACKEND) {
                    KSR.strings.upscale_badge_no_backend
                } else {
                    KSR.strings.upscale_badge_failed
                },
            )
            else -> null
        }
        upscaleBadge = showBadge(upscaleBadge, Gravity.TOP or Gravity.END, text, KSR.strings.upscale_badge_description) {
            if (running) upscaleManager.cancel(page)
        }
    }

    private fun renderTranslationBadge(page: ReaderPage, state: TranslationManager.State) {
        val context = container.context
        val text = when {
            shown == Shown.TRANSLATED -> context.stringResource(KSR.strings.translate_badge_done)
            state is TranslationManager.State.Working -> context.stringResource(stageLabel(state.stage))
            state is TranslationManager.State.Failed -> context.stringResource(failureLabel(state.reason))
            else -> null
        }
        val running = state is TranslationManager.State.Working && shown != Shown.TRANSLATED
        translationBadge = showBadge(
            translationBadge,
            Gravity.TOP or Gravity.START,
            text,
            KSR.strings.translate_badge_description,
        ) {
            if (running) translationManager.cancel(page)
        }
    }

    private fun showBadge(
        existing: TextView?,
        gravity: Int,
        text: String?,
        description: StringResource,
        onClick: () -> Unit,
    ): TextView? {
        if (text == null) {
            existing?.isVisible = false
            return existing
        }
        val badge = existing ?: createBadge(gravity, description)
        badge.text = text
        badge.setOnClickListener { onClick() }
        badge.isVisible = true
        badge.bringToFront()
        return badge
    }

    private fun createBadge(gravity: Int, description: StringResource): TextView {
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
            contentDescription = context.stringResource(description)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity,
            ).apply {
                val margin = 8.dpToPx
                setMargins(margin, margin, margin, margin)
            }
            container.addView(this, params)
        }
    }

    private fun stageLabel(stage: TranslationManager.Stage): StringResource = when (stage) {
        TranslationManager.Stage.DOWNLOADING_OCR_MODEL -> KSR.strings.translate_stage_ocr_model
        TranslationManager.Stage.RECOGNIZING -> KSR.strings.translate_stage_recognizing
        TranslationManager.Stage.DOWNLOADING_TRANSLATION_MODEL -> KSR.strings.translate_stage_translation_model
        TranslationManager.Stage.TRANSLATING -> KSR.strings.translate_stage_translating
        TranslationManager.Stage.RENDERING -> KSR.strings.translate_stage_rendering
    }

    private fun failureLabel(reason: TranslationManager.FailureReason): StringResource = when (reason) {
        TranslationManager.FailureReason.OCR_MODEL_UNAVAILABLE -> KSR.strings.translate_error_ocr_model
        TranslationManager.FailureReason.TRANSLATION_MODEL_DOWNLOAD -> KSR.strings.translate_error_translation_model
        TranslationManager.FailureReason.NO_API_KEY -> KSR.strings.translate_error_no_key
        TranslationManager.FailureReason.INVALID_API_KEY -> KSR.strings.translate_error_invalid_key
        TranslationManager.FailureReason.QUOTA_EXCEEDED -> KSR.strings.translate_error_quota
        TranslationManager.FailureReason.NETWORK -> KSR.strings.translate_error_network
        TranslationManager.FailureReason.UNSUPPORTED_LANGUAGE -> KSR.strings.translate_error_language
        TranslationManager.FailureReason.UNSUPPORTED_IMAGE,
        TranslationManager.FailureReason.OTHER,
        -> KSR.strings.translate_error_generic
    }

    private companion object {
        val BADGE_BACKGROUND = Color.argb(160, 0, 0, 0)
    }
}
