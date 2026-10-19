package dev.geode.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellReducerTest {
    private val firstRun = ShellPreferences(false, false, false, true, true, GeodeDestination.PLAYER)
    private val returning = firstRun.copy(safetyAcknowledged = true, setupDone = true, tutorialSeen = true)

    private fun apply(
        state: ShellState,
        action: ShellAction,
    ) = ShellReducer.reduce(state, action).state

    @Test
    fun `entry gates cannot be bypassed and duplicate acknowledgements have no effects`() {
        var state = ShellState()
        assertEquals(state, apply(state, ShellAction.FinishSetup))
        state = apply(state, ShellAction.Configure(firstRun, true))
        assertEquals(EntryStage.BOOT, state.stage)
        assertEquals(state, apply(state, ShellAction.StartTutorial))
        state = apply(state, ShellAction.BootFinished)
        assertEquals(EntryStage.SAFETY, state.stage)
        val acknowledgement = ShellReducer.reduce(state, ShellAction.AcknowledgeSafety)
        assertEquals(listOf(ShellEffect.SaveSafetyAcknowledged), acknowledgement.effects)
        state = acknowledgement.state
        assertEquals(EntryStage.SETUP, state.stage)
        assertTrue(ShellReducer.reduce(state, ShellAction.AcknowledgeSafety).effects.isEmpty())
        state = apply(state, ShellAction.FinishSetup)
        assertEquals(EntryStage.TUTORIAL, state.stage)
        assertTrue(state.canGoBack)
        state = apply(state, ShellAction.Back)
        assertEquals(EntryStage.READY, state.stage)
        assertTrue(ShellReducer.reduce(state, ShellAction.DismissTutorial).effects.isEmpty())
    }

    @Test
    fun `overlay replacement and back have a single deterministic priority`() {
        var state = apply(ShellState(), ShellAction.Configure(returning, false))
        state = apply(state, ShellAction.Navigate(GeodeDestination.LIBRARY))
        state = apply(state, ShellAction.Open(ShellOverlay.SEARCH))
        state = apply(state, ShellAction.Open(ShellOverlay.PLAYER))
        assertEquals(ShellOverlay.PLAYER, state.overlay)
        state = apply(state, ShellAction.Back)
        assertEquals(ShellOverlay.NONE, state.overlay)
        assertEquals(GeodeDestination.LIBRARY, state.destination)
        state = apply(state, ShellAction.Back)
        assertEquals(GeodeDestination.PLAYER, state.destination)
        assertFalse(state.canGoBack)
    }

    @Test
    fun `tutorial preference change does not dismiss current tour or offer it twice`() {
        var state = apply(ShellState(), ShellAction.Configure(returning.copy(tutorialSeen = false), false))
        assertEquals(EntryStage.TUTORIAL, state.stage)
        state = apply(state, ShellAction.Configure(returning.copy(tutorialSeen = false, tutorialOnFirstRun = false), false))
        assertEquals(EntryStage.TUTORIAL, state.stage)
        state = apply(state, ShellAction.DismissTutorial)
        state = apply(state, ShellAction.Configure(returning.copy(tutorialSeen = false), false))
        assertEquals(EntryStage.READY, state.stage)
    }

    @Test
    fun `tour bounds hidden studio and final transition are safe`() {
        var state = apply(ShellState(), ShellAction.Configure(returning.copy(showsStudio = false), false))
        state = apply(state, ShellAction.StartTutorial)
        assertEquals(state, apply(state, ShellAction.PreviousTutorialStep))
        repeat(state.tourStops.size - 1) {
            state = apply(state, ShellAction.NextTutorialStep)
            assertTrue(state.tourStop in state.tourStops)
            assertEquals(state.tourStop?.destination, state.destination)
        }
        assertEquals(TourStop.SETTINGS, state.tourStop)
        val end = ShellReducer.reduce(state, ShellAction.NextTutorialStep)
        assertEquals(EntryStage.READY, end.state.stage)
        assertEquals(listOf(ShellEffect.SaveTutorialSeen), end.effects)
    }

    @Test
    fun `restoration waits for preferences and rejects malformed or obsolete snapshots`() {
        val ready = apply(ShellState(), ShellAction.Configure(returning, false))
        val tour = apply(apply(ready, ShellAction.StartTutorial), ShellAction.NextTutorialStep)
        val restored = ShellSnapshot.restore(ShellSnapshot.save(tour))
        assertEquals(EntryStage.LOADING, restored.stage)
        assertEquals(TourStop.LIBRARY, apply(restored, ShellAction.Configure(returning, false)).tourStop)
        assertEquals(ShellState(), ShellSnapshot.restore(listOf("99")))
        val damaged = listOf("1", "unknown", "unknown", "not bool", "unknown", "not bool")
        assertEquals(ShellState(), ShellSnapshot.restore(damaged))
        val export = ready.copy(overlay = ShellOverlay.EXPORT)
        assertEquals(ShellOverlay.NONE, ShellSnapshot.restore(ShellSnapshot.save(export)).overlay)
    }

    @Test
    fun `every action preserves overlay exclusivity during gated flows`() {
        val actions =
            listOf(
                ShellAction.BootFinished,
                ShellAction.AcknowledgeSafety,
                ShellAction.FinishSetup,
                ShellAction.StartTutorial,
                ShellAction.NextTutorialStep,
                ShellAction.PreviousTutorialStep,
                ShellAction.DismissTutorial,
                ShellAction.Back,
                ShellAction.Open(ShellOverlay.SEARCH),
                ShellAction.Navigate(GeodeDestination.VISUALS),
            )
        var frontier = setOf(apply(ShellState(), ShellAction.Configure(firstRun, true)))
        repeat(7) {
            frontier = frontier.flatMap { state -> actions.map { apply(state, it) } }.toSet()
            frontier.forEach { state ->
                if (state.stage != EntryStage.READY) assertEquals(ShellOverlay.NONE, state.overlay)
                if (state.stage == EntryStage.TUTORIAL) assertTrue(state.tourStop in state.tourStops)
            }
        }
    }
}
