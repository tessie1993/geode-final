package dev.geode.data

import android.content.SharedPreferences
import dev.geode.engine.types.TrackId

/** The old favourites, read once to move them into the Library (#17). The Library keeps them now. */
class FavouritesStore(
    private val prefs: SharedPreferences,
) {
    /** The newest first. */
    fun all(): List<TrackId> =
        prefs
            .getString(KEY, "")
            .orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
            .map(::TrackId)
            .distinct()
            .asReversed()

    private companion object {
        const val KEY = "uris"

        const val SEPARATOR = "\n"
    }
}
