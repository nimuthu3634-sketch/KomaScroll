package komascroll.immersion

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.LruCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import komascroll.lab.LabPreferences
import komascroll.translate.engine.PageTextRecognizer
import komascroll.translate.engine.PageTranslationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.min

/**
 * Sound-effect haptics: recognizes onomatopoeia on the page being read (on-device OCR) and plays a
 * matching vibration — a thud for ドン, a fading blast for BOOM, a heartbeat for ドキドキ.
 *
 * Pages are analyzed one at a time off the main thread; the next page is analyzed ahead so its
 * effect plays as soon as it is shown. Each page vibrates at most once per reading session.
 */
class SfxHapticsManager(
    context: Context,
    private val preferences: LabPreferences,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recognizer = PageTextRecognizer(appContext)
    private val ocrMutex = Mutex()

    /** Analysis results; [Entry] wraps the nullable hit because LruCache cannot store nulls. */
    private class Entry(val hit: SfxHit?)

    private val results = LruCache<String, Entry>(MEMORY_ENTRIES)
    private val inFlight = mutableMapOf<String, Deferred<SfxHit?>>()
    private val played = mutableSetOf<String>()
    private var current: Job? = null

    /** Set when the OCR model cannot be installed, so pages are not retried for this session. */
    @Volatile
    private var unavailable = false

    private val vibrator: Vibrator? by lazy {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java)
        }
        vibrator?.takeIf { it.hasVibrator() }
    }

    private fun isActive(): Boolean =
        preferences.sfxHapticsEnabled().get() &&
            preferences.sfxHapticsInReader().get() &&
            !unavailable &&
            vibrator != null

    fun onPageSelected(page: ReaderPage) {
        current?.cancel()
        if (!isActive()) return
        val target = (page as? InsertPage)?.parent ?: page
        val key = keyFor(target)
        current = scope.launch {
            val hit = analyze(target) ?: return@launch
            val firstTime = synchronized(played) { played.add(key) }
            if (firstTime) vibrate(hit)
        }
        // Analyze the next page ahead of time so its effect is not delayed by OCR.
        target.chapter.pages?.getOrNull(target.index + 1)?.let { next ->
            scope.launch { analyze(next) }
        }
    }

    /** Plays [kind] at full strength so the user can feel the current intensity setting. */
    fun preview(kind: SfxKind) {
        vibrate(SfxHit(kind, strength = 1f, text = ""))
    }

    /** Whether the device can vibrate at all. */
    fun hasVibrator(): Boolean = vibrator != null

    fun onReaderClosed() {
        current?.cancel()
        synchronized(played) { played.clear() }
        vibrator?.cancel()
        unavailable = false
        scope.launch { ocrMutex.withLock { recognizer.close() } }
    }

    private suspend fun analyze(page: ReaderPage): SfxHit? {
        val key = keyFor(page)
        results.get(key)?.let { return it.hit }
        val deferred = synchronized(inFlight) {
            inFlight.getOrPut(key) {
                scope.async { detect(page, key) }.also { job ->
                    job.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(key) } }
                }
            }
        }
        return deferred.await()
    }

    private suspend fun detect(page: ReaderPage, key: String): SfxHit? {
        withTimeoutOrNull(READY_TIMEOUT_MS) { page.statusFlow.first { it == Page.State.Ready } } ?: return null
        val streamFn = page.stream ?: return null
        val language = preferences.sfxHapticsLanguage().get()
        val hit = ocrMutex.withLock {
            try {
                recognize(streamFn, language)
            } catch (e: PageTextRecognizer.ModelUnavailableException) {
                logcat(LogPriority.WARN, e) { "Text recognition model unavailable; SFX haptics paused" }
                unavailable = true
                return null
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "SFX analysis failed for $key" }
                null
            }
        }
        results.put(key, Entry(hit))
        return hit
    }

    private suspend fun recognize(streamFn: () -> InputStream, language: String): SfxHit? {
        val bytes = streamFn().use { it.readBytes() }
        if (ImageUtil.isAnimatedAndSupported(Buffer().write(bytes))) return null
        val bitmap = decode(bytes) ?: return null
        try {
            val script = PageTranslationEngine.scriptFor(language)
            recognizer.ensureModel(script) {}
            val lines = recognizer.recognize(bitmap, script)
            // Webtoon strips are much taller than a screen: measure text against one screenful.
            val reference = min(bitmap.height.toFloat(), bitmap.width * SCREEN_ASPECT)
            return SfxClassifier.strongest(lines.map { SfxCandidate(it.text, it.box.height / reference) })
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val decoder = ImageDecoder.newInstance(ByteArrayInputStream(bytes)) ?: return null
        if (decoder.width <= 0 || decoder.height <= 0) {
            decoder.recycle()
            return null
        }
        var sampleSize = 1
        while (decoder.width / (sampleSize * 2) >= OCR_WIDTH) sampleSize *= 2
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

    private fun vibrate(hit: SfxHit) {
        val vibrator = vibrator ?: return
        val intensity = preferences.sfxHapticsIntensity().get() / 100f
        val waveform = HapticPatterns.waveform(hit.kind, hit.strength, intensity)
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(waveform.timings, waveform.amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(waveform.timings, -1)
        }
        vibrator.vibrate(effect)
    }

    private fun keyFor(page: ReaderPage) = "${page.chapter.chapter.id}-${page.index}"

    private companion object {
        const val MEMORY_ENTRIES = 256
        const val READY_TIMEOUT_MS = 60_000L

        /** Pages are downscaled until narrower than twice this; effects are big, so it is plenty. */
        const val OCR_WIDTH = 900

        /** Height/width of a typical phone screen, used to size text on very tall pages. */
        const val SCREEN_ASPECT = 1.6f
    }
}
