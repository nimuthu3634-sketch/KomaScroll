package komascroll.presentation.lab

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.util.system.toast
import komascroll.i18n.KSR
import komascroll.lab.LabPreferences
import komascroll.upscale.UpscaleCache
import komascroll.upscale.engine.UpscaleEngine
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
        val preferences = remember { Injekt.get<LabPreferences>() }
        return listOf(
            translateGroup(preferences),
            getUpscaleGroup(preferences),
            Preference.PreferenceGroup(
                title = stringResource(KSR.strings.guided_group),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.guidedEnabled(),
                        title = stringResource(KSR.strings.guided_enable),
                        subtitle = stringResource(KSR.strings.guided_enable_summary),
                    ),
                ),
            ),
        )
    }

    @Composable
    private fun getUpscaleGroup(preferences: LabPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val engine = remember { Injekt.get<UpscaleEngine>() }
        val cache = remember { Injekt.get<UpscaleCache>() }

        val enabled by preferences.upscaleEnabled().collectAsState()

        // Probing initializes Vulkan, so only do it once the user has opted in.
        var support by remember { mutableStateOf<UpscaleEngine.Support?>(null) }
        var supportRefresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(enabled, supportRefresh) {
            support = if (enabled) withIOContext { engine.support() } else null
        }

        var cacheRefresh by remember { mutableIntStateOf(0) }
        var cacheSize by remember { mutableStateOf("") }
        LaunchedEffect(cacheRefresh) {
            cacheSize = Formatter.formatFileSize(context, withIOContext { cache.size() })
        }

        val supportText = when (val current = support) {
            null -> stringResource(KSR.strings.upscale_device_unknown)
            is UpscaleEngine.Support.Gpu -> stringResource(KSR.strings.upscale_device_gpu, current.deviceName)
            is UpscaleEngine.Support.CpuOnly -> when (current.reason) {
                UpscaleEngine.Reason.NO_VULKAN -> stringResource(KSR.strings.upscale_device_no_vulkan)
                UpscaleEngine.Reason.GPU_CRASHED -> stringResource(KSR.strings.upscale_device_gpu_crashed)
            }
            UpscaleEngine.Support.Unavailable -> stringResource(KSR.strings.upscale_device_unavailable)
        }
        val gpuCrashed = (support as? UpscaleEngine.Support.CpuOnly)?.reason == UpscaleEngine.Reason.GPU_CRASHED

        return Preference.PreferenceGroup(
            title = stringResource(KSR.strings.upscale_group),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = preferences.upscaleEnabled(),
                    title = stringResource(KSR.strings.upscale_enable),
                    subtitle = stringResource(KSR.strings.upscale_enable_summary),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KSR.strings.upscale_device),
                    subtitle = supportText,
                    enabled = enabled,
                    onClick = if (gpuCrashed) {
                        {
                            engine.resetGpuCrashGuard()
                            supportRefresh++
                        }
                    } else {
                        null
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.upscaleScale(),
                    entries = persistentMapOf(
                        2 to stringResource(KSR.strings.upscale_scale_2x),
                        4 to stringResource(KSR.strings.upscale_scale_4x),
                    ),
                    title = stringResource(KSR.strings.upscale_scale),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.upscaleMaxSourceWidth(),
                    entries = persistentMapOf(
                        600 to stringResource(KSR.strings.upscale_width_px, 600),
                        800 to stringResource(KSR.strings.upscale_width_px, 800),
                        1000 to stringResource(KSR.strings.upscale_width_px, 1000),
                        1200 to stringResource(KSR.strings.upscale_width_px, 1200),
                        1600 to stringResource(KSR.strings.upscale_width_px, 1600),
                        0 to stringResource(KSR.strings.upscale_width_any),
                    ),
                    title = stringResource(KSR.strings.upscale_max_width),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = preferences.upscaleOnlyWhenCharging(),
                    title = stringResource(KSR.strings.upscale_only_charging),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = preferences.upscaleAllowCpu(),
                    title = stringResource(KSR.strings.upscale_allow_cpu),
                    subtitle = stringResource(KSR.strings.upscale_allow_cpu_summary),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.upscaleCacheSizeMb(),
                    entries = persistentMapOf(
                        100 to "100 MB",
                        250 to "250 MB",
                        500 to "500 MB",
                        1000 to "1 GB",
                        2000 to "2 GB",
                    ),
                    title = stringResource(KSR.strings.upscale_cache_limit),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KSR.strings.upscale_cache_clear),
                    subtitle = stringResource(KSR.strings.upscale_cache_used, cacheSize),
                    onClick = {
                        scope.launch {
                            val deleted = withIOContext { cache.clear() }
                            context.toast(context.stringResource(KSR.strings.upscale_cache_cleared, deleted))
                            cacheRefresh++
                        }
                    },
                ),
            ),
        )
    }
}
