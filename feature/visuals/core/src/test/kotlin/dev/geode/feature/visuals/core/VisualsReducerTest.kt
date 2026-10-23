package dev.geode.feature.visuals.core

import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.feature.visuals.core.Fixtures.command
import dev.geode.feature.visuals.core.Fixtures.fresh
import dev.geode.feature.visuals.core.Fixtures.gone
import dev.geode.feature.visuals.core.Fixtures.ink
import dev.geode.feature.visuals.core.Fixtures.milk
import dev.geode.feature.visuals.core.Fixtures.ready
import dev.geode.feature.visuals.core.Fixtures.reduce
import dev.geode.feature.visuals.core.Fixtures.saved
import dev.geode.feature.visuals.core.Fixtures.tunnel
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Choosing a Style, following the one Customization shows, and reading and saving the library, as a
 * transition table (ADR-0003): each row is a state, an event, and the Next it must give. The library
 * edits are in [LibraryEditsTest].
 */
class VisualsReducerTest {
    private val unloaded = fresh.copy(current = tunnel)
    private val chooseInk = command(VisualsCommand.Choose(ink))
    private val askForInk = Perform(VisualsWork.ChooseStyle(ink))

    private data class Row(
        val name: String,
        val state: VisualsState,
        val event: VisualsEvent,
        val next: Next<VisualsState, VisualsEffect>,
    )

    private fun same(state: VisualsState) = Next<VisualsState, VisualsEffect>(state)

    private val table: List<Row> =
        listOf(
            // Choosing asks Customization; the state moves only when Customization answers.
            Row("choose another Style", ready, chooseInk, Next(ready, listOf(askForInk))),
            Row("choose before the library loads", unloaded, chooseInk, Next(unloaded, listOf(askForInk))),
            Row("choose before Customization has said", fresh, chooseInk, Next(fresh, listOf(askForInk))),
            Row("choose the Style already shown", ready, command(VisualsCommand.Choose(tunnel)), same(ready)),
            Row("choose a Style this version does not draw", ready, command(VisualsCommand.Choose(gone)), same(ready)),
            // Following Customization: every new Style goes to the front of Recent, which is saved.
            Row(
                "Customization shows another Style",
                ready,
                VisualsEvent.CurrentStyle(ink),
                Next(
                    ready.copy(current = ink, library = StyleLibrary(recent = listOf(ink, tunnel))),
                    listOf(saved(StyleLibrary(recent = listOf(ink, tunnel)))),
                ),
            ),
            Row(
                "a Style already in Recent moves to the front",
                ready.copy(current = ink, library = StyleLibrary(recent = listOf(ink, tunnel, milk))),
                VisualsEvent.CurrentStyle(milk),
                Next(
                    ready.copy(current = milk, library = StyleLibrary(recent = listOf(milk, ink, tunnel))),
                    listOf(saved(StyleLibrary(recent = listOf(milk, ink, tunnel)))),
                ),
            ),
            Row("Customization repeats the Style shown", ready, VisualsEvent.CurrentStyle(tunnel), same(ready)),
            Row("Customization's first word, library not loaded", fresh, VisualsEvent.CurrentStyle(tunnel), same(unloaded)),
            Row("another Style, library not loaded", unloaded, VisualsEvent.CurrentStyle(ink), same(fresh.copy(current = ink))),
            // Loading: the Style shown already counts as recent.
            Row(
                "the library loads while a Style is shown",
                unloaded,
                VisualsEvent.LibraryLoaded(StyleLibrary(favourites = listOf(milk), recent = listOf(ink))),
                Next(
                    unloaded.copy(library = StyleLibrary(favourites = listOf(milk), recent = listOf(tunnel, ink))),
                    listOf(saved(StyleLibrary(favourites = listOf(milk), recent = listOf(tunnel, ink)))),
                ),
            ),
            Row(
                "the library loads with the shown Style already first",
                unloaded,
                VisualsEvent.LibraryLoaded(StyleLibrary(recent = listOf(tunnel, ink))),
                same(unloaded.copy(library = StyleLibrary(recent = listOf(tunnel, ink)))),
            ),
            Row(
                "the library loads before Customization has said",
                fresh,
                VisualsEvent.LibraryLoaded(StyleLibrary(recent = listOf(ink))),
                same(fresh.copy(library = StyleLibrary(recent = listOf(ink)))),
            ),
            Row("a second load is stale", ready, VisualsEvent.LibraryLoaded(StyleLibrary(favourites = listOf(ink))), same(ready)),
            // Failures are reported; a library that could not be read stays unloaded, so nothing overwrites it.
            Row(
                "the library cannot be read",
                unloaded,
                VisualsEvent.WorkFailed(WorkFailure.LIBRARY_NOT_LOADED),
                Next(unloaded, listOf(ReportFailure(WorkFailure.LIBRARY_NOT_LOADED.message))),
            ),
            Row(
                "the library cannot be saved",
                ready,
                VisualsEvent.WorkFailed(WorkFailure.LIBRARY_NOT_SAVED),
                Next(ready, listOf(ReportFailure(WorkFailure.LIBRARY_NOT_SAVED.message))),
            ),
            Row(
                "Customization cannot be reached",
                ready,
                VisualsEvent.WorkFailed(WorkFailure.STYLE_NOT_CHOSEN),
                Next(ready, listOf(ReportFailure(WorkFailure.STYLE_NOT_CHOSEN.message))),
            ),
        )

    @Test
    fun `every row of the table`() {
        for (row in table) {
            assertEquals(row.name, row.next, reduce(row.state, row.event))
        }
    }

    @Test
    fun `Recent keeps the latest twelve`() {
        val many = (1..StyleLibrary.RECENT_LIMIT).map { SceneId("s$it") }
        val full = ready.copy(current = many.first(), library = StyleLibrary(recent = many))

        val next = reduce(full, VisualsEvent.CurrentStyle(ink))

        assertEquals(listOf(ink) + many.dropLast(1), next.state.library?.recent)
    }

    @Test
    fun `a library loaded full keeps twelve with the shown Style first`() {
        val many = (1..StyleLibrary.RECENT_LIMIT).map { SceneId("s$it") }

        val next = reduce(unloaded, VisualsEvent.LibraryLoaded(StyleLibrary(recent = many)))

        assertEquals(listOf(tunnel) + many.dropLast(1), next.state.library?.recent)
    }

    @Test
    fun `the store starts by following Customization, reading the library, listing the presets and listing MilkDrop`() {
        assertEquals(
            listOf(
                Perform(VisualsWork.FollowCurrentStyle),
                Perform(VisualsWork.LoadLibrary),
                Perform(VisualsWork.LoadPresets),
                Perform(VisualsWork.ListMilkdrop),
            ),
            VisualsReducer.start,
        )
    }
}
