package dev.geode.ui

import kotlinx.coroutines.flow.StateFlow

/** The App UI's settings as the old screens read and save them; Settings keeps them (#17). */
interface UserDataRepository {
    val guiPrefs: StateFlow<GuiPrefs>

    val loaded: StateFlow<Boolean>

    suspend fun setGuiPrefs(prefs: GuiPrefs)
}
