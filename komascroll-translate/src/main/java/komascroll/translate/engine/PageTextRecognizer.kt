package komascroll.translate.engine

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlin.math.min

/** Script of the source text; selects the ML Kit Text Recognition v2 model. */
enum class SourceScript { JAPANESE, CHINESE, KOREAN, LATIN }

/**
 * OCR through ML Kit Text Recognition v2 (Google Play services models, downloaded on demand).
 * Very tall pages (webtoon strips) are recognized in overlapping chunks so text stays legible
 * for the recognizer.
 */
class PageTextRecognizer(context: Context) {

    private val appContext = context.applicationContext
    private val recognizers = mutableMapOf<SourceScript, TextRecognizer>()

    class ModelUnavailableException(cause: Throwable? = null) :
        Exception("The text recognition model could not be installed", cause)

    /**
     * Makes sure the OCR model for [script] is installed, downloading it through Google Play
     * services if needed. [onDownloading] is called once if a download starts.
     */
    suspend fun ensureModel(script: SourceScript, onDownloading: () -> Unit) {
        val recognizer = recognizerFor(script)
        val client = ModuleInstall.getClient(appContext)
        try {
            if (client.areModulesAvailable(recognizer).await().areModulesAvailable()) return

            onDownloading()
            val finished = CompletableDeferred<Boolean>()
            val listener = InstallStatusListener { update ->
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> finished.complete(true)
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED,
                    -> finished.complete(false)
                    else -> Unit
                }
            }
            val request = ModuleInstallRequest.newBuilder()
                .addApi(recognizer)
                .setListener(listener)
                .build()
            try {
                val response = client.installModules(request).await()
                val installed = response.areModulesAlreadyInstalled() || finished.await()
                if (!installed) throw ModelUnavailableException()
            } finally {
                client.unregisterListener(listener)
            }
        } catch (e: ModelUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw ModelUnavailableException(e)
        }
    }

    suspend fun recognize(bitmap: Bitmap, script: SourceScript): List<OcrLine> {
        val recognizer = recognizerFor(script)
        val width = bitmap.width
        val height = bitmap.height
        if (height <= width * TALL_RATIO) return recognizeRegion(recognizer, bitmap, 0)

        val chunkHeight = (width * CHUNK_RATIO).toInt().coerceAtLeast(MIN_CHUNK_HEIGHT)
        val overlap = (chunkHeight * CHUNK_OVERLAP).toInt()
        val lines = mutableListOf<OcrLine>()
        var top = 0
        while (top < height) {
            val chunkBottom = min(top + chunkHeight, height)
            val chunk = Bitmap.createBitmap(bitmap, 0, top, width, chunkBottom - top)
            try {
                lines += recognizeRegion(recognizer, chunk, top)
            } finally {
                if (chunk !== bitmap) chunk.recycle()
            }
            if (chunkBottom >= height) break
            top = chunkBottom - overlap
        }
        return lines
    }

    fun close() {
        recognizers.values.forEach(TextRecognizer::close)
        recognizers.clear()
    }

    private suspend fun recognizeRegion(recognizer: TextRecognizer, bitmap: Bitmap, offsetY: Int): List<OcrLine> {
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val rect = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, Box(rect.left, rect.top + offsetY, rect.right, rect.bottom + offsetY))
            }
        }
    }

    @Synchronized
    private fun recognizerFor(script: SourceScript): TextRecognizer = recognizers.getOrPut(script) {
        when (script) {
            SourceScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            SourceScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            SourceScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            SourceScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    private companion object {
        /** Pages taller than this many widths are recognized in chunks. */
        const val TALL_RATIO = 2.2f
        const val CHUNK_RATIO = 1.5f
        const val CHUNK_OVERLAP = 0.15f
        const val MIN_CHUNK_HEIGHT = 400
    }
}
