package komascroll.presentation.lab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import komascroll.i18n.KSR
import komascroll.lab.LabPreferences
import komascroll.library.KomaScrollDatabase
import komascroll.library.wrapped.ReadingWrappedScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** The "Library intelligence" group of the KomaScroll Lab screen. */
@Composable
internal fun libraryGroup(preferences: LabPreferences): Preference.PreferenceGroup {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { Injekt.get<KomaScrollDatabase>() }

    return Preference.PreferenceGroup(
        title = stringResource(KSR.strings.library_group),
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.releasePredictionEnabled(),
                title = stringResource(KSR.strings.release_enable),
                subtitle = stringResource(KSR.strings.release_enable_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.sourceFailoverEnabled(),
                title = stringResource(KSR.strings.failover_enable),
                subtitle = stringResource(KSR.strings.failover_enable_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.readingStatsEnabled(),
                title = stringResource(KSR.strings.wrapped_stats_enable),
                subtitle = stringResource(KSR.strings.wrapped_stats_enable_summary),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KSR.strings.wrapped_title),
                subtitle = stringResource(KSR.strings.wrapped_open_summary),
                onClick = { navigator.push(ReadingWrappedScreen()) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KSR.strings.wrapped_clear),
                onClick = {
                    scope.launch {
                        withIOContext { database.clearReadingLog() }
                        context.toast(KSR.strings.wrapped_cleared)
                    }
                },
            ),
        ),
    )
}
