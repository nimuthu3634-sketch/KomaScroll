package komascroll.lab

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Preferences for KomaScroll Lab features.
 *
 * Every Lab feature gets its own on/off toggle here. Heavy features (ML, image processing) default to off.
 * All keys are prefixed so they never collide with upstream preference keys.
 */
class LabPreferences(
    private val preferenceStore: PreferenceStore,
) {

    // AI upscaling (Phase 1)

    /** Master switch for the feature. Heavy (GPU + battery), so off by default. */
    fun upscaleEnabled() = preferenceStore.getBoolean(key("upscale_enabled"), false)

    /** Reader-side toggle, shown in the reader settings sheet once the feature is enabled. */
    fun upscaleInReader() = preferenceStore.getBoolean(key("upscale_in_reader"), true)

    /** Upscale factor: 2 or 4. */
    fun upscaleScale() = preferenceStore.getInt(key("upscale_scale"), 2)

    fun upscaleOnlyWhenCharging() = preferenceStore.getBoolean(key("upscale_only_when_charging"), false)

    /** Only pages narrower than this (in px) are upscaled; 0 means no limit. */
    fun upscaleMaxSourceWidth() = preferenceStore.getInt(key("upscale_max_source_width"), 1200)

    /** Allow the (very slow) CPU backend on devices without a usable Vulkan GPU. */
    fun upscaleAllowCpu() = preferenceStore.getBoolean(key("upscale_allow_cpu"), false)

    /** Disk cache limit for upscaled pages, in MB. */
    fun upscaleCacheSizeMb() = preferenceStore.getInt(key("upscale_cache_size_mb"), 500)

    private fun key(name: String) = KEY_PREFIX + name

    private companion object {
        const val KEY_PREFIX = "komascroll_lab_"
    }
}
