package komascroll.library.wrapped

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import komascroll.i18n.KSR
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate

/** Reading Wrapped: a yearly reading summary with a shareable image card. */
class ReadingWrappedScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { Model() }
        val state by model.state.collectAsState()
        val summary = state.summary

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KSR.strings.wrapped_title),
                    navigateUp = navigator::pop,
                    actions = {
                        if (summary != null && !summary.isEmpty) {
                            IconButton(onClick = { context.startActivity(WrappedShareCard.shareIntent(context, summary)) }) {
                                Icon(Icons.Outlined.Share, contentDescription = stringResource(KSR.strings.wrapped_share))
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.years.forEach { year ->
                            FilterChip(
                                selected = year == state.year,
                                onClick = { model.select(year) },
                                label = { Text(year.toString()) },
                            )
                        }
                    }
                }
                when {
                    summary == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                    summary.isEmpty -> item {
                        Text(
                            stringResource(KSR.strings.wrapped_empty, summary.year),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    else -> {
                        item {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatCard(summary.chaptersRead.toString(), stringResource(KSR.strings.wrapped_chapters))
                                    StatCard((summary.minutesRead / 60).toString(), stringResource(KSR.strings.wrapped_hours))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatCard(summary.pagesRead.toString(), stringResource(KSR.strings.wrapped_pages))
                                    StatCard(summary.longestStreak.toString(), stringResource(KSR.strings.wrapped_streak_days))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatCard(summary.activeDays.toString(), stringResource(KSR.strings.wrapped_active_days))
                                    StatCard(summary.seriesRead.toString(), stringResource(KSR.strings.wrapped_series))
                                }
                            }
                        }
                        if (summary.topSeries.isNotEmpty()) {
                            item { SectionTitle(stringResource(KSR.strings.wrapped_top_series)) }
                            summary.topSeries.forEachIndexed { index, series ->
                                item {
                                    Text(
                                        stringResource(KSR.strings.wrapped_series_line, index + 1, series.name, series.count),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }
                        if (summary.topGenres.isNotEmpty()) {
                            item { SectionTitle(stringResource(KSR.strings.wrapped_top_genres)) }
                            item {
                                Text(
                                    summary.topGenres.joinToString(" · ") { it.name },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                        item {
                            val since = summary.pagesCountedSince
                            Text(
                                if (since != null) {
                                    stringResource(KSR.strings.wrapped_note_pages_since, since.toString())
                                } else {
                                    stringResource(KSR.strings.wrapped_note_no_pages)
                                },
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RowScope.StatCard(value: String, label: String) {
        Card(modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(16.dp)) {
                Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    data class State(
        val year: Int = LocalDate.now().year,
        val years: List<Int> = (0 until 3).map { LocalDate.now().year - it },
        val summary: ReadingWrapped.Summary? = null,
    )

    private class Model(private val wrapped: ReadingWrapped = Injekt.get()) : StateScreenModel<State>(State()) {
        private var job: Job? = null

        init {
            select(state.value.year)
        }

        fun select(year: Int) {
            job?.cancel()
            mutableState.update { it.copy(year = year, summary = null) }
            job = screenModelScope.launch {
                val summary = wrapped.summary(year)
                mutableState.update { it.copy(summary = summary) }
            }
        }
    }
}
