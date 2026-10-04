package komascroll.library

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import komascroll.lab.LabPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.time.LocalDate
import java.util.Collections

/**
 * Records, while reading: pages read per day (Reading Wrapped) and fingerprints of the first pages
 * of each chapter (source failover). Each page counts once per reading session.
 */
class LibraryInsightsRecorder(
    private val preferences: LabPreferences,
    private val database: KomaScrollDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val countedPages: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun onPageSelected(page: ReaderPage) {
        val target = (page as? InsertPage)?.parent ?: page
        val chapter = target.chapter.chapter
        val mangaId = chapter.manga_id ?: return

        if (preferences.readingStatsEnabled().get() && countedPages.add("${chapter.id}:${target.index}")) {
            scope.launch {
                runCatching { database.addPagesRead(LocalDate.now(), mangaId, 1) }
                    .onFailure { logcat(LogPriority.ERROR, it) { "Could not record page read" } }
            }
        }

        val chapterNumber = chapterKey(chapter.chapter_number.toDouble())
        if (preferences.sourceFailoverEnabled().get() && target.index < HASHED_PAGES && chapterNumber >= 0) {
            scope.launch {
                try {
                    if (database.hasHash(mangaId, chapterNumber, target.index)) return@launch
                    withTimeoutOrNull(READY_TIMEOUT_MS) { target.statusFlow.first { it == Page.State.Ready } }
                        ?: return@launch
                    val bytes = target.stream?.invoke()?.use { it.readBytes() } ?: return@launch
                    val hash = PageFingerprint.fromBytes(bytes) ?: return@launch
                    database.putHash(mangaId, chapterNumber, target.index, hash)
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Could not fingerprint page" }
                }
            }
        }
    }

    /** Called when the reader closes; the next session counts pages afresh. */
    fun onReaderClosed() {
        countedPages.clear()
    }

    companion object {
        /** How many leading pages of each chapter are fingerprinted (credit pages vary by group). */
        const val HASHED_PAGES = 3
        private const val READY_TIMEOUT_MS = 60_000L

        /**
         * Chapter numbers reach us as Float (reader, sources) or Double (database); rounding makes
         * them comparable, e.g. 10.1f and 10.1 both become 10.1.
         */
        fun chapterKey(number: Double): Double = Math.round(number * 1000) / 1000.0
    }
}
