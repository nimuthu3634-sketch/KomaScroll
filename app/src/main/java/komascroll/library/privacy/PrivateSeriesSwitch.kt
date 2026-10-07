package komascroll.library.privacy

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import komascroll.i18n.KSR
import komascroll.immersion.lock.AppLock
import komascroll.immersion.lock.DecoySession
import komascroll.immersion.lock.PinDialog
import komascroll.lab.LabPreferences
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The "Hide private series" switch shown in the More tab, next to Incognito mode. When the
 * KomaScroll PIN lock is on, showing private series again asks for the PIN.
 */
@Composable
fun PrivateSeriesSwitch() {
    val preferences = remember { Injekt.get<LabPreferences>() }
    val appLock = remember { Injekt.get<AppLock>() }
    val enabled by preferences.privateSeriesEnabled().collectAsState()
    val hide by preferences.hidePrivateSeries().collectAsState()
    val marked by preferences.privateSeriesIds().collectAsState()
    val decoy by DecoySession.active.collectAsState()
    var confirmShow by remember { mutableStateOf(false) }
    if (!enabled || decoy) return

    SwitchPreferenceWidget(
        title = stringResource(KSR.strings.private_hide),
        subtitle = if (marked.isEmpty()) {
            stringResource(KSR.strings.private_hide_summary_none)
        } else {
            stringResource(KSR.strings.private_hide_summary, marked.size)
        },
        icon = if (hide) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
        checked = hide,
        onCheckedChanged = { on ->
            if (!on && appLock.isEnabled()) confirmShow = true else preferences.hidePrivateSeries().set(on)
        },
    )

    if (confirmShow) {
        val wrongPin = stringResource(KSR.strings.lock_wrong_pin)
        PinDialog(
            title = stringResource(KSR.strings.private_show_title),
            labels = listOf(stringResource(KSR.strings.lock_current_pin)),
            onDismiss = { confirmShow = false },
            onConfirm = { (pin) ->
                if (appLock.isRealPin(pin)) {
                    preferences.hidePrivateSeries().set(false)
                    null
                } else {
                    wrongPin
                }
            },
        )
    }
}
