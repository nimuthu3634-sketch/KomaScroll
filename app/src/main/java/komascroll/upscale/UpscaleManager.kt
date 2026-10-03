package komascroll.upscale

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import komascroll.lab.LabPreferences
import komascroll.upscale.engine.UpscaleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Upscales the page being read plus the next [LOOKAHEAD] pages in the background, one at a time,
 * and keeps the results in [UpscaleCache]. Page holders read [stateFlow] to show progress and
 * [upscaledStream] to display the result.
 */
class UpscaleManager(
    private val context: Context,
    private val preferences: LabPreferences,
    private val engine: UpscaleEngine,
    private val cache: UpscaleCache,
) {

    sealed interface State {
        data object Idle : State
        data object Waiting : State
        data class Running(val progress: Float, val scale: Int) : State
        data class Done(val scale: Int) : State
        data class Skipped(val reason: SkipReason) : State
        data class Failed(val reason: UpscaleEngine.FailureReason?) : State
        data object Cancelled : State
    }

    enum class SkipReason { ANIMATED, TOO_WIDE, TOO_LARGE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Native inference is serialized anyway; one lane keeps the current page first in line. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val engineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val states = ConcurrentHashMap<String, MutableStateFlow<State>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val userCancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var lastSelectedPage: ReaderPage? = null

    init {
        combine(
            preferences.upscaleEnabled().changes(),
            preferences.upscaleInReader().changes(),
            preferences.upscaleScale().changes(),
            preferences.upscaleOnlyWhenCharging().changes(),
        ) { _, _, _, _ -> }
            .drop(1)
            .onEach {
                cancelAll()
                // Settings changed, so earlier skip/failure decisions may no longer apply.
                states.values.forEach { state ->
                    if (state.value is State.Skipped || state.value is State.Failed) state.value = State.Idle
                }
                lastSelectedPage?.let(::onPageSelected)
            }
            .launchIn(scope)

        preferences.upscaleCacheSizeMb().changes()
            .drop(1)
            .onEach { cache.trim() }
            .launchIn(scope)
    }

    /** True when the Lab feature and the reader toggle are both on. */
    val isActive: Boolean
        get() = preferences.upscaleEnabled().get() && preferences.upscaleInReader().get()

    val scale: Int
        get() = if (preferences.upscaleScale().get() == 4) 4 else 2

    fun stateFlow(page: ReaderPage): StateFlow<State> = mutableState(keyFor(page)).asStateFlow()

    /** A stream of the upscaled page if one is cached and the feature is active, otherwise null. */
    fun upscaledStream(page: ReaderPage): (() -> InputStream)? {
        if (!isActive) return null
        val file = cache.get(keyFor(page)) ?: return null
        return { file.inputStream() }
    }

    /** Called by the reader whenever the visible page changes. */
    fun onPageSelected(page: ReaderPage) {
        val target = (page as? InsertPage)?.parent ?: page
        lastSelectedPage = target
        if (!isActive) return
        if (preferences.upscaleOnlyWhenCharging().get() && !isCharging()) return

        val pages = target.chapter.pages ?: return
        val start = pages.indexOfFirst { it === target }.takeIf { it >= 0 } ?: target.index.coerceAtLeast(0)
        val window = pages.drop(start).take(LOOKAHEAD + 1).associateBy { keyFor(it) }

        // Pages that scrolled out of the window are not worth finishing.
        jobs.keys.filter { it !in window }.forEach { jobs.remove(it)?.cancel() }

        window.forEach { (key, windowPage) -> enqueue(key, windowPage) }
    }

    /** Stops upscaling [page] for the rest of this reading session. */
    fun cancel(page: ReaderPage) {
        val key = keyFor(page)
        userCancelled += key
        jobs.remove(key)?.cancel()
        mutableState(key).value = State.Cancelled
    }

    /** Called when the reader closes: stops all work and frees the model's GPU memory. */
    fun onReaderClosed() {
        lastSelectedPage = null
        cancelAll()
        userCancelled.clear()
        states.clear()
        scope.launch(engineDispatcher) { engine.release() }
    }

    private fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun enqueue(key: String, page: ReaderPage) {
        if (key in userCancelled || jobs.containsKey(key)) return
        val state = mutableState(key)
        if (cache.contains(key)) {
            state.value = State.Done(scale)
            return
        }
        if (state.value is State.Skipped || state.value is State.Failed) return

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                state.value = State.Waiting
                page.statusFlow.first { it == Page.State.Ready }
                withContext(engineDispatcher) { upscale(key, page, state) }
            } catch (e: CancellationException) {
                if (state.value !is State.Done) {
                    state.value = if (key in userCancelled) State.Cancelled else State.Idle
                }
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Upscaling failed for $key" }
                state.value = State.Failed(null)
            } finally {
                jobs.remove(key, coroutineContext.job)
            }
        }
        jobs[key] = job
        job.start()
    }

    private suspend fun upscale(key: String, page: ReaderPage, state: MutableStateFlow<State>) {
        currentCoroutineContext().ensureActive()
        if (cache.contains(key)) {
            state.value = State.Done(scale)
            return
        }
        val streamFn = page.stream ?: return
        val factor = scale
        val bytes = streamFn().use { it.readBytes() }

        if (ImageUtil.isAnimatedAndSupported(Buffer().write(bytes))) {
            state.value = State.Skipped(SkipReason.ANIMATED)
            return
        }

        val decoder = ImageDecoder.newInstance(ByteArrayInputStream(bytes))
        if (decoder == null || decoder.width <= 0 || decoder.height <= 0) {
            decoder?.recycle()
            state.value = State.Failed(null)
            return
        }
        val width = decoder.width
        val height = decoder.height
        val maxWidth = preferences.upscaleMaxSourceWidth().get()
        val skip = when {
            maxWidth > 0 && width >= maxWidth -> SkipReason.TOO_WIDE
            width.toLong() * height * factor * factor > maxOutputPixels() -> SkipReason.TOO_LARGE
            else -> null
        }
        if (skip != null) {
            decoder.recycle()
            state.value = State.Skipped(skip)
            return
        }

        val source = decoder.decode()
        decoder.recycle()
        if (source == null) {
            state.value = State.Failed(null)
            return
        }

        val job = currentCoroutineContext().job
        state.value = State.Running(0f, factor)
        val result = try {
            engine.upscale(
                input = source,
                scale = factor,
                allowCpu = preferences.upscaleAllowCpu().get(),
                isActive = { job.isActive },
                onProgress = { state.value = State.Running(it, factor) },
            )
        } finally {
            source.recycle()
        }

        when (result) {
            is UpscaleEngine.Result.Success -> {
                try {
                    cache.put(key, result.bitmap)
                } finally {
                    result.bitmap.recycle()
                }
                state.value = State.Done(factor)
            }
            UpscaleEngine.Result.Cancelled -> {
                currentCoroutineContext().ensureActive()
                state.value = State.Idle
            }
            is UpscaleEngine.Result.Failed -> state.value = State.Failed(result.reason)
        }
    }

    private fun mutableState(key: String) = states.getOrPut(key) { MutableStateFlow(State.Idle) }

    private fun keyFor(page: ReaderPage): String {
        val target = (page as? InsertPage)?.parent ?: page
        return "${target.chapter.chapter.id}-${target.index}-x$scale"
    }

    private fun isCharging(): Boolean =
        context.getSystemService(BatteryManager::class.java)?.isCharging == true

    /** Caps the output bitmap so a single page never needs more than ~80 MB (or ~48 MB on low-RAM devices). */
    private fun maxOutputPixels(): Long {
        val lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        return if (lowRam) 12_000_000L else 20_000_000L
    }

    private companion object {
        /** How many pages after the current one are upscaled ahead of time. */
        const val LOOKAHEAD = 2
    }
}
