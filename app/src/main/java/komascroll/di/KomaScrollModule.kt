package komascroll.di

import android.app.Application
import komascroll.lab.LabPreferences
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
    }
}
