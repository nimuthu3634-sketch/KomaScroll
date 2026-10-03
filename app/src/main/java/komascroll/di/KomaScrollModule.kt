package komascroll.di

import komascroll.lab.LabPreferences
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

/**
 * Dependency registrations for KomaScroll-only code. Imported once from [eu.kanade.tachiyomi.App].
 */
class KomaScrollModule : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory { LabPreferences(get()) }
    }
}
