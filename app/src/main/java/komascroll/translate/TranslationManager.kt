package komascroll.translate

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import komascroll.core.LruFileCache
import komascroll.core.SecretStore
import komascroll.lab.LabPreferences
import komascroll.translate.engine.DeepLTextTranslator
import komascroll.translate.engine.MlKitTextTranslator
import komascroll.translate.engine.PageTextRecognizer
import komascroll.translate.engine.PageTranslation
import komascroll.translate.engine.PageTranslationEngine
import komascroll.translate.engine.TextTranslator
import komascroll.translate.engine.TranslationException
import komascroll.upscale.UpscaleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates the page being read plus the next [LOOKAHEAD] pages in the background.
 *
 * Results are cached in two layers: the analysis ([PageTranslation]: bubble boxes, original and
 * translated text, background colours) as JSON, and rendered pages as JPEG per base image. When
 * an AI-upscaled version of a page appears, the cached analysis is redrawn onto it without
 * running OCR or translation again.
 */
class TranslationManager(
    private val context: Context,
    private val preferences: LabPreferences,
    private val secrets: SecretStore,
    private val upscaleManager: UpscaleManager,
    private val network: NetworkHelper,
    private val json: Json,
) {

    sealed interface State {
        data object Idle : State
        data object Waiting : State
        data class Working(val stage: Stage) : State
        data class Done(val base: Base) : State
        data object NoText : State
        data class Failed(val reason: FailureReason) : State
        data object Cancelled : State
    }

    enum class Stage { DOWNLOADING_OCR_MODEL, RECOGNIZING, DOWNLOADING_TRANSLATION_MODEL, TRANSLATING, RENDERING }

    /** Which version of the page a translation was drawn onto. */
    enum class Base(val tag: String) { ORIGINAL("orig"), UPSCALED_2X("up2"), UPSCALED_4X("up4") }

    enum class FailureReason {
        OCR_MODEL_UNAVAILABLE,
        TRANSLATION_MODEL_DOWNLOAD,
        NO_API_KEY,
        INVALID_API_KEY,
        QUOTA_EXCEEDED,
        NETWORK,
        UNSUPPORTED_LANGUAGE,
        UNSUPPORTED_IMAGE,
        OTHER,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine by lazy { PageTranslationEngine(context) }

    /** OCR, translation and rendering are memory-heavy: one page at a time. */
    private val engineMutex = Mutex()

    private val cache = LruFileCache(File(context.cacheDir, "komascroll_translate")) {
        preferences.translateCacheSizeMb().get().toLong() * 1024 * 1024
    }

    private val states = ConcurrentHashMap<String, MutableStateFlow<State>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val userCancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var lastSelectedPage: ReaderPage? = null

    init {
        combine(
            preferences.translateEnabled().changes(),
            preferences.translateInReader().changes(),
            preferences.translateSourceLanguage().changes(),
            preferences.translateTargetLanguage().changes(),
            preferences.translateEngine().changes(),
        ) { _, _, _, _, _ -> }
            .drop(1)
            .onEach {
                cancelAll()
                states.values.forEach { state ->
                    if (state.value !is State.Done) state.value = State.Idle
                }
                lastSelectedPage?.let(::onPageSelected)
            }
            .launchIn(scope)

        preferences.translateCacheSizeMb().changes()
            .drop(1)
            .onEach { cache.trim() }
            .launchIn(scope)
    }

    /** True when the Lab feature and the reader toggle are both on. */
    val isActive: Boolean
        get() = preferences.translateEnabled().get() && preferences.translateInReader().get()

    fun stateFlow(page: ReaderPage): StateFlow<State> = mutableState(keyFor(page)).asStateFlow()

    /**
     * The best cached rendering of [page] (preferring one drawn on the upscaled page) and the base
     * it was drawn on, or null if none exists or the feature is off.
     */
    fun translatedStream(page: ReaderPage): Pair<() -> InputStream, Base>? {
        if (!isActive) return null
        val key = keyFor(page)
        val upscaledBase = if (upscaleManager.upscaledStream(page) != null) upscaledBase() else null
        for (base in listOfNotNull(upscaledBase, Base.ORIGINAL)) {
            val file = cache.get(renderName(key, base)) ?: continue
            return Pair({ file.inputStream() }, base)
        }
        return null
    }

    /** The cached analysis for [page] (bubble positions and texts), if it was translated. */
    fun translationFor(page: ReaderPage): PageTranslation? {
        if (!isActive) return null
        val text = cache.readText(analysisName(keyFor(page))) ?: return null
        return runCatching { json.decodeFromString<PageTranslation>(text) }.getOrNull()
    }

    /** Called by the reader whenever the visible page changes. */
    fun onPageSelected(page: ReaderPage) {
        val target = (page as? InsertPage)?.parent ?: page
        lastSelectedPage = target
        if (!isActive) return

        val pages = target.chapter.pages ?: return
        val start = pages.indexOfFirst { it === target }.takeIf { it >= 0 } ?: target.index.coerceAtLeast(0)
        val window = pages.drop(start).take(LOOKAHEAD + 1).associateBy { keyFor(it) }

        jobs.keys.filter { it !in window }.forEach { jobs.remove(it)?.cancel() }
        window.forEach { (key, windowPage) -> enqueue(key, windowPage) }
    }

    /** Stops translating [page] for the rest of this reading session. */
    fun cancel(page: ReaderPage) {
        val key = keyFor(page)
        userCancelled += key
        jobs.remove(key)?.cancel()
        mutableState(key).value = State.Cancelled
    }

    fun onReaderClosed() {
        lastSelectedPage = null
        cancelAll()
        userCancelled.clear()
        states.clear()
    }

    /** Size of the translation cache in bytes. */
    fun cacheSize(): Long = cache.size()

    /** Deletes all cached translations; returns the number of files removed. */
    fun clearCache(): Int = cache.clear()

    private fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun enqueue(key: String, page: ReaderPage) {
        if (key in userCancelled || jobs.containsKey(key)) return
        val state = mutableState(key)
        if (state.value is State.Failed || state.value is State.NoText) return

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                state.value = State.Waiting
                page.statusFlow.first { it == Page.State.Ready }
                val analysis = analysisFor(key, page, state) ?: return@launch
                if (analysis.bubbles.isEmpty()) {
                    state.value = State.NoText
                    return@launch
                }
                renderOnBestBase(key, page, analysis, state)

                // Redraw onto the upscaled page as soon as one becomes available.
                upscaleManager.stateFlow(page)
                    .filterIsInstance<UpscaleManager.State.Done>()
                    .collect { renderOnBestBase(key, page, analysis, state) }
            } catch (e: CancellationException) {
                if (state.value !is State.Done) {
                    state.value = if (key in userCancelled) State.Cancelled else State.Idle
                }
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Translation failed for $key" }
                state.value = State.Failed(failureReason(e))
            } finally {
                jobs.remove(key, coroutineContext.job)
            }
        }
        jobs[key] = job
        job.start()
    }

    private suspend fun analysisFor(key: String, page: ReaderPage, state: MutableStateFlow<State>): PageTranslation? {
        cache.readText(analysisName(key))?.let { text ->
            runCatching { json.decodeFromString<PageTranslation>(text) }.getOrNull()?.let { return it }
        }
        val streamFn = page.stream ?: return null
        val translator = createTranslator(state) ?: run {
            state.value = State.Failed(FailureReason.NO_API_KEY)
            return null
        }

        return engineMutex.withLock {
            val bytes = withContext(Dispatchers.IO) { streamFn().use { it.readBytes() } }
            if (ImageUtil.isAnimatedAndSupported(Buffer().write(bytes))) {
                state.value = State.Failed(FailureReason.UNSUPPORTED_IMAGE)
                return@withLock null
            }
            val bitmap = decode(bytes) ?: run {
                state.value = State.Failed(FailureReason.UNSUPPORTED_IMAGE)
                return@withLock null
            }
            val analysis = try {
                engine.analyze(
                    bitmap = bitmap,
                    sourceLanguage = preferences.translateSourceLanguage().get(),
                    targetLanguage = preferences.translateTargetLanguage().get(),
                    translator = translator,
                ) { stage ->
                    state.value = State.Working(
                        when (stage) {
                            PageTranslationEngine.Stage.DOWNLOADING_OCR_MODEL -> Stage.DOWNLOADING_OCR_MODEL
                            PageTranslationEngine.Stage.RECOGNIZING -> Stage.RECOGNIZING
                            PageTranslationEngine.Stage.DOWNLOADING_TRANSLATION_MODEL -> Stage.DOWNLOADING_TRANSLATION_MODEL
                            PageTranslationEngine.Stage.TRANSLATING -> Stage.TRANSLATING
                        },
                    )
                }
            } finally {
                bitmap.recycle()
            }
            cache.writeText(analysisName(key), json.encodeToString(PageTranslation.serializer(), analysis))
            analysis
        }
    }

    private suspend fun renderOnBestBase(
        key: String,
        page: ReaderPage,
        analysis: PageTranslation,
        state: MutableStateFlow<State>,
    ) {
        val upscaledStream = upscaleManager.upscaledStream(page)
        val base = if (upscaledStream != null) upscaledBase() else Base.ORIGINAL
        if (cache.contains(renderName(key, base))) {
            state.value = State.Done(base)
            return
        }
        val streamFn = upscaledStream ?: page.stream ?: return

        val rendered = engineMutex.withLock {
            if (state.value !is State.Done) state.value = State.Working(Stage.RENDERING)
            val bytes = withContext(Dispatchers.IO) { streamFn().use { it.readBytes() } }
            val bitmap = decode(bytes) ?: return@withLock false
            try {
                val output = engine.render(bitmap, analysis)
                try {
                    cache.write(renderName(key, base)) { output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                } finally {
                    output.recycle()
                }
            } finally {
                bitmap.recycle()
            }
            true
        }
        if (rendered) {
            state.value = State.Done(base)
        } else if (state.value !is State.Done) {
            state.value = State.Failed(FailureReason.UNSUPPORTED_IMAGE)
        }
    }

    private fun createTranslator(state: MutableStateFlow<State>): TextTranslator? =
        when (preferences.translateEngine().get()) {
            DeepLTextTranslator.ID -> secrets.get(SecretStore.DEEPL_API_KEY)
                ?.takeIf { it.isNotBlank() }
                ?.let { DeepLTextTranslator(network.client, it) }
            else -> MlKitTextTranslator(
                requireWifiForDownload = { preferences.translateModelsOnWifiOnly().get() },
                onModelDownload = { state.value = State.Working(Stage.DOWNLOADING_TRANSLATION_MODEL) },
            )
        }

    /** Decodes to a mutable-safe software bitmap (OCR and rendering need pixel access). */
    private fun decode(bytes: ByteArray): Bitmap? {
        val decoder = ImageDecoder.newInstance(ByteArrayInputStream(bytes)) ?: return null
        val decoded = try {
            decoder.decode()
        } finally {
            decoder.recycle()
        }
        val bitmap = decoded ?: return null
        if (bitmap.config != Bitmap.Config.HARDWARE) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
    }

    private fun failureReason(e: Throwable): FailureReason = when (e) {
        is PageTextRecognizer.ModelUnavailableException -> FailureReason.OCR_MODEL_UNAVAILABLE
        is TranslationException -> when (e.reason) {
            TranslationException.Reason.UNSUPPORTED_LANGUAGE -> FailureReason.UNSUPPORTED_LANGUAGE
            TranslationException.Reason.MODEL_DOWNLOAD_FAILED -> FailureReason.TRANSLATION_MODEL_DOWNLOAD
            TranslationException.Reason.INVALID_API_KEY -> FailureReason.INVALID_API_KEY
            TranslationException.Reason.QUOTA_EXCEEDED -> FailureReason.QUOTA_EXCEEDED
            TranslationException.Reason.NETWORK -> FailureReason.NETWORK
        }
        else -> FailureReason.OTHER
    }

    private fun upscaledBase() = if (upscaleManager.scale == 4) Base.UPSCALED_4X else Base.UPSCALED_2X

    private fun mutableState(key: String) = states.getOrPut(key) { MutableStateFlow(State.Idle) }

    /** One key per page, language pair and engine; the rendered base is appended per file. */
    private fun keyFor(page: ReaderPage): String {
        val target = (page as? InsertPage)?.parent ?: page
        val source = preferences.translateSourceLanguage().get()
        val destination = preferences.translateTargetLanguage().get()
        val engineId = preferences.translateEngine().get()
        return "${target.chapter.chapter.id}-${target.index}-$source-$destination-$engineId"
    }

    private fun analysisName(key: String) = "$key.json"

    private fun renderName(key: String, base: Base) = "$key-${base.tag}.jpg"

    private companion object {
        const val LOOKAHEAD = 2
        const val JPEG_QUALITY = 92
    }
}
