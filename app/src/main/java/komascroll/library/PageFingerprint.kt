package komascroll.library

import android.graphics.Bitmap
import android.graphics.Color
import komascroll.insights.PerceptualHash
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayInputStream
import kotlin.math.max

/** Computes the dHash fingerprint of a page image. */
object PageFingerprint {

    /** Pages are decoded at reduced resolution; dHash only needs a 9×8 thumbnail. */
    private const val TARGET_SIZE = 256

    fun fromBytes(bytes: ByteArray): Long? {
        val decoder = ImageDecoder.newInstance(ByteArrayInputStream(bytes)) ?: return null
        val longest = max(decoder.width, decoder.height)
        if (longest <= 0) {
            decoder.recycle()
            return null
        }
        var sampleSize = 1
        while (longest / (sampleSize * 2) >= TARGET_SIZE) sampleSize *= 2
        val decoded = try {
            decoder.decode(sampleSize = sampleSize)
        } finally {
            decoder.recycle()
        } ?: return null

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
        val gray = IntArray(pixels.size) {
            val c = pixels[it]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        return PerceptualHash.dHash(gray, width, height)
    }
}
