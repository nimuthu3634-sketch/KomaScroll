package komascroll.library.failover

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import komascroll.i18n.KSR
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * "Find in other sources": lists the same series in the user's other sources, verified by page
 * fingerprints where possible, and opens the wanted chapter (or the series) from a chosen one.
 */
class SourceFailoverScreen(
    private val mangaId: Long,
    private val chapterId: Long? = null,
    private val chapterNumber: Double? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { Model(mangaId, chapterId, chapterNumber) }
        val state by model.state.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KSR.strings.failover_title),
                    subtitle = state.mangaTitle,
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val number = state.chapterNumber
                        Text(
                            text = if (number != null) {
                                stringResource(KSR.strings.failover_intro_chapter, formatNumber(number))
                            } else {
                                stringResource(KSR.strings.failover_intro)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        when (val progress = state.progress) {
                            is SourceFailover.Progress.Searching -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(
                                    stringResource(KSR.strings.failover_searching, progress.searched, progress.total),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            is SourceFailover.Progress.Finished -> if (progress.matches.isEmpty()) {
                                Text(
                                    stringResource(
                                        if (progress.total == 0) KSR.strings.failover_no_sources else KSR.strings.failover_none,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }

                items(state.progress?.matches.orEmpty(), key = { "${it.source.id}-${it.manga.url}" }) { match ->
                    MatchRow(
                        match = match,
                        chapterNumber = state.chapterNumber,
                        busy = state.opening,
                        onRead = {
                            model.open(context, match) { opened ->
                                val chapter = opened.chapterId
                                if (chapter != null) {
                                    context.startActivity(ReaderActivity.newIntent(context, opened.mangaId, chapter))
                                } else {
                                    context.toast(KSR.strings.failover_chapter_not_found)
                                    navigator.push(MangaScreen(opened.mangaId))
                                }
                            }
                        },
                        onOpenSeries = {
                            model.open(context, match) { opened -> navigator.push(MangaScreen(opened.mangaId)) }
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun MatchRow(
        match: SourceFailover.Match,
        chapterNumber: Double?,
        busy: Boolean,
        onRead: () -> Unit,
        onOpenSeries: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(match.manga.title, style = MaterialTheme.typography.titleSmall)
            Text(
                "${match.source.name} · ${match.source.lang.uppercase()}",
                style = MaterialTheme.typography.bodySmall,
            )
            val (label, color) = when (match.verification) {
                SourceFailover.Verification.SAME_PAGES ->
                    stringResource(KSR.strings.failover_verified) to MaterialTheme.colorScheme.primary
                SourceFailover.Verification.TITLE_ONLY ->
                    stringResource(KSR.strings.failover_title_only) to MaterialTheme.colorScheme.onSurfaceVariant
                SourceFailover.Verification.DIFFERENT_PAGES ->
                    stringResource(KSR.strings.failover_different) to MaterialTheme.colorScheme.error
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            if (chapterNumber != null && match.hasChapter == false) {
                Text(
                    stringResource(KSR.strings.failover_chapter_missing, formatNumber(chapterNumber)),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chapterNumber != null && match.hasChapter == true) {
                    OutlinedButton(onClick = onRead, enabled = !busy) {
                        Text(stringResource(KSR.strings.failover_read_chapter, formatNumber(chapterNumber)))
                    }
                }
                TextButton(onClick = onOpenSeries, enabled = !busy) {
                    Text(stringResource(KSR.strings.failover_open_series))
                }
            }
        }
    }

    data class State(
        val mangaTitle: String? = null,
        val chapterNumber: Double? = null,
        val progress: SourceFailover.Progress? = null,
        val opening: Boolean = false,
    )

    private class Model(
        mangaId: Long,
        chapterId: Long?,
        chapterNumber: Double?,
        private val failover: SourceFailover = Injekt.get(),
        getManga: GetManga = Injekt.get(),
        getChapter: GetChapter = Injekt.get(),
    ) : StateScreenModel<State>(State(chapterNumber = chapterNumber)) {

        init {
            screenModelScope.launch {
                val number = chapterNumber ?: chapterId?.let { getChapter.await(it)?.chapterNumber }
                mutableState.update {
                    it.copy(mangaTitle = getManga.await(mangaId)?.title, chapterNumber = number?.takeIf { n -> n >= 0 })
                }
                failover.search(mangaId, state.value.chapterNumber).collect { progress ->
                    mutableState.update { it.copy(progress = progress) }
                }
            }
        }

        fun open(context: Context, match: SourceFailover.Match, onOpened: (SourceFailover.Opened) -> Unit) {
            if (state.value.opening) return
            mutableState.update { it.copy(opening = true) }
            screenModelScope.launch {
                try {
                    onOpened(failover.open(match, state.value.chapterNumber))
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Could not open failover match" }
                    context.toast(KSR.strings.failover_open_failed)
                } finally {
                    mutableState.update { it.copy(opening = false) }
                }
            }
        }
    }

    companion object {
        /** Intent action handled by [MainActivity] to open this screen, e.g. from the reader. */
        const val ACTION = "com.chama.komascroll.FIND_IN_OTHER_SOURCES"
        const val EXTRA_MANGA_ID = "manga_id"
        const val EXTRA_CHAPTER_ID = "chapter_id"

        fun intent(context: Context, mangaId: Long, chapterId: Long?): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION)
                .putExtra(EXTRA_MANGA_ID, mangaId)
                .apply { if (chapterId != null) putExtra(EXTRA_CHAPTER_ID, chapterId) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        private fun formatNumber(number: Double): String =
            if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
    }
}
