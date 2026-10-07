package komascroll.library.privacy

import komascroll.lab.LabPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import uy.kohesive.injekt.injectLazy

/**
 * Private series: series the user marked by hand. While "Hide private series" is on they are left
 * out of the library, History, Updates and Reading Wrapped; switching it off shows them again.
 * Only the set of series IDs is stored; nothing is deleted or moved.
 */
object PrivateSeries {
    private val preferences: LabPreferences by injectLazy()

    private fun markedIds(): Flow<Set<Long>> =
        preferences.privateSeriesIds().changes().map { ids -> ids.mapNotNull(String::toLongOrNull).toSet() }

    /** Whether [mangaId] is marked private (whether or not hiding is on). */
    fun isMarked(mangaId: Long): Flow<Boolean> = markedIds().map { mangaId in it }

    fun setMarked(mangaId: Long, marked: Boolean) {
        val pref = preferences.privateSeriesIds()
        val id = mangaId.toString()
        pref.set(if (marked) pref.get() + id else pref.get() - id)
    }

    /** IDs to hide right now: the marked series while the feature and hiding are on, otherwise none. */
    fun hiddenIds(): Flow<Set<Long>> =
        combine(
            preferences.privateSeriesEnabled().changes(),
            preferences.hidePrivateSeries().changes(),
            markedIds(),
        ) { enabled, hide, ids -> if (enabled && hide) ids else emptySet() }

    suspend fun currentlyHidden(): Set<Long> = hiddenIds().first()

    /** [flow] without the items that belong to a currently hidden series. */
    fun <T> exclude(flow: Flow<List<T>>, mangaId: (T) -> Long): Flow<List<T>> =
        combine(flow, hiddenIds()) { items, hidden ->
            if (hidden.isEmpty()) items else items.filter { mangaId(it) !in hidden }
        }
}
