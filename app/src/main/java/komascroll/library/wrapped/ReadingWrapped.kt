package komascroll.library.wrapped

import komascroll.insights.ReadingStreaks
import komascroll.library.KomaScrollDatabase
import komascroll.library.privacy.PrivateSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetReadMangaNotInLibraryView
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds a "Reading Wrapped" yearly summary.
 *
 * Chapters, reading time, active days and streaks come from the app's reading history, which
 * keeps the last time each chapter was read. Pages are counted by KomaScroll itself from the moment
 * the feature was turned on ([Summary.pagesCountedSince]).
 */
class ReadingWrapped(
    private val database: KomaScrollDatabase,
    private val getLibraryManga: GetLibraryManga,
    private val getReadMangaNotInLibraryView: GetReadMangaNotInLibraryView,
    private val historyRepository: HistoryRepository,
) {

    data class Ranked(val name: String, val count: Int)

    data class Summary(
        val year: Int,
        val chaptersRead: Int,
        val minutesRead: Long,
        val pagesRead: Int,
        val pagesCountedSince: LocalDate?,
        val activeDays: Int,
        val longestStreak: Int,
        val seriesRead: Int,
        val topSeries: List<Ranked>,
        val topGenres: List<Ranked>,
    ) {
        val isEmpty: Boolean get() = chaptersRead == 0 && pagesRead == 0
    }

    suspend fun summary(year: Int, zone: ZoneId = ZoneId.systemDefault()): Summary = withContext(Dispatchers.IO) {
        val start = LocalDate.of(year, 1, 1)
        val end = LocalDate.of(year, 12, 31)

        val mangas = (getLibraryManga.await() + getReadMangaNotInLibraryView.await())
            .map { it.manga }
            .distinctBy { it.id }

        val activeDays = mutableSetOf<LocalDate>()
        val chaptersBySeries = mutableMapOf<Long, Int>()
        var minutes = 0L
        for (manga in mangas) {
            val history = historyRepository.getHistoryByMangaId(manga.id)
                .filter { entry ->
                    val day = entry.readAt?.toInstant()?.atZone(zone)?.toLocalDate() ?: return@filter false
                    !day.isBefore(start) && !day.isAfter(end)
                }
            if (history.isEmpty()) continue
            history.forEach { entry ->
                activeDays += entry.readAt!!.toInstant().atZone(zone).toLocalDate()
                minutes += entry.readDuration / 60_000
            }
            chaptersBySeries[manga.id] = history.size
        }

        val pageRows = database.pagesRead(start, end)
        pageRows.forEach { if (it.pages > 0) activeDays += it.day }
        val pagesBySeries = pageRows.groupBy { it.mangaId }.mapValues { (_, rows) -> rows.sumOf { it.pages } }

        // Hidden private series still count towards the totals but are never named.
        val hidden = PrivateSeries.currentlyHidden()
        val titles = mangas.filter { it.id !in hidden }.associate { it.id to it.title }
        val genres = mangas.associate { it.id to it.genre.orEmpty() }
        // Rank series by chapters read, using pages as a tie-breaker.
        val topSeries = chaptersBySeries.keys.union(pagesBySeries.keys)
            .sortedWith(
                compareByDescending<Long> { chaptersBySeries[it] ?: 0 }.thenByDescending { pagesBySeries[it] ?: 0 },
            )
            .mapNotNull { id -> titles[id]?.let { Ranked(it, chaptersBySeries[id] ?: 0) } }
            .take(TOP_COUNT)

        val genreWeights = mutableMapOf<String, Int>()
        chaptersBySeries.filterKeys { it !in hidden }.forEach { (id, chapters) ->
            genres[id].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.forEach { genre ->
                genreWeights[genre] = (genreWeights[genre] ?: 0) + chapters
            }
        }

        Summary(
            year = year,
            chaptersRead = chaptersBySeries.values.sum(),
            minutesRead = minutes,
            pagesRead = pagesBySeries.values.sum(),
            pagesCountedSince = database.firstLoggedDay(),
            activeDays = activeDays.size,
            longestStreak = ReadingStreaks.longest(activeDays),
            seriesRead = chaptersBySeries.keys.union(pagesBySeries.keys).size,
            topSeries = topSeries,
            topGenres = genreWeights.entries.sortedByDescending { it.value }.take(TOP_COUNT).map { Ranked(it.key, it.value) },
        )
    }

    private companion object {
        const val TOP_COUNT = 5
    }
}
