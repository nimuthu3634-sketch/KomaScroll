package komascroll.library.failover

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import komascroll.insights.PerceptualHash
import komascroll.insights.TitleSimilarity
import komascroll.library.KomaScrollDatabase
import komascroll.library.LibraryInsightsRecorder
import komascroll.library.PageFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * Finds the same series in the user's other sources so a chapter that fails to load (or is
 * missing) can be read from there.
 *
 * Candidates come from a title search in the other enabled sources of the same language. Each is
 * then verified with perceptual hashes: the first pages of a chapter both sources have are
 * fingerprinted and compared with the fingerprints recorded while the user read that chapter.
 */
class SourceFailover(
    private val sourceManager: SourceManager,
    private val database: KomaScrollDatabase,
    private val getManga: GetManga,
    private val networkToLocalManga: NetworkToLocalManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    enum class Verification {
        /** First pages match the ones read in the original source. */
        SAME_PAGES,

        /** No common chapter with recorded fingerprints: matched by title only. */
        TITLE_ONLY,

        /** Fingerprints differ: probably a different series (or a different translation). */
        DIFFERENT_PAGES,
    }

    data class Match(
        val source: HttpSource,
        val manga: SManga,
        val titleScore: Float,
        val verification: Verification,
        /** Whether the wanted chapter exists there (null when no chapter was asked for). */
        val hasChapter: Boolean?,
    )

    sealed interface Progress {
        val matches: List<Match>

        data class Searching(val searched: Int, val total: Int, override val matches: List<Match>) : Progress
        data class Finished(val total: Int, override val matches: List<Match>) : Progress
    }

    data class Opened(val mangaId: Long, val chapterId: Long?)

    fun search(mangaId: Long, chapterNumber: Double?): Flow<Progress> = channelFlow {
        val manga = getManga.await(mangaId) ?: run {
            send(Progress.Finished(0, emptyList()))
            return@channelFlow
        }
        val language = (sourceManager.get(manga.source) as? HttpSource)?.lang
        val sources = sourceManager.getVisibleOnlineSources()
            .filter { it.id != manga.source }
            .filter { language == null || language == ALL_LANGUAGES || it.lang == language || it.lang == ALL_LANGUAGES }
            .take(MAX_SOURCES)
        val fingerprints = database.hashes(mangaId)

        val matches = mutableListOf<Match>()
        val lock = Mutex()
        var searched = 0
        send(Progress.Searching(0, sources.size, emptyList()))

        coroutineScope {
            val permits = Semaphore(PARALLEL_SOURCES)
            sources.forEach { source ->
                launch {
                    val match = permits.withPermit {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) { evaluate(source, manga, chapterNumber, fingerprints) }
                    }
                    lock.withLock {
                        searched++
                        if (match != null) matches += match
                        send(Progress.Searching(searched, sources.size, sorted(matches)))
                    }
                }
            }
        }
        send(Progress.Finished(sources.size, sorted(matches)))
    }.flowOn(Dispatchers.IO)

    /** Adds the matched series to the database (not to the library) and finds the chapter. */
    suspend fun open(match: Match, chapterNumber: Double?): Opened {
        val local = networkToLocalManga(match.manga.toDomainManga(match.source.id))
        val chapters = match.source.getChapterList(local.toSManga())
        syncChaptersWithSource.await(chapters, local, match.source)
        val chapterId = chapterNumber?.let { number ->
            getChaptersByMangaId.await(local.id).firstOrNull { it.chapterNumber == number }?.id
        }
        return Opened(local.id, chapterId)
    }

    private suspend fun evaluate(
        source: HttpSource,
        manga: Manga,
        chapterNumber: Double?,
        fingerprints: Map<Double, List<Long>>,
    ): Match? = try {
        val results = source.getSearchManga(1, manga.ogTitle, source.getFilterList()).mangas
        val (candidate, score) = results
            .map { it to TitleSimilarity.similarity(manga.ogTitle, it.title) }
            .filter { it.second >= MIN_TITLE_SIMILARITY }
            .maxByOrNull { it.second }
            ?: return null

        val chapters = source.getChapterList(candidate)
        val numbered = chapters.associateBy { chapter ->
            ChapterRecognition.parseChapterNumber(candidate.title, chapter.name, chapter.chapter_number.toDouble())
        }
        val hasChapter = chapterNumber?.let { it in numbered.keys }
        val verification = verify(source, numbered, fingerprints, chapterNumber)
        Match(source, candidate, score, verification, hasChapter)
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Failover search failed in ${source.name}" }
        null
    }

    private suspend fun verify(
        source: HttpSource,
        candidateChapters: Map<Double, SChapter>,
        fingerprints: Map<Double, List<Long>>,
        wanted: Double?,
    ): Verification {
        // Compare on chapters both sides have, closest to the wanted chapter first.
        val common = fingerprints.keys
            .filter { it in candidateChapters.keys }
            .sortedBy { if (wanted != null) kotlin.math.abs(it - wanted) else -it }
            .take(MAX_VERIFY_CHAPTERS)
        if (common.isEmpty()) return Verification.TITLE_ONLY

        for (number in common) {
            val chapter = candidateChapters.getValue(number)
            val hashes = firstPageHashes(source, chapter)
            if (hashes.isEmpty()) continue
            val known = fingerprints.getValue(number)
            return if (hashes.any { hash -> known.any { PerceptualHash.matches(it, hash) } }) {
                Verification.SAME_PAGES
            } else {
                Verification.DIFFERENT_PAGES
            }
        }
        return Verification.TITLE_ONLY
    }

    private suspend fun firstPageHashes(source: HttpSource, chapter: SChapter): List<Long> {
        val pages = source.getPageList(chapter).take(LibraryInsightsRecorder.HASHED_PAGES)
        return pages.mapNotNull { page ->
            runCatching {
                if (page.imageUrl.isNullOrEmpty()) page.imageUrl = source.getImageUrl(page)
                source.getImage(page).use { response -> PageFingerprint.fromBytes(response.body.bytes()) }
            }.getOrNull()
        }
    }

    private fun sorted(matches: List<Match>): List<Match> = matches.sortedWith(
        compareBy<Match> { it.verification.ordinal }
            .thenByDescending { it.hasChapter == true }
            .thenByDescending { it.titleScore },
    )

    private companion object {
        const val ALL_LANGUAGES = "all"
        const val MAX_SOURCES = 15
        const val PARALLEL_SOURCES = 4
        const val SOURCE_TIMEOUT_MS = 30_000L
        const val MIN_TITLE_SIMILARITY = 0.6f
        const val MAX_VERIFY_CHAPTERS = 2
    }
}
