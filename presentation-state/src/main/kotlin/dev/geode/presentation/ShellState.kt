package dev.geode.presentation

enum class GeodeDestination { PLAYER, LIBRARY, VISUALS, STUDIO, SETTINGS }

enum class ShellOverlay { NONE, SEARCH, PLAYER, EXPORT }

enum class EntryStage { LOADING, BOOT, SAFETY, SETUP, TUTORIAL, READY }

enum class TourStop(
    val destination: GeodeDestination,
) {
    PLAYER(GeodeDestination.PLAYER),
    LIBRARY(GeodeDestination.LIBRARY),
    VISUALS(GeodeDestination.VISUALS),
    TOUCH(GeodeDestination.VISUALS),
    STUDIO(GeodeDestination.STUDIO),
    SETTINGS(GeodeDestination.SETTINGS),
}

data class ShellPreferences(
    val safetyAcknowledged: Boolean,
    val setupDone: Boolean,
    val tutorialSeen: Boolean,
    val tutorialOnFirstRun: Boolean,
    val showsStudio: Boolean,
    val landingDestination: GeodeDestination,
)

/** Immutable presentation state. Android objects, resource IDs and effects never live here. */
data class ShellState(
    val destination: GeodeDestination = GeodeDestination.PLAYER,
    val overlay: ShellOverlay = ShellOverlay.NONE,
    val bootDone: Boolean = false,
    val bootEnabled: Boolean = true,
    val preferences: ShellPreferences? = null,
    val tourStop: TourStop? = null,
    val tutorialOffered: Boolean = false,
) {
    val stage: EntryStage
        get() =
            when {
                preferences == null -> EntryStage.LOADING
                bootEnabled && !bootDone -> EntryStage.BOOT
                !preferences.safetyAcknowledged -> EntryStage.SAFETY
                !preferences.setupDone -> EntryStage.SETUP
                tourStop != null -> EntryStage.TUTORIAL
                else -> EntryStage.READY
            }

    val tourStops: List<TourStop>
        get() = TourStop.entries.filter { it != TourStop.STUDIO || preferences?.showsStudio == true }

    val onPlayer: Boolean get() = destination == GeodeDestination.PLAYER
    val canGoBack: Boolean get() =
        stage == EntryStage.TUTORIAL ||
            (stage == EntryStage.READY && (overlay != ShellOverlay.NONE || !onPlayer))
}

sealed interface ShellAction {
    data class Configure(
        val preferences: ShellPreferences,
        val bootEnabled: Boolean,
    ) : ShellAction

    data class Navigate(
        val destination: GeodeDestination,
    ) : ShellAction

    data class Open(
        val overlay: ShellOverlay,
    ) : ShellAction

    data object CloseOverlay : ShellAction

    data object Back : ShellAction

    data object BootFinished : ShellAction

    data object AcknowledgeSafety : ShellAction

    data object FinishSetup : ShellAction

    data object StartTutorial : ShellAction

    data object PreviousTutorialStep : ShellAction

    data object NextTutorialStep : ShellAction

    data object DismissTutorial : ShellAction

    data class SetTutorialOffer(
        val enabled: Boolean,
    ) : ShellAction
}

sealed interface ShellEffect {
    data object SaveSafetyAcknowledged : ShellEffect

    data object SaveSetupDone : ShellEffect

    data object SaveTutorialSeen : ShellEffect

    data class SaveTutorialOffer(
        val enabled: Boolean,
    ) : ShellEffect
}

data class ShellTransition(
    val state: ShellState,
    val effects: List<ShellEffect> = emptyList(),
)
