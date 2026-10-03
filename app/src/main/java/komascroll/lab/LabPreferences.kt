package komascroll.lab

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Preferences for KomaScroll Lab features.
 *
 * Every Lab feature gets its own on/off toggle here. Heavy features (ML, image processing) default to off.
 * All keys are prefixed so they never collide with upstream preference keys.
 */
@Suppress("unused")
class LabPreferences(
    private val preferenceStore: PreferenceStore,
) {
    private companion object {
        const val KEY_PREFIX = "komascroll_lab_"
    }

    private fun key(name: String) = KEY_PREFIX + name
}
