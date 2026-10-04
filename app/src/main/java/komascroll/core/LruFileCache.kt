package komascroll.core

import java.io.File
import java.io.OutputStream

/**
 * A directory of files with least-recently-used eviction down to [limitBytes]. Reads refresh a
 * file's modification time to mark it as used. Writes are atomic (temp file + rename).
 */
class LruFileCache(
    private val directory: File,
    private val limitBytes: () -> Long,
) {
    private val lock = Any()

    fun get(name: String): File? {
        val file = fileFor(name)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    fun contains(name: String): Boolean = fileFor(name).isFile

    fun readText(name: String): String? = get(name)?.readText()

    fun write(name: String, writer: (OutputStream) -> Unit): File = synchronized(lock) {
        directory.mkdirs()
        val target = fileFor(name)
        val temp = File(directory, "${target.name}.tmp")
        try {
            temp.outputStream().use(writer)
            if (!temp.renameTo(target)) error("Could not move ${temp.name} into the cache")
        } finally {
            temp.delete()
        }
        trimLocked()
        target
    }

    fun writeText(name: String, text: String): File = write(name) { it.write(text.toByteArray()) }

    /** Total size in bytes. */
    fun size(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes every cached file. Returns how many were removed. */
    fun clear(): Int = synchronized(lock) {
        directory.listFiles()?.count { it.delete() } ?: 0
    }

    fun trim() = synchronized(lock) { trimLocked() }

    private fun trimLocked() {
        val limit = limitBytes()
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limit) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun fileFor(name: String) = File(directory, name.replace(UNSAFE_CHARS, "_"))

    private companion object {
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
