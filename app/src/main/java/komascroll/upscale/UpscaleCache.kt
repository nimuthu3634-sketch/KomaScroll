package komascroll.upscale

import android.content.Context
import android.graphics.Bitmap
import komascroll.lab.LabPreferences
import java.io.File

/**
 * Disk cache for upscaled pages, with least-recently-used eviction down to the size limit set in
 * [LabPreferences.upscaleCacheSizeMb]. Reads refresh a file's modification time to mark it as used.
 */
class UpscaleCache(
    context: Context,
    private val preferences: LabPreferences,
) {
    private val directory = File(context.cacheDir, "komascroll_upscale")
    private val lock = Any()

    /** Returns the cached file for [key] and marks it as recently used, or null if absent. */
    fun get(key: String): File? {
        val file = fileFor(key)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    fun contains(key: String): Boolean = fileFor(key).isFile

    /** Encodes [bitmap] as JPEG, stores it atomically under [key], then trims the cache. */
    fun put(key: String, bitmap: Bitmap) {
        synchronized(lock) {
            directory.mkdirs()
            val target = fileFor(key)
            val temp = File(directory, "${target.name}.tmp")
            try {
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                if (!temp.renameTo(target)) error("Could not move ${temp.name} into the cache")
            } finally {
                temp.delete()
            }
            trimLocked()
        }
    }

    /** Total size of the cache in bytes. */
    fun size(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes every cached page. Returns the number of files removed. */
    fun clear(): Int = synchronized(lock) {
        directory.listFiles()?.count { it.delete() } ?: 0
    }

    /** Re-applies the size limit, e.g. after the user lowered it. */
    fun trim() = synchronized(lock) { trimLocked() }

    private fun trimLocked() {
        val limit = preferences.upscaleCacheSizeMb().get().toLong() * 1024 * 1024
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limit) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun fileFor(key: String) = File(directory, key.replace(UNSAFE_CHARS, "_") + ".jpg")

    private companion object {
        const val JPEG_QUALITY = 92
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
