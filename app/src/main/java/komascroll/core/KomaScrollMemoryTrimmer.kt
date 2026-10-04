package komascroll.core

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import komascroll.immersion.SfxHapticsManager
import komascroll.panels.PanelManager
import komascroll.translate.TranslationManager
import komascroll.upscale.UpscaleManager
import uy.kohesive.injekt.injectLazy

/**
 * Frees KomaScroll's heavy in-memory state when the app goes to the background or Android runs low
 * on memory: ML Kit OCR models, the upscaler's GPU model and small result caches. Everything is
 * reloaded lazily on next use; disk caches are kept.
 */
class KomaScrollMemoryTrimmer : ComponentCallbacks2 {

    private val translationManager: TranslationManager by injectLazy()
    private val upscaleManager: UpscaleManager by injectLazy()
    private val sfxHaptics: SfxHapticsManager by injectLazy()
    private val panelManager: PanelManager by injectLazy()

    override fun onTrimMemory(level: Int) {
        // Background (UI hidden) or, on older Android versions, running low while in use.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trim()
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = trim()

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun trim() {
        translationManager.trimMemory()
        upscaleManager.trimMemory()
        sfxHaptics.trimMemory()
        panelManager.trimMemory()
    }
}
