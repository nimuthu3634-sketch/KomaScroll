package komascroll.translate.engine

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * The per-page translation pipeline: detect text regions → OCR → group into bubbles → translate →
 * analyse each bubble's background. Rendering is separate ([render]) so a cached [PageTranslation]
 * can be redrawn onto any version of the page (e.g. an AI-upscaled one) without redoing OCR.
 *
 * All methods are blocking/suspending and must run off the main thread.
 */
class PageTranslationEngine(context: Context) {

    enum class Stage { DOWNLOADING_OCR_MODEL, RECOGNIZING, DOWNLOADING_TRANSLATION_MODEL, TRANSLATING }

    private val recognizer = PageTextRecognizer(context)
    private val renderer = PageRenderer(context)

    /** [bitmap] must be a software bitmap (not HARDWARE) so its pixels can be read. */
    suspend fun analyze(
        bitmap: Bitmap,
        sourceLanguage: String,
        targetLanguage: String,
        translator: TextTranslator,
        onStage: (Stage) -> Unit,
    ): PageTranslation {
        val script = scriptFor(sourceLanguage)
        recognizer.ensureModel(script) { onStage(Stage.DOWNLOADING_OCR_MODEL) }

        onStage(Stage.RECOGNIZING)
        val groups = BubbleGrouper.group(recognizer.recognize(bitmap, script))

        val bubbles = if (groups.isEmpty()) {
            emptyList()
        } else {
            onStage(Stage.TRANSLATING)
            val translations = translator.translate(groups.map { it.text }, sourceLanguage, targetLanguage)
            groups.mapIndexed { index, group -> analyzeBubble(bitmap, group, translations.getOrElse(index) { "" }) }
        }

        return PageTranslation(
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            engine = translator.id,
            bubbles = bubbles,
        )
    }

    /** Draws [translation] onto [base], which may be any resolution of the analysed page. */
    fun render(base: Bitmap, translation: PageTranslation): Bitmap = renderer.render(base, translation)

    fun close() = recognizer.close()

    private fun analyzeBubble(bitmap: Bitmap, group: BubbleGrouper.Group, translation: String): TranslatedBubble {
        val sizes = group.lines.map { if (group.vertical) it.box.width else it.box.height }.sorted()
        val charSize = sizes[sizes.size / 2].coerceAtLeast(1)
        val ring = (charSize / 2).coerceIn(4, 40)
        val maxExpand = (charSize * 4).coerceIn(24, 400)

        val regionBox = group.box.expand(maxExpand + ring).clampTo(bitmap.width, bitmap.height)
        val pixels = IntArray(regionBox.width * regionBox.height)
        bitmap.getPixels(pixels, 0, regionBox.width, regionBox.left, regionBox.top, regionBox.width, regionBox.height)
        val region = PixelRegion(pixels, regionBox.width, regionBox.height, regionBox.left, regionBox.top)

        val sample = BackgroundAnalyzer.sampleRing(region, group.box, ring)
        return if (sample != null && sample.isUniform) {
            val interior = BackgroundAnalyzer.estimateInterior(region, group.box, sample.color, maxExpand)
            val margin = charSize / 3
            val layout = Box(interior.left + margin, interior.top + margin, interior.right - margin, interior.bottom - margin)
                .union(group.box)
            TranslatedBubble(group.box, layout, group.vertical, group.text, translation, sample.color)
        } else {
            val layout = group.box.expand(charSize / 2).clampTo(bitmap.width, bitmap.height)
            TranslatedBubble(group.box, layout, group.vertical, group.text, translation, null)
        }
    }

    companion object {
        /** Source languages offered for OCR, as BCP-47 tags. */
        val SOURCE_LANGUAGES = listOf("ja", "zh", "ko", "en")

        /** Target languages supported by on-device translation, as BCP-47 tags. */
        fun targetLanguages(): List<String> = TranslateLanguage.getAllLanguages().sorted()

        fun scriptFor(sourceLanguage: String): SourceScript = when (sourceLanguage.substringBefore('-').lowercase()) {
            "ja" -> SourceScript.JAPANESE
            "zh" -> SourceScript.CHINESE
            "ko" -> SourceScript.KOREAN
            else -> SourceScript.LATIN
        }
    }
}
