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

    // Live raw translation (Phase 2)

    /** Master switch for the feature. Heavy (OCR + translation models), so off by default. */
    fun translateEnabled() = preferenceStore.getBoolean(key("translate_enabled"), false)

    /** Reader-side toggle, shown in the reader settings sheet once the feature is enabled. */
    fun translateInReader() = preferenceStore.getBoolean(key("translate_in_reader"), true)

    /** Language of the raw pages, as a BCP-47 tag (selects the OCR script). */
    fun translateSourceLanguage() = preferenceStore.getString(key("translate_source_language"), "ja")

    /** Language to translate into, as a BCP-47 tag. */
    fun translateTargetLanguage() = preferenceStore.getString(key("translate_target_language"), "en")

    /** Translation engine id: "mlkit" (on-device, default) or "deepl" (online, needs a user API key). */
    fun translateEngine() = preferenceStore.getString(key("translate_engine"), "mlkit")

    /** Only download OCR/translation models over Wi-Fi. */
    fun translateModelsOnWifiOnly() = preferenceStore.getBoolean(key("translate_models_wifi_only"), true)

    /** Disk cache limit for translated pages, in MB. */
    fun translateCacheSizeMb() = preferenceStore.getInt(key("translate_cache_size_mb"), 200)

    // Guided panel view (Phase 3)

    /** Master switch: detect panels and step through them in the paged readers. Off by default. */
    fun guidedEnabled() = preferenceStore.getBoolean(key("guided_enabled"), false)

    /** Reader-side toggle, shown in the reader settings sheet once the feature is enabled. */
    fun guidedInReader() = preferenceStore.getBoolean(key("guided_in_reader"), true)

    private fun key(name: String) = KEY_PREFIX + name

    private companion object {
        const val KEY_PREFIX = "komascroll_lab_"
    }
}
