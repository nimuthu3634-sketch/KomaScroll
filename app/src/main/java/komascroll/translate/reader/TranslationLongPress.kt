package komascroll.translate.reader

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.util.system.copyToClipboard
import komascroll.i18n.KSR
import komascroll.translate.TranslationManager
import tachiyomi.core.common.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Long-pressing a translated bubble shows its original and translated text.
 */
object TranslationLongPress {

    /**
     * Shows the bubble under ([x], [y]) (in [pageView] coordinates) if [page] has been translated.
     * Returns false when there is no translated bubble there, so the normal long-press menu runs.
     */
    fun handle(context: Context, page: ReaderPage, pageView: ReaderPageImageView, x: Float, y: Float): Boolean {
        val manager = Injekt.get<TranslationManager>()
        val translation = manager.translationFor(page) ?: return false
        val point = pageView.imageFractionAt(x, y) ?: return false
        val bubble = translation.bubbleAt(point.x, point.y) ?: return false

        val message = buildString {
            append(context.stringResource(KSR.strings.translate_bubble_original))
            append('\n')
            append(bubble.original)
            append("\n\n")
            append(context.stringResource(KSR.strings.translate_bubble_translation))
            append('\n')
            append(bubble.translated)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(context.stringResource(KSR.strings.translate_bubble_title))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(context.stringResource(KSR.strings.translate_bubble_copy)) { _, _ ->
                context.copyToClipboard(context.stringResource(KSR.strings.translate_bubble_original), bubble.original)
            }
            .show()
        return true
    }
}
