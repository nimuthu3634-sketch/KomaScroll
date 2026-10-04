package komascroll.immersion.downloads

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.util.system.workManager
import exh.source.MERGED_SOURCE_ID
import komascroll.immersion.PlannerChapter
import komascroll.immersion.SmartDownloadPlanner
import komascroll.lab.LabPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * Smart downloads: keeps the next few unread chapters of the series you are actively reading
 * downloaded, so they are ready offline. Runs periodically through WorkManager, by default only
 * while charging and on an unmetered (Wi-Fi) network.
 *
 * The job only queues chapters; the regular downloader fetches them, with its own notification.
 */
class SmartDownloadJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val preferences = Injekt.get<LabPreferences>()
        if (!preferences.smartDownloadsEnabled().get()) return Result.success()
        return try {
            val queued = queueChapters(preferences)
            logcat { "Smart downloads queued $queued chapters" }
            Result.success(workDataOf(KEY_QUEUED to queued))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Smart downloads failed" }
            Result.failure()
        }
    }

    private suspend fun queueChapters(preferences: LabPreferences): Int {
        val downloadManager = Injekt.get<DownloadManager>()
        val getChaptersByMangaId = Injekt.get<GetChaptersByMangaId>()
        val ahead = preferences.smartDownloadsAhead().get()
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(preferences.smartDownloadsActiveDays().get().toLong())

        val library = Injekt.get<GetLibraryManga>().await().associateBy { it.manga.id }
        // One row per series: its most recently read chapter. Library entries only.
        val active = Injekt.get<HistoryRepository>()
            .getHistory("", unfinishedManga = null, unfinishedChapter = null, nonLibraryEntries = false)
            .first()
            .filter { (it.readAt?.time ?: 0L) >= cutoff }
            .mapNotNull { library[it.mangaId]?.manga }
            .filter { !it.isLocal() && it.source != MERGED_SOURCE_ID }
            .distinctBy { it.id }

        var queued = 0
        for (manga in active) {
            currentCoroutineContext().ensureActive()
            val chapters = getChaptersByMangaId.await(manga.id, applyFilter = true)
            val planned = SmartDownloadPlanner.plan(
                chapters.map { chapter ->
                    PlannerChapter(
                        id = chapter.id,
                        number = chapter.chapterNumber,
                        sourceOrder = chapter.sourceOrder,
                        read = chapter.read,
                        downloaded = downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                manga.ogTitle,
                                manga.source,
                            ),
                    )
                },
                ahead,
            ).toSet()
            if (planned.isEmpty()) continue
            downloadManager.downloadChapters(manga, chapters.filter { it.id in planned }, autoStart = false)
            queued += planned.size
        }
        if (queued > 0) downloadManager.startDownloads()
        return queued
    }

    companion object {
        private const val WORK_NAME = "KomaScrollSmartDownloads"
        private const val WORK_NAME_NOW = "KomaScrollSmartDownloadsNow"
        private const val KEY_QUEUED = "queued"
        private const val INTERVAL_HOURS = 6L

        /** Schedules (or cancels) the periodic job to match the Lab settings. */
        fun setupTask(context: Context) {
            val preferences = Injekt.get<LabPreferences>()
            if (!preferences.smartDownloadsEnabled().get()) {
                context.workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (preferences.smartDownloadsUnmeteredOnly().get()) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .setRequiresCharging(preferences.smartDownloadsRequireCharging().get())
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<SmartDownloadJob>(INTERVAL_HOURS, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            context.workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Runs the job once right away (on any network), e.g. from the Lab screen. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmartDownloadJob>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            context.workManager.enqueueUniqueWork(WORK_NAME_NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
