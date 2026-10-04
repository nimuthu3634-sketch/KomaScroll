package komascroll.di

import android.app.Application
import komascroll.core.SecretStore
import komascroll.lab.LabPreferences
import komascroll.panels.PanelManager
import komascroll.translate.TranslationManager
import komascroll.upscale.UpscaleCache
import komascroll.upscale.UpscaleManager
import komascroll.upscale.engine.UpscaleEngine
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

/**
 * Dependency registrations for KomaScroll-only code. Imported once from [eu.kanade.tachiyomi.App].
 */
class KomaScrollModule(private val app: Application) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory { LabPreferences(get()) }

        // AI upscaling: nothing native is loaded or initialized until the feature is first used.
        addSingletonFactory { UpscaleEngine(app) }
        addSingletonFactory { UpscaleCache(app, get()) }
        addSingletonFactory { UpscaleManager(app, get(), get(), get()) }

        // Live translation: OCR and translation models are only touched once the feature is used.
        addSingletonFactory { SecretStore(app) }
        addSingletonFactory { TranslationManager(app, get(), get(), get(), get(), get()) }

        // Guided panel view: classical panel detection, cached per page.
        addSingletonFactory { PanelManager(app, get()) }
    }
}
