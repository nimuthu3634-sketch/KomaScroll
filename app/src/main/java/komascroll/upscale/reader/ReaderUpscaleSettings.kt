package komascroll.upscale.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import komascroll.i18n.KSR
import komascroll.lab.LabPreferences
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Reader settings sheet entry for AI upscaling. Shown only once the feature is enabled in KomaScroll Lab.
 */
@Composable
fun ReaderUpscaleSettings() {
    val preferences = remember { Injekt.get<LabPreferences>() }
    val enabled by preferences.upscaleEnabled().collectAsState()
    if (!enabled) return

    HeadingItem(KSR.strings.pref_category_lab)
    CheckboxItem(
        label = stringResource(KSR.strings.upscale_reader_toggle),
        pref = preferences.upscaleInReader(),
    )
}
