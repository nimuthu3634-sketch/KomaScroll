package komascroll.reader

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
 * Reader settings sheet entries for KomaScroll Lab features. Each toggle is shown only once its
 * feature is enabled in Settings → KomaScroll Lab.
 */
@Composable
fun ReaderLabSettings() {
    val preferences = remember { Injekt.get<LabPreferences>() }
    val upscaleEnabled by preferences.upscaleEnabled().collectAsState()
    val translateEnabled by preferences.translateEnabled().collectAsState()
    if (!upscaleEnabled && !translateEnabled) return

    HeadingItem(KSR.strings.pref_category_lab)
    if (translateEnabled) {
        CheckboxItem(
            label = stringResource(KSR.strings.translate_reader_toggle),
            pref = preferences.translateInReader(),
        )
    }
    if (upscaleEnabled) {
        CheckboxItem(
            label = stringResource(KSR.strings.upscale_reader_toggle),
            pref = preferences.upscaleInReader(),
        )
    }
}
