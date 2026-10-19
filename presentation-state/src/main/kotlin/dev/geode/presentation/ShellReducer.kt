package dev.geode.presentation

/** Pure transitions: invalid/duplicate actions have no effects. */
object ShellReducer {
    fun reduce(
        state: ShellState,
        action: ShellAction,
    ): ShellTransition {
        val transition =
            when (action) {
                is ShellAction.Configure -> ShellTransition(state.copy(preferences = action.preferences, bootEnabled = action.bootEnabled))
                ShellAction.BootFinished ->
                    if (state.stage ==
                        EntryStage.BOOT
                    ) {
                        ShellTransition(state.copy(bootDone = true))
                    } else {
                        ShellTransition(state)
                    }
                ShellAction.AcknowledgeSafety ->
                    if (state.stage == EntryStage.SAFETY) {
                        ShellTransition(
                            state.copy(preferences = state.preferences?.copy(safetyAcknowledged = true)),
                            listOf(ShellEffect.SaveSafetyAcknowledged),
                        )
                    } else {
                        ShellTransition(state)
                    }
                ShellAction.FinishSetup ->
                    if (state.stage == EntryStage.SETUP) {
                        ShellTransition(
                            state.copy(
                                preferences = state.preferences?.copy(setupDone = true),
                                destination = requireNotNull(state.preferences).landingDestination,
                            ),
                            listOf(ShellEffect.SaveSetupDone),
                        )
                    } else {
                        ShellTransition(state)
                    }
                ShellAction.StartTutorial ->
                    if (state.stage ==
                        EntryStage.READY
                    ) {
                        ShellTransition(startTour(state))
                    } else {
                        ShellTransition(state)
                    }
                ShellAction.PreviousTutorialStep -> moveTour(state, -1)
                ShellAction.NextTutorialStep -> moveTour(state, 1)
                ShellAction.DismissTutorial -> finishTour(state)
                ShellAction.Back -> if (state.stage == EntryStage.TUTORIAL) finishTour(state) else reduceNavigation(state, action)
                is ShellAction.SetTutorialOffer ->
                    if (state.stage == EntryStage.TUTORIAL) {
                        ShellTransition(state, listOf(ShellEffect.SaveTutorialOffer(action.enabled)))
                    } else {
                        ShellTransition(state)
                    }
                else -> reduceNavigation(state, action)
            }
        return transition.copy(state = reconcile(transition.state))
    }

    private fun reduceNavigation(
        state: ShellState,
        action: ShellAction,
    ): ShellTransition {
        if (state.stage != EntryStage.READY) return ShellTransition(state)
        return ShellTransition(
            when (action) {
                is ShellAction.Navigate -> state.copy(destination = action.destination, overlay = ShellOverlay.NONE)
                is ShellAction.Open -> state.copy(overlay = action.overlay)
                ShellAction.CloseOverlay -> state.copy(overlay = ShellOverlay.NONE)
                ShellAction.Back ->
                    if (state.overlay !=
                        ShellOverlay.NONE
                    ) {
                        state.copy(overlay = ShellOverlay.NONE)
                    } else {
                        state.copy(destination = GeodeDestination.PLAYER)
                    }
                else -> state
            },
        )
    }

    private fun startTour(state: ShellState): ShellState =
        state.copy(
            tourStop = TourStop.PLAYER,
            destination = GeodeDestination.PLAYER,
            overlay = ShellOverlay.NONE,
            tutorialOffered = true,
        )

    private fun finishTour(state: ShellState): ShellTransition =
        if (state.stage == EntryStage.TUTORIAL) {
            ShellTransition(
                state.copy(tourStop = null, tutorialOffered = true, preferences = state.preferences?.copy(tutorialSeen = true)),
                listOf(ShellEffect.SaveTutorialSeen),
            )
        } else {
            ShellTransition(state)
        }

    private fun moveTour(
        state: ShellState,
        delta: Int,
    ): ShellTransition {
        if (state.stage != EntryStage.TUTORIAL) return ShellTransition(state)
        val steps = state.tourStops
        val next = steps.indexOf(state.tourStop) + delta
        if (next >= steps.size) return finishTour(state)
        val step = steps[next.coerceAtLeast(0)]
        return ShellTransition(state.copy(tourStop = step, destination = step.destination))
    }

    private fun reconcile(state: ShellState): ShellState {
        var result = state
        val offerRequested = result.preferences?.let { !it.tutorialSeen && it.tutorialOnFirstRun } == true
        if (result.stage == EntryStage.READY && !result.tutorialOffered && offerRequested) {
            result = startTour(result)
        }
        if (result.stage == EntryStage.TUTORIAL) {
            val stop = result.tourStop?.takeIf { it in result.tourStops } ?: TourStop.SETTINGS
            result = result.copy(tourStop = stop, destination = stop.destination)
        }
        return if (result.stage != EntryStage.READY) result.copy(overlay = ShellOverlay.NONE) else result
    }
}
