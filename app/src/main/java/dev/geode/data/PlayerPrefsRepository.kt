package dev.geode.data

import kotlinx.coroutines.flow.StateFlow

/** The old player settings as the old screens read and save them; Settings and Playback keep them (#17). */
interface PlayerPrefsRepository {
    val prefs: StateFlow<PlayerPrefs>

    suspend fun loaded(): PlayerPrefs

    suspend fun update(transform: (PlayerPrefs) -> PlayerPrefs)
}
