package komascroll.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.util.system.toast
import komascroll.i18n.KSR
import komascroll.immersion.lock.DecoySession
import komascroll.insights.ChapterGaps
import komascroll.insights.ReleasePredictor
import komascroll.lab.LabPreferences
import komascroll.library.failover.SourceFailoverScreen
import komascroll.library.privacy.PrivateSeries
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Series page row with KomaScroll library intelligence: the predicted next release
 * ("Next chapter likely: Friday") and a shortcut to find the series in other sources.
 * Renders nothing when there is neither a confident prediction nor failover available.
 */
@Composable
fun LibraryInsightsRow(manga: Manga, chapters: List<Chapter>) {
    val preferences = remember { Injekt.get<LabPreferences>() }
    val predictionEnabled by preferences.releasePredictionEnabled().collectAsState()
    val failoverEnabled by preferences.sourceFailoverEnabled().collectAsState()

    val prediction = remember(chapters, predictionEnabled) {
        if (predictionEnabled) {
            ReleasePredictor.predict(chapters.map { it.dateUpload }, LocalDate.now(), ZoneId.systemDefault())
        } else {
            null
        }
    }
    val showFailover = failoverEnabled && !manga.isLocal()
    val privateEnabled by preferences.privateSeriesEnabled().collectAsState()
    val decoy by DecoySession.active.collectAsState()
    val showPrivate = privateEnabled && !decoy
    if (prediction == null && !showFailover && !showPrivate) return

    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val isPrivate by remember(manga.id) { PrivateSeries.isMarked(manga.id) }.collectAsState(false)
    val firstMissing = remember(chapters) { ChapterGaps.firstMissing(chapters.map { it.chapterNumber }) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prediction != null) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(KSR.strings.release_next_likely, describe(prediction.date)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (showFailover) {
            TextButton(onClick = { navigator.push(SourceFailoverScreen(manga.id, chapterNumber = firstMissing)) }) {
                Text(stringResource(KSR.strings.failover_button))
            }
        }
        if (showPrivate) {
            IconButton(
                onClick = {
                    PrivateSeries.setMarked(manga.id, !isPrivate)
                    context.toast(if (isPrivate) KSR.strings.private_unmarked else KSR.strings.private_marked)
                },
            ) {
                Icon(
                    imageVector = if (isPrivate) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    contentDescription = stringResource(
                        if (isPrivate) KSR.strings.private_unmark else KSR.strings.private_mark,
                    ),
                    tint = if (isPrivate) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        }
    }
}

@Composable
private fun describe(date: LocalDate): String {
    val days = ChronoUnit.DAYS.between(LocalDate.now(), date)
    val locale = Locale.getDefault()
    return when {
        days <= 0L -> stringResource(KSR.strings.release_today)
        days == 1L -> stringResource(KSR.strings.release_tomorrow)
        days < 7L -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> date.format(DateTimeFormatter.ofPattern("d MMM", locale))
    }
}
