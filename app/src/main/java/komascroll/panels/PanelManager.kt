package komascroll.panels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import komascroll.core.LruFileCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import kotlin.math.max

/**
 * Detects (and caches) the panel layout of reader pages for guided panel view. Detection runs on a
 * downscaled grayscale copy of the original page, off the main thread, one page at a time.
 */
class PanelManager(
    context: Context,
    private val json: Json,
) {
    private val memory = LruCache<String, PanelLayout>(MEMORY_ENTRIES)
    private val disk = LruFileCache(File(context.cacheDir, "komascroll_panels")) { DISK_LIMIT_BYTES }
    private val mutex = Mutex()

    /** Drops the in-memory layouts; the disk cache keeps them. */
    fun trimMemory() {
        memory.evictAll()
    }

    /** The panel layout of [page] in reading order; suspends until the page is downloaded. */
    suspend fun layoutFor(page: ReaderPage, rightToLeft: Boolean): PanelLayout {
        val target = (page as? InsertPage)?.parent ?: page
        val key = "${target.chapter.chapter.id}-${target.index}-${if (rightToLeft) "rtl" else "ltr"}-v$FORMAT"
        memory.get(key)?.let { return it }

        withContext(Dispatchers.IO) {
            disk.readText("$key.json")?.let { runCatching { json.decodeFromString<PanelLayout>(it) }.getOrNull() }
        }?.let {
            memory.put(key, it)
            return it
        }

        target.statusFlow.first { it == Page.State.Ready }
        val streamFn = target.stream ?: return PanelLayout.EMPTY

        val layout = mutex.withLock {
            withContext(Dispatchers.Default) {
                try {
                    detect(streamFn, rightToLeft)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Panel detection failed for $key" }
                    PanelLayout.EMPTY
                }
            }
        }
        memory.put(key, layout)
        withContext(Dispatchers.IO) { disk.writeText("$key.json", json.encodeToString(PanelLayout.serializer(), layout)) }
        return layout
    }

    private fun detect(streamFn: () -> InputStream, rightToLeft: Boolean): PanelLayout {
        val bytes = streamFn().use { it.readBytes() }
        if (ImageUtil.isAnimatedAndSupported(Buffer().write(bytes))) return PanelLayout.EMPTY

        val decoder = ImageDecoder.newInstance(ByteArrayInputStream(bytes)) ?: return PanelLayout.EMPTY
        val longest = max(decoder.width, decoder.height)
        var sampleSize = 1
        while (longest / (sampleSize * 2) >= TARGET_SIZE) sampleSize *= 2
        val decoded = try {
            decoder.decode(sampleSize = sampleSize)
        } finally {
            decoder.recycle()
        } ?: return PanelLayout.EMPTY

        val bitmap = if (decoded.config == Bitmap.Config.HARDWARE) {
            decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
        } else {
            decoded
        }
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        val gray = IntArray(pixels.size) { index ->
            val color = pixels[index]
            (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
        }
        return PanelDetector.detect(gray, width, height, rightToLeft)
    }

    private companion object {
        /** Pages are downscaled until their longest side is below twice this size. */
        const val TARGET_SIZE = 600
        const val MEMORY_ENTRIES = 128
        const val DISK_LIMIT_BYTES = 10L * 1024 * 1024

        /** Bump when detection changes so stale cached layouts are ignored. */
        const val FORMAT = 1
    }
}
