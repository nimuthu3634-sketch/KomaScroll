package komascroll.presentation.lab

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import komascroll.i18n.KSR
import komascroll.immersion.PinHasher
import komascroll.immersion.SfxHapticsManager
import komascroll.immersion.SfxKind
import komascroll.immersion.downloads.SmartDownloadJob
import komascroll.immersion.lock.AppLock
import komascroll.immersion.lock.PinDialog
import komascroll.lab.LabPreferences
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/** The "Immersion" group: sound-effect haptics and the panel clipper. */
@Composable
internal fun immersionGroup(preferences: LabPreferences): Preference.PreferenceGroup {
    val haptics = remember { Injekt.get<SfxHapticsManager>() }
    val enabled by preferences.sfxHapticsEnabled().collectAsState()
    val hasVibrator = remember { haptics.hasVibrator() }

    return Preference.PreferenceGroup(
        title = stringResource(KSR.strings.immersion_group),
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.sfxHapticsEnabled(),
                title = stringResource(KSR.strings.sfx_enable),
                subtitle = stringResource(
                    if (hasVibrator) KSR.strings.sfx_enable_summary else KSR.strings.sfx_no_vibrator,
                ),
                enabled = hasVibrator,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.sfxHapticsLanguage(),
                entries = SFX_LANGUAGES.associateWith(::languageName).toImmutableMap(),
                title = stringResource(KSR.strings.sfx_language),
                enabled = enabled,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.sfxHapticsIntensity(),
                entries = persistentMapOf(25 to "25%", 50 to "50%", 75 to "75%", 100 to "100%"),
                title = stringResource(KSR.strings.sfx_intensity),
                enabled = enabled,
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KSR.strings.sfx_try),
                subtitle = stringResource(KSR.strings.sfx_try_summary),
                enabled = enabled && hasVibrator,
                onClick = { haptics.preview(SfxKind.HEARTBEAT) },
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.panelClipperEnabled(),
                title = stringResource(KSR.strings.clip_enable),
                subtitle = stringResource(KSR.strings.clip_enable_summary),
            ),
        ),
    )
}

/** The "Smart downloads" group. */
@Composable
internal fun smartDownloadsGroup(preferences: LabPreferences): Preference.PreferenceGroup {
    val context = LocalContext.current
    val enabled by preferences.smartDownloadsEnabled().collectAsState()
    val charging by preferences.smartDownloadsRequireCharging().collectAsState()
    val unmetered by preferences.smartDownloadsUnmeteredOnly().collectAsState()
    // Keep the WorkManager schedule in sync with the settings.
    LaunchedEffect(enabled, charging, unmetered) { SmartDownloadJob.setupTask(context) }

    return Preference.PreferenceGroup(
        title = stringResource(KSR.strings.smart_downloads_group),
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.smartDownloadsEnabled(),
                title = stringResource(KSR.strings.smart_downloads_enable),
                subtitle = stringResource(KSR.strings.smart_downloads_enable_summary),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.smartDownloadsAhead(),
                entries = listOf(1, 2, 3, 5, 10)
                    .associateWith { stringResource(KSR.strings.smart_downloads_ahead_value, it) }
                    .toImmutableMap(),
                title = stringResource(KSR.strings.smart_downloads_ahead),
                enabled = enabled,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.smartDownloadsActiveDays(),
                entries = listOf(7, 14, 30, 90)
                    .associateWith { stringResource(KSR.strings.smart_downloads_days_value, it) }
                    .toImmutableMap(),
                title = stringResource(KSR.strings.smart_downloads_active),
                enabled = enabled,
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.smartDownloadsRequireCharging(),
                title = stringResource(KSR.strings.smart_downloads_charging),
                enabled = enabled,
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.smartDownloadsUnmeteredOnly(),
                title = stringResource(KSR.strings.smart_downloads_unmetered),
                enabled = enabled,
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KSR.strings.smart_downloads_run_now),
                subtitle = stringResource(KSR.strings.smart_downloads_run_now_summary),
                enabled = enabled,
                onClick = {
                    SmartDownloadJob.runNow(context)
                    context.toast(KSR.strings.smart_downloads_started)
                },
            ),
        ),
    )
}

private enum class LockDialog { SET_PIN, CHANGE_PIN, DISABLE, SET_DECOY, REMOVE_DECOY, DISABLE_PRIVATE }

