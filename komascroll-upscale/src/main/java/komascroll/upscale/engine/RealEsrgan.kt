package komascroll.upscale.engine

import android.content.res.AssetManager
import android.graphics.Bitmap

/**
 * Thin JNI binding to the native Real-ESRGAN (realesr-animevideov3) runner in `realesrgan_jni.cpp`.
 *
 * All calls are blocking and serialized natively; call them from a background thread.
 * Use [UpscaleEngine] instead of this object directly.
 */
internal object RealEsrgan {

    const val RESULT_OK = 0
    const val RESULT_CANCELLED = 1
    const val RESULT_NOT_LOADED = -1
    const val RESULT_BAD_BITMAP = -2
    const val RESULT_INFERENCE_FAILED = -3

    /** Called from native code after every tile. Return false to cancel. */
    fun interface ProgressCallback {
        fun onProgress(doneTiles: Int, totalTiles: Int): Boolean
    }

    val isLibraryLoaded: Boolean by lazy {
        try {
            System.loadLibrary("komascroll_upscale")
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    external fun nativeInitGpu(): Int

    @JvmStatic
    external fun nativeGpuName(): String?

    @JvmStatic
    external fun nativeLoad(assetManager: AssetManager, scale: Int, useGpu: Boolean): Boolean

    @JvmStatic
    external fun nativeSuggestTileSize(): Int

    @JvmStatic
    external fun nativeRelease()

    /** [input] and [output] must be ARGB_8888; [output] must be exactly `scale` times larger. */
    @JvmStatic
    external fun nativeUpscale(input: Bitmap, output: Bitmap, tileSize: Int, callback: ProgressCallback): Int
}
