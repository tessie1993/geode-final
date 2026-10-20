package dev.geode.data

import android.content.SharedPreferences
import dev.geode.engine.types.TrackId

class FavouritesStore(
    private val prefs: SharedPreferences,
) {
    private val uris: LinkedHashSet<TrackId> =
        LinkedHashSet(
            prefs
                .getString(KEY, "")
                .orEmpty()
                .split(SEPARATOR)
                .filter { it.isNotBlank() }
                .map(::TrackId),
        )

    fun isFavourite(uri: TrackId): Boolean = uri in uris

    fun toggle(uri: TrackId): Boolean {
        val added = uri !in uris
        uris.remove(uri)
        if (added) uris.add(uri)
        persist()
        return added
    }

    fun remove(uri: TrackId) {
        if (uris.remove(uri)) persist()
    }

    fun all(): List<TrackId> = uris.toList().asReversed()

    val size: Int get() = uris.size

    private fun persist() {
        prefs.edit().putString(KEY, uris.joinToString(SEPARATOR) { it.value }).apply()
    }

    private companion object {
        const val KEY = "uris"

        const val SEPARATOR = "\n"
    }
}