/** The "Privacy and app lock" group: private series, PIN lock, biometrics and the decoy PIN. */
@Composable
internal fun appLockGroup(preferences: LabPreferences): Preference.PreferenceGroup {
    val context = LocalContext.current
    val appLock = remember { Injekt.get<AppLock>() }
    val lockPref by preferences.appLockEnabled().collectAsState()
    var hasPin by remember { mutableStateOf(appLock.hasPin()) }
    var hasDecoy by remember { mutableStateOf(appLock.hasDecoyPin()) }
    val lockOn = lockPref && hasPin
    var dialog by remember { mutableStateOf<LockDialog?>(null) }

    val categories by remember { Injekt.get<GetCategories>().subscribe() }.collectAsState(emptyList())
    val decoyEntries = buildMap {
        put(-1L, stringResource(KSR.strings.lock_decoy_empty))
        categories.filter { !it.isSystemCategory }.forEach { put(it.id, it.name) }
    }.toImmutableMap()

    fun refresh() {
        hasPin = appLock.hasPin()
        hasDecoy = appLock.hasDecoyPin()
    }

    val wrongPin = stringResource(KSR.strings.lock_wrong_pin)
    val invalidPin = stringResource(KSR.strings.lock_pin_invalid, PinHasher.MIN_LENGTH, PinHasher.MAX_LENGTH)
    val mismatch = stringResource(KSR.strings.lock_pin_mismatch)
    val sameAsReal = stringResource(KSR.strings.lock_decoy_same)

    fun newPinError(pin: String, confirm: String): String? = when {
        !PinHasher.isValidPin(pin) -> invalidPin
        pin != confirm -> mismatch
        else -> null
    }

    when (dialog) {
        LockDialog.SET_PIN -> PinDialog(
            title = stringResource(KSR.strings.lock_set_pin),
            labels = listOf(stringResource(KSR.strings.lock_new_pin), stringResource(KSR.strings.lock_confirm_pin)),
            onDismiss = { dialog = null },
            onConfirm = { (pin, confirm) ->
                newPinError(pin, confirm) ?: run {
                    appLock.enable(pin)
                    refresh()
                    context.toast(KSR.strings.lock_enabled)
                    null
                }
            },
        )
        LockDialog.CHANGE_PIN -> PinDialog(
            title = stringResource(KSR.strings.lock_change_pin),
            labels = listOf(
                stringResource(KSR.strings.lock_current_pin),
                stringResource(KSR.strings.lock_new_pin),
                stringResource(KSR.strings.lock_confirm_pin),
            ),
            onDismiss = { dialog = null },
            onConfirm = { (current, pin, confirm) ->
                when {
                    !appLock.isRealPin(current) -> wrongPin
                    else -> newPinError(pin, confirm) ?: run {
                        appLock.enable(pin)
                        refresh()
                        null
                    }
                }
            },
        )
        LockDialog.DISABLE -> PinDialog(
            title = stringResource(KSR.strings.lock_disable),
            labels = listOf(stringResource(KSR.strings.lock_current_pin)),
            onDismiss = { dialog = null },
            onConfirm = { (current) ->
                if (!appLock.isRealPin(current)) {
                    wrongPin
                } else {
                    appLock.disable()
                    refresh()
                    null
                }
            },
        )
        LockDialog.SET_DECOY -> PinDialog(
            title = stringResource(KSR.strings.lock_decoy_set),
            message = stringResource(KSR.strings.lock_decoy_set_message),
            labels = listOf(stringResource(KSR.strings.lock_new_pin), stringResource(KSR.strings.lock_confirm_pin)),
            onDismiss = { dialog = null },
            onConfirm = { (pin, confirm) ->
                newPinError(pin, confirm) ?: if (appLock.setDecoyPin(pin)) {
                    refresh()
                    null
                } else {
                    sameAsReal
                }
            },
        )
        LockDialog.REMOVE_DECOY -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(KSR.strings.lock_decoy_remove)) },
            text = { Text(stringResource(KSR.strings.lock_decoy_remove_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        appLock.removeDecoyPin()
                        refresh()
                        dialog = null
                    },
                ) { Text(stringResource(MR.strings.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
        LockDialog.DISABLE_PRIVATE -> PinDialog(
            title = stringResource(KSR.strings.private_enable),
            message = stringResource(KSR.strings.private_disable_message),
            labels = listOf(stringResource(KSR.strings.lock_current_pin)),
            onDismiss = { dialog = null },
            onConfirm = { (current) ->
                if (!appLock.isRealPin(current)) {
                    wrongPin
                } else {
                    preferences.privateSeriesEnabled().set(false)
                    null
                }
            },
        )
        null -> Unit
    }

    return Preference.PreferenceGroup(
        title = stringResource(KSR.strings.lock_group),
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.privateSeriesEnabled(),
                title = stringResource(KSR.strings.private_enable),
                subtitle = stringResource(KSR.strings.private_enable_summary),
                onValueChanged = { enable ->
                    // Turning it off shows every private series, so it needs the PIN like the More switch.
                    if (!enable && lockOn) {
                        dialog = LockDialog.DISABLE_PRIVATE
                        false
                    } else {
                        true
                    }
                },
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.appLockEnabled(),
                title = stringResource(KSR.strings.lock_enable),
                subtitle = stringResource(KSR.strings.lock_enable_summary),
                onValueChanged = { enable ->
                    // The dialogs change the preference once the PIN is confirmed.
                    dialog = when {
                        enable -> LockDialog.SET_PIN
                        hasPin -> LockDialog.DISABLE
                        else -> {
                            // Restored from a backup without its PIN (PINs are never backed up).
                            appLock.disable()
                            null
                        }
                    }
                    false
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KSR.strings.lock_change_pin),
                enabled = lockOn,
                onClick = { dialog = LockDialog.CHANGE_PIN },
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.appLockBiometric(),
                title = stringResource(KSR.strings.lock_biometric),
                subtitle = stringResource(KSR.strings.lock_biometric_summary),
                enabled = lockOn,
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KSR.strings.lock_decoy_pin),
                subtitle = stringResource(
                    if (hasDecoy) KSR.strings.lock_decoy_pin_set else KSR.strings.lock_decoy_pin_summary,
                ),
                enabled = lockOn,
                onClick = { dialog = if (hasDecoy) LockDialog.REMOVE_DECOY else LockDialog.SET_DECOY },
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.decoyCategoryId(),
                entries = decoyEntries,
                title = stringResource(KSR.strings.lock_decoy_category),
                enabled = lockOn && hasDecoy,
            ),
        ),
    )
}

private val SFX_LANGUAGES = listOf("ja", "ko", "zh", "en")

private fun languageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
