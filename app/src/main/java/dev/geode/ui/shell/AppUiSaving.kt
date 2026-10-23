package dev.geode.ui.shell

/**
 * The App UI as saved across process death, and the Commands that bring it back (owner: restore
 * everything, full screen too). Saved by name, so a place renamed or removed since falls back to
 * Home. The banner is not saved: a failure belongs to the process that had it.
 */
object AppUiSaving {
    private const val SEPARATOR = ","

    fun save(state: AppUiState): String = listOf(state.place.name, state.fullScreen, state.sheet?.name.orEmpty()).joinToString(SEPARATOR)

    /** The Commands that take a fresh App UI to what [saved] holds; none when nothing was saved. */
    fun restore(saved: String?): List<AppUiCommand> {
        val parts = saved?.split(SEPARATOR) ?: return emptyList()
        val place = Place.named(parts.getOrNull(0))
        val sheet = Sheet.entries.firstOrNull { it.name == parts.getOrNull(2) }
        return listOfNotNull(
            AppUiCommand.Open(place).takeIf { place != Place.START },
            AppUiCommand.EnterFullScreen.takeIf { parts.getOrNull(1) == true.toString() },
            sheet?.let(AppUiCommand::OpenSheet),
        )
    }
}
