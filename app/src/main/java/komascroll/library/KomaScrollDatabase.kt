package komascroll.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

/**
 * KomaScroll's own small database, kept separate from the upstream SQLDelight schema so upstream
 * migrations never conflict with it. Everything here stays on the device.
 *
 * - `page_hash`: dHash fingerprints of the first pages of chapters, used to verify that a series
 *   found in another source really is the same one (source failover).
 * - `reading_log`: pages read per day and series, for Reading Wrapped.
 */
class KomaScrollDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    data class PagesRead(val day: LocalDate, val mangaId: Long, val pages: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE page_hash (
                manga_id INTEGER NOT NULL,
                chapter_number REAL NOT NULL,
                page_index INTEGER NOT NULL,
                hash INTEGER NOT NULL,
                PRIMARY KEY (manga_id, chapter_number, page_index)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE reading_log (
                day TEXT NOT NULL,
                manga_id INTEGER NOT NULL,
                pages INTEGER NOT NULL,
                PRIMARY KEY (day, manga_id)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun hasHash(mangaId: Long, chapterNumber: Double, pageIndex: Int): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM page_hash WHERE manga_id = ? AND chapter_number = ? AND page_index = ?",
            arrayOf(mangaId.toString(), chapterNumber.toString(), pageIndex.toString()),
        ).use { it.moveToFirst() }

    fun putHash(mangaId: Long, chapterNumber: Double, pageIndex: Int, hash: Long) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO page_hash (manga_id, chapter_number, page_index, hash) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(mangaId, chapterNumber, pageIndex, hash),
        )
    }

    /** Stored fingerprints of a series, by chapter number. */
    fun hashes(mangaId: Long): Map<Double, List<Long>> {
        val result = mutableMapOf<Double, MutableList<Long>>()
        readableDatabase.rawQuery(
            "SELECT chapter_number, hash FROM page_hash WHERE manga_id = ?",
            arrayOf(mangaId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.getOrPut(cursor.getDouble(0)) { mutableListOf() } += cursor.getLong(1)
            }
        }
        return result
    }

    fun addPagesRead(day: LocalDate, mangaId: Long, pages: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // No UPSERT before SQLite 3.24 (Android 11), so insert-or-ignore then increment.
            db.execSQL(
                "INSERT OR IGNORE INTO reading_log (day, manga_id, pages) VALUES (?, ?, 0)",
                arrayOf<Any>(day.toString(), mangaId),
            )
            db.execSQL(
                "UPDATE reading_log SET pages = pages + ? WHERE day = ? AND manga_id = ?",
                arrayOf<Any>(pages, day.toString(), mangaId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun pagesRead(from: LocalDate, to: LocalDate): List<PagesRead> {
        val rows = mutableListOf<PagesRead>()
        readableDatabase.rawQuery(
            "SELECT day, manga_id, pages FROM reading_log WHERE day >= ? AND day <= ?",
            arrayOf(from.toString(), to.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += PagesRead(LocalDate.parse(cursor.getString(0)), cursor.getLong(1), cursor.getInt(2))
            }
        }
        return rows
    }

    /** The first day pages were counted, if any. */
    fun firstLoggedDay(): LocalDate? =
        readableDatabase.rawQuery("SELECT MIN(day) FROM reading_log", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) LocalDate.parse(cursor.getString(0)) else null
        }

    fun clearReadingLog() {
        writableDatabase.execSQL("DELETE FROM reading_log")
    }

    private companion object {
        const val NAME = "komascroll.db"
        const val VERSION = 1
    }
}
