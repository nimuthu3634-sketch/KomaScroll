package komascroll.presentation.lab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import komascroll.i18n.KSR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The "KomaScroll Lab" settings section, where every KomaScroll feature gets its on/off toggle.
 */
object SettingsLabScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = SettingsLabScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KSR.strings.pref_category_lab

    @Composable
    override fun getPreferences(): List<Preference> {
        return listOf(
            Preference.PreferenceItem.InfoPreference(stringResource(KSR.strings.lab_empty)),
        )
    }
}
