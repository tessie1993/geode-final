package dev.geode.presentation

/** Stable names, validated fields, no persisted jobs, effects, preferences or platform objects. */
object ShellSnapshot {
    fun save(state: ShellState): List<String> =
        listOf(
            "1",
            state.destination.name,
            state.overlay.name,
            state.bootDone.toString(),
            state.tourStop?.name.orEmpty(),
            state.tutorialOffered.toString(),
        )

    fun restore(values: List<String>?): ShellState {
        if (values?.size != FIELD_COUNT || values.firstOrNull() != "1") return ShellState()
        return ShellState(
            destination = GeodeDestination.entries.firstOrNull { it.name == values[1] } ?: GeodeDestination.PLAYER,
            // External export pickers/jobs are not resumed by restoring the shell.
            overlay = ShellOverlay.entries.firstOrNull { it.name == values[2] && it != ShellOverlay.EXPORT } ?: ShellOverlay.NONE,
            bootDone = values[3].toBooleanStrictOrNull() ?: false,
            tourStop = TourStop.entries.firstOrNull { it.name == values[4] },
            tutorialOffered = values[5].toBooleanStrictOrNull() ?: false,
        )
    }

    private const val FIELD_COUNT = 6
}
