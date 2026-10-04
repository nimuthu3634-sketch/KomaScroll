package komascroll.insights

/**
 * dHash ("difference hash"): a 64-bit perceptual fingerprint that survives re-encoding, resizing
 * and small edits, used to recognise the same page served by different sources.
 */
object PerceptualHash {

    private const val HASH_WIDTH = 9
    private const val HASH_HEIGHT = 8

    /** Hashes with at most this many differing bits are treated as the same page. */
    const val MATCH_DISTANCE = 10

    /** [gray] holds [width] × [height] luminance values (0..255), row by row. */
    fun dHash(gray: IntArray, width: Int, height: Int): Long {
        require(width > 0 && height > 0 && gray.size >= width * height) { "Invalid image" }
        val small = downscale(gray, width, height)
        var hash = 0L
        var bit = 0
        for (y in 0 until HASH_HEIGHT) {
            for (x in 0 until HASH_WIDTH - 1) {
                if (small[y * HASH_WIDTH + x] > small[y * HASH_WIDTH + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun matches(a: Long, b: Long): Boolean = distance(a, b) <= MATCH_DISTANCE

    /** Box-filter downscale to 9×8 (area average, so it is robust to the source resolution). */
    private fun downscale(gray: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(HASH_WIDTH * HASH_HEIGHT)
        for (ty in 0 until HASH_HEIGHT) {
            val y0 = ty * height / HASH_HEIGHT
            val y1 = maxOf(y0 + 1, (ty + 1) * height / HASH_HEIGHT)
            for (tx in 0 until HASH_WIDTH) {
                val x0 = tx * width / HASH_WIDTH
                val x1 = maxOf(x0 + 1, (tx + 1) * width / HASH_WIDTH)
                var sum = 0L
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) sum += gray[row + x]
                }
                out[ty * HASH_WIDTH + tx] = (sum / ((y1 - y0) * (x1 - x0))).toInt()
            }
        }
        return out
    }
}
