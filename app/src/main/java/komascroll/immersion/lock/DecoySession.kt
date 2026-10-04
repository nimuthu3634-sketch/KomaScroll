package komascroll.immersion.lock

import komascroll.lab.LabPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.GetLibraryManga
import uy.kohesive.injekt.injectLazy

/**
 * Whether the app was unlocked with the decoy PIN. Kept in memory only: it ends on the next real
 * unlock, and a killed process always starts locked again.
 */
object DecoySession {
    private val state = MutableStateFlow(false)

    val active: StateFlow<Boolean> = state.asStateFlow()

    val isActive: Boolean get() = state.value

    fun start() {
        state.value = true
    }

    fun end() {
        state.value = false
    }
}

/**
 * Filters for the decoy library. Outside a decoy session they pass everything through; during one,
 * the library, history and updates only show series from the chosen decoy category.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object DecoyLibrary {
    private val preferences: LabPreferences by injectLazy()
    private val getLibraryManga: GetLibraryManga by injectLazy()

    /** Library categories: only the decoy category during a decoy session. */
    fun categories(flow: Flow<List<Category>>): Flow<List<Category>> =
        DecoySession.active.flatMapLatest { active ->
            if (!active) {
                flow
            } else {
                combine(flow, preferences.decoyCategoryId().changes()) { categories, decoyId ->
                    categories.filter { it.id == decoyId }
                }
            }
        }

    /** Any list of items that belong to a series, e.g. library entries, history or updates. */
    fun <T> byManga(flow: Flow<List<T>>, mangaId: (T) -> Long): Flow<List<T>> =
        DecoySession.active.flatMapLatest { active ->
            if (!active) {
                flow
            } else {
                combine(flow, decoyMangaIds()) { items, ids -> items.filter { mangaId(it) in ids } }
            }
        }

    private fun decoyMangaIds(): Flow<Set<Long>> =
        combine(getLibraryManga.subscribe(), preferences.decoyCategoryId().changes()) { library, decoyId ->
            library.filter { decoyId in it.categories }.map { it.manga.id }.toSet()
        }
}
