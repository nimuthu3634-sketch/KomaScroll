package komascroll.upscale.engine

import android.content.Context
import android.graphics.Bitmap

/**
 * Real-ESRGAN anime upscaler backed by NCNN (Vulkan when available, optionally CPU).
 *
 * Every method blocks; call from a background thread. Native work is serialized, so a single
 * instance per process is enough.
 */
class UpscaleEngine(context: Context) {

    private val appContext = context.applicationContext
    private val guard = appContext.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE)

    sealed interface Support {
        /** A Vulkan GPU is available. */
        data class Gpu(val deviceName: String) : Support

        /** No usable GPU: upscaling only works with the (slow) CPU fallback. */
        data class CpuOnly(val reason: Reason) : Support

        /** The native library could not be loaded (e.g. unsupported ABI). */
        data object Unavailable : Support
    }

    enum class Reason {
        /** The device has no Vulkan driver/GPU usable by NCNN. */
        NO_VULKAN,

        /** A previous GPU initialization crashed the app, so the GPU path was switched off. */
        GPU_CRASHED,
    }

    sealed interface Result {
        class Success(val bitmap: Bitmap, val usedGpu: Boolean) : Result
        data object Cancelled : Result
        data class Failed(val reason: FailureReason) : Result
    }

    enum class FailureReason { NO_BACKEND, MODEL_LOAD, OUT_OF_MEMORY, INFERENCE }

    @Volatile
    private var support: Support? = null

    /** Probes the device once (initializing Vulkan) and caches the answer. */
    @Synchronized
    fun support(): Support {
        support?.let { return it }
        val probed = when {
            !RealEsrgan.isLibraryLoaded -> Support.Unavailable
            isGpuBlocked() -> Support.CpuOnly(Reason.GPU_CRASHED)
            else -> {
                val gpuCount = guarded { RealEsrgan.nativeInitGpu() }
                if (gpuCount > 0) {
                    Support.Gpu(RealEsrgan.nativeGpuName() ?: "Vulkan GPU")
                } else {
                    Support.CpuOnly(Reason.NO_VULKAN)
                }
            }
        }
        support = probed
        return probed
    }

    /** Allows the GPU path again after it was disabled by a crash. Takes effect on the next [support] call. */
    @Synchronized
    fun resetGpuCrashGuard() {
        guard.edit().clear().commit()
        support = null
    }

    /**
     * Upscales [input] by [scale] (2 or 4). [isActive] is polled after every tile; returning false cancels.
     * [onProgress] receives values in 0..1. The caller owns the returned bitmap.
     */
    fun upscale(
        input: Bitmap,
        scale: Int,
        allowCpu: Boolean,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Result {
        require(scale == 2 || scale == 4) { "Unsupported scale $scale" }

        val useGpu = when (support()) {
            is Support.Gpu -> true
            is Support.CpuOnly -> if (allowCpu) false else return Result.Failed(FailureReason.NO_BACKEND)
            Support.Unavailable -> return Result.Failed(FailureReason.NO_BACKEND)
        }

        val loaded = if (useGpu) {
            guarded { RealEsrgan.nativeLoad(appContext.assets, scale, true) }
        } else {
            RealEsrgan.nativeLoad(appContext.assets, scale, false)
        }
        if (!loaded) return Result.Failed(FailureReason.MODEL_LOAD)

        val source = if (input.config == Bitmap.Config.ARGB_8888) {
            input
        } else {
            input.copy(Bitmap.Config.ARGB_8888, false) ?: return Result.Failed(FailureReason.OUT_OF_MEMORY)
        }
        val output = try {
            Bitmap.createBitmap(source.width * scale, source.height * scale, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            if (source !== input) source.recycle()
            return Result.Failed(FailureReason.OUT_OF_MEMORY)
        }

        val code = RealEsrgan.nativeUpscale(source, output, 0) { done, total ->
            onProgress(done.toFloat() / total)
            isActive()
        }
        if (source !== input) source.recycle()

        return when (code) {
            RealEsrgan.RESULT_OK -> Result.Success(output, useGpu)
            RealEsrgan.RESULT_CANCELLED -> {
                output.recycle()
                Result.Cancelled
            }
            else -> {
                output.recycle()
                Result.Failed(FailureReason.INFERENCE)
            }
        }
    }

    /** Frees the loaded model and its GPU memory. The next [upscale] reloads it. */
    fun release() {
        if (RealEsrgan.isLibraryLoaded) RealEsrgan.nativeRelease()
    }

    private fun isGpuBlocked(): Boolean {
        if (guard.getBoolean(KEY_IN_PROGRESS, false)) {
            // The last GPU setup never finished, so the process died inside the Vulkan driver.
            guard.edit().putBoolean(KEY_BLOCKED, true).putBoolean(KEY_IN_PROGRESS, false).commit()
        }
        return guard.getBoolean(KEY_BLOCKED, false)
    }

    /** Marks GPU setup as in progress with a synchronous write, so a native crash is detectable later. */
    private inline fun <T> guarded(block: () -> T): T {
        guard.edit().putBoolean(KEY_IN_PROGRESS, true).commit()
        try {
            return block()
        } finally {
            guard.edit().putBoolean(KEY_IN_PROGRESS, false).commit()
        }
    }

    private companion object {
        const val GUARD_PREFS = "komascroll_upscale_engine"
        const val KEY_IN_PROGRESS = "gpu_setup_in_progress"
        const val KEY_BLOCKED = "gpu_blocked"
    }
}
