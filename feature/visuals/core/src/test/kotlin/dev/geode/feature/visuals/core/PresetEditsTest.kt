package dev.geode.feature.visuals.core

import dev.geode.engine.types.PresetId
import dev.geode.feature.visuals.api.BuiltInPreset
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.PresetFolderPath
import dev.geode.feature.visuals.api.PresetShare
import dev.geode.feature.visuals.api.PresetShelf
import dev.geode.feature.visuals.api.SavedPreset
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.feature.visuals.core.Fixtures.command
import dev.geode.feature.visuals.core.Fixtures.ink
import dev.geode.feature.visuals.core.Fixtures.ready
import dev.geode.feature.visuals.core.Fixtures.reduce
import dev.geode.feature.visuals.core.Fixtures.tunnel
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Presets browser (#144) as a transition table (ADR-0003): applying, renaming, moving and deleting
 * presets, their folders, sharing and importing, listing, and failures. Each row is a state, an event,
 * and the Next it must give.
 */
class PresetEditsTest {
    private val calmId = PresetId("Calm")
    private val deepId = PresetId("Deep")
    private val deeperId = PresetId("Deeper")
    private val chillId = PresetId("Tunnel · Chill")

    private val night = PresetFolderPath("Night")
    private val late = PresetFolderPath("Night/Late")
    private val nightlife = PresetFolderPath("Nightlife")
    private val party = PresetFolderPath("Party")
    private val top = PresetFolderPath.TOP

    private val calm = SavedPreset(calmId, tunnel)
    private val deep = SavedPreset(deepId, ink, night)
    private val deeper = SavedPreset(deeperId, ink, late)
    private val chill = BuiltInPreset(chillId, tunnel)

    /** Calm at the top, Deep in Night, Deeper in Night/Late; Nightlife and Party are empty. */
    private val shelf =
        PresetShelf(
            builtIn = listOf(chill),
            saved = listOf(calm, deep, deeper),
            folders = listOf(night, late, nightlife, party),
        )

    private val listed = ready.copy(presets = shelf)

    private fun with(change: PresetShelf.() -> PresetShelf) = listed.copy(presets = shelf.change())

    private data class Row(
        val name: String,
        val state: VisualsState,
        val event: VisualsEvent,
        val next: Next<VisualsState, VisualsEffect>,
    )

    private fun same(state: VisualsState) = Next<VisualsState, VisualsEffect>(state)

    private fun changed(
        state: VisualsState,
        work: VisualsWork,
    ) = Next<VisualsState, VisualsEffect>(state, listOf(Perform(work)))

    private fun emits(fact: VisualsFact) = Next<VisualsState, VisualsEffect>(listed, listOf(Emit(fact)))

    private val applying: List<Row> =
        listOf(
            Row(
                "apply a saved preset",
                listed,
                command(VisualsCommand.ApplyPreset(calmId)),
                changed(listed, VisualsWork.ApplySavedPreset(calmId)),
            ),
            Row(
                "apply a built-in preset",
                listed,
                command(VisualsCommand.ApplyPreset(chillId)),
                changed(listed, VisualsWork.ApplyBuiltInPreset(chillId)),
            ),
            Row(
                "a saved preset named like a built-in one is the one applied",
                with { copy(saved = saved + SavedPreset(chillId, ink)) },
                command(VisualsCommand.ApplyPreset(chillId)),
                changed(with { copy(saved = saved + SavedPreset(chillId, ink)) }, VisualsWork.ApplySavedPreset(chillId)),
            ),
            Row("apply a preset that is not there", listed, command(VisualsCommand.ApplyPreset(PresetId("Gone"))), same(listed)),
            Row("apply before the presets are listed", ready, command(VisualsCommand.ApplyPreset(calmId)), same(ready)),
        )

    private val renaming: List<Row> =
        listOf(
            Row(
                "rename a saved preset, tidying the name",
                listed,
                command(VisualsCommand.RenamePreset(calmId, "  Quiet   time ")),
                changed(
                    with { copy(saved = listOf(deep, deeper, calm.copy(id = PresetId("Quiet time")))) },
                    VisualsWork.RenamePreset(calmId, "Quiet time"),
                ),
            ),
            Row(
                "a renamed preset keeps its folder",
                listed,
                command(VisualsCommand.RenamePreset(deepId, "Abyss")),
                changed(
                    with { copy(saved = listOf(deep.copy(id = PresetId("Abyss")), calm, deeper)) },
                    VisualsWork.RenamePreset(deepId, "Abyss"),
                ),
            ),
            Row("rename to a blank name", listed, command(VisualsCommand.RenamePreset(calmId, "   ")), same(listed)),
            Row("rename to another saved preset's name", listed, command(VisualsCommand.RenamePreset(calmId, "Deep")), same(listed)),
            Row("rename to its own name", listed, command(VisualsCommand.RenamePreset(calmId, " Calm ")), same(listed)),
            Row("rename a built-in preset", listed, command(VisualsCommand.RenamePreset(chillId, "Mine")), same(listed)),
            Row("rename before the presets are listed", ready, command(VisualsCommand.RenamePreset(calmId, "Mine")), same(ready)),
        )

    private val moving: List<Row> =
        listOf(
            Row(
                "move a preset into a folder",
                listed,
                command(VisualsCommand.MovePreset(calmId, party)),
                changed(with { copy(saved = listOf(calm.copy(folder = party), deep, deeper)) }, VisualsWork.MovePreset(calmId, party)),
            ),
            Row(
                "move a preset to the top",
                listed,
                command(VisualsCommand.MovePreset(deepId, top)),
                changed(with { copy(saved = listOf(calm, deep.copy(folder = top), deeper)) }, VisualsWork.MovePreset(deepId, top)),
            ),
            Row("move a preset where it already is", listed, command(VisualsCommand.MovePreset(deepId, night)), same(listed)),
            Row(
                "move into a folder that is not there",
                listed,
                command(VisualsCommand.MovePreset(calmId, PresetFolderPath("Nowhere"))),
                same(listed),
            ),
            Row("move a built-in preset", listed, command(VisualsCommand.MovePreset(chillId, party)), same(listed)),
            Row("move before the presets are listed", ready, command(VisualsCommand.MovePreset(calmId, party)), same(ready)),
        )

    private val deleting: List<Row> =
        listOf(
            Row(
                "delete a saved preset",
                listed,
                command(VisualsCommand.DeletePreset(deepId)),
                changed(with { copy(saved = listOf(calm, deeper)) }, VisualsWork.DeletePreset(deepId)),
            ),
            Row("delete a built-in preset", listed, command(VisualsCommand.DeletePreset(chillId)), same(listed)),
            Row("delete a preset that is not there", listed, command(VisualsCommand.DeletePreset(PresetId("Gone"))), same(listed)),
            Row("delete before the presets are listed", ready, command(VisualsCommand.DeletePreset(calmId)), same(ready)),
        )

    private val folders: List<Row> =
        listOf(
            Row(
                "make a folder at the top, safe on the phone",
                listed,
                command(VisualsCommand.CreatePresetFolder(" Chill/out! ")),
                changed(
                    with { copy(folders = listOf(PresetFolderPath("Chill_out_"), night, late, nightlife, party)) },
                    VisualsWork.CreatePresetFolder(PresetFolderPath("Chill_out_")),
                ),
            ),
            Row(
                "make a folder inside another",
                listed,
                command(VisualsCommand.CreatePresetFolder("Early", night)),
                changed(
                    with { copy(folders = listOf(night, PresetFolderPath("Night/Early"), late, nightlife, party)) },
                    VisualsWork.CreatePresetFolder(PresetFolderPath("Night/Early")),
                ),
            ),
            Row("make a folder that is already there", listed, command(VisualsCommand.CreatePresetFolder("Party")), same(listed)),
            Row(
                "make a folder in one that is not there",
                listed,
                command(VisualsCommand.CreatePresetFolder("Early", PresetFolderPath("Nowhere"))),
                same(listed),
            ),
            Row("make a folder with a blank name", listed, command(VisualsCommand.CreatePresetFolder(" ")), same(listed)),
            Row("make a folder before the presets are listed", ready, command(VisualsCommand.CreatePresetFolder("Early")), same(ready)),
            Row(
                "rename a folder: what is in it goes with it, a folder named alike stays",
                listed,
                command(VisualsCommand.RenamePresetFolder(night, "Evening")),
                changed(
                    with {
                        copy(
                            saved =
                                listOf(
                                    calm,
                                    deep.copy(folder = PresetFolderPath("Evening")),
                                    deeper.copy(folder = PresetFolderPath("Evening/Late")),
                                ),
                            folders = listOf(PresetFolderPath("Evening"), PresetFolderPath("Evening/Late"), nightlife, party),
                        )
                    },
                    VisualsWork.RenamePresetFolder(night, PresetFolderPath("Evening")),
                ),
            ),
            Row(
                "rename a folder inside another",
                listed,
                command(VisualsCommand.RenamePresetFolder(late, "Small hours")),
                changed(
                    with {
                        copy(
                            saved = listOf(calm, deep, deeper.copy(folder = PresetFolderPath("Night/Small hours"))),
                            folders = listOf(night, PresetFolderPath("Night/Small hours"), nightlife, party),
                        )
                    },
                    VisualsWork.RenamePresetFolder(late, PresetFolderPath("Night/Small hours")),
                ),
            ),
            Row("rename a folder to a name in use", listed, command(VisualsCommand.RenamePresetFolder(night, "Party")), same(listed)),
            Row("rename a folder to a blank name", listed, command(VisualsCommand.RenamePresetFolder(night, "")), same(listed)),
            Row("rename the top", listed, command(VisualsCommand.RenamePresetFolder(top, "Mine")), same(listed)),
            Row(
                "rename a folder that is not there",
                listed,
                command(VisualsCommand.RenamePresetFolder(PresetFolderPath("Nowhere"), "Mine")),
                same(listed),
            ),
            Row(
                "rename a folder before the presets are listed",
                ready,
                command(VisualsCommand.RenamePresetFolder(night, "Mine")),
                same(ready),
            ),
            Row(
                "delete an empty folder",
                listed,
                command(VisualsCommand.DeletePresetFolder(party)),
                changed(with { copy(folders = listOf(night, late, nightlife)) }, VisualsWork.DeletePresetFolder(party)),
            ),
            Row(
                "delete an empty folder named like a full one",
                listed,
                command(VisualsCommand.DeletePresetFolder(nightlife)),
                changed(with { copy(folders = listOf(night, late, party)) }, VisualsWork.DeletePresetFolder(nightlife)),
            ),
            Row("delete a folder that holds a preset", listed, command(VisualsCommand.DeletePresetFolder(late)), same(listed)),
            Row(
                "delete a folder that holds only a folder",
                with { copy(saved = listOf(calm)) },
                command(VisualsCommand.DeletePresetFolder(night)),
                same(with { copy(saved = listOf(calm)) }),
            ),
            Row("delete the top", listed, command(VisualsCommand.DeletePresetFolder(top)), same(listed)),
            Row(
                "delete a folder that is not there",
                listed,
                command(VisualsCommand.DeletePresetFolder(PresetFolderPath("Nowhere"))),
                same(listed),
            ),
            Row("delete a folder before the presets are listed", ready, command(VisualsCommand.DeletePresetFolder(party)), same(ready)),
        )

    private val link = "geode://preset/abc"

    private val sharing: List<Row> =
        listOf(
            Row(
                "share a saved preset",
                listed,
                command(VisualsCommand.SharePreset(calmId)),
                changed(listed, VisualsWork.EncodePreset(calmId)),
            ),
            Row("share a built-in preset", listed, command(VisualsCommand.SharePreset(chillId)), same(listed)),
            Row("share before the presets are listed", ready, command(VisualsCommand.SharePreset(calmId)), same(ready)),
            Row(
                "a preset that fits a link is shared as one",
                listed,
                VisualsEvent.PresetEncoded(calmId, link),
                emits(VisualsFact.PresetShareReady(PresetShare.Link(calmId, link))),
            ),
            Row(
                "a link of the longest length is still a link",
                listed,
                VisualsEvent.PresetEncoded(calmId, "x".repeat(PresetLinks.MAX_LINK_LENGTH)),
                emits(VisualsFact.PresetShareReady(PresetShare.Link(calmId, "x".repeat(PresetLinks.MAX_LINK_LENGTH)))),
            ),
            Row(
                "a preset too big for a link is shared as its file",
                listed,
                VisualsEvent.PresetEncoded(calmId, "x".repeat(PresetLinks.MAX_LINK_LENGTH + 1)),
                emits(VisualsFact.PresetShareReady(PresetShare.File(calmId))),
            ),
        )

    private val shared = SavedPreset(PresetId("Shared"), ink)

    private val importing: List<Row> =
        listOf(
            Row(
                "import the link in a message",
                listed,
                command(VisualsCommand.ImportPresetLink("look at this: $link !")),
                changed(listed, VisualsWork.ImportPresetLink(link)),
            ),
            Row(
                "import text that holds no link",
                listed,
                command(VisualsCommand.ImportPresetLink("hello")),
                emits(VisualsFact.PresetNotImported(ImportProblem.NO_LINK)),
            ),
            Row(
                "import a file",
                listed,
                command(VisualsCommand.ImportPresetFile("content://doc/1")),
                changed(listed, VisualsWork.ImportPresetFile("content://doc/1")),
            ),
            Row(
                "an imported preset joins the shelf",
                listed,
                VisualsEvent.PresetImported(shared),
                Next(with { copy(saved = listOf(calm, deep, deeper, shared)) }, listOf(Emit(VisualsFact.PresetImported(shared.id)))),
            ),
            Row(
                "an imported preset replaces one saved under its name",
                listed,
                VisualsEvent.PresetImported(SavedPreset(calmId, ink)),
                Next(
                    with { copy(saved = listOf(SavedPreset(calmId, ink), deep, deeper)) },
                    listOf(Emit(VisualsFact.PresetImported(calmId))),
                ),
            ),
            Row(
                "a preset imported before the presets are listed",
                ready,
                VisualsEvent.PresetImported(shared),
                Next(ready, listOf(Emit(VisualsFact.PresetImported(shared.id)))),
            ),
            Row(
                "a shared preset that cannot be read",
                listed,
                VisualsEvent.ImportRefused(ImportProblem.NOT_A_PRESET),
                emits(VisualsFact.PresetNotImported(ImportProblem.NOT_A_PRESET)),
            ),
        )

    private val listing: List<Row> =
        listOf(
            Row("list the presets again", listed, command(VisualsCommand.ReloadPresets), changed(listed, VisualsWork.LoadPresets)),
            Row(
                "list the presets before they are listed",
                ready,
                command(VisualsCommand.ReloadPresets),
                changed(ready, VisualsWork.LoadPresets),
            ),
            Row(
                "listed presets are tidied: by name, without repeats, with every folder that holds one",
                ready,
                VisualsEvent.PresetsLoaded(
                    PresetShelf(
                        builtIn = listOf(chill),
                        saved = listOf(deeper, calm, deep, deep.copy(style = tunnel)),
                        folders = listOf(party, nightlife),
                    ),
                ),
                same(listed),
            ),
            Row(
                "a later list replaces the shelf",
                listed,
                VisualsEvent.PresetsLoaded(PresetShelf(saved = listOf(calm))),
                same(ready.copy(presets = PresetShelf(saved = listOf(calm)))),
            ),
        )

    private fun failed(failure: WorkFailure) = VisualsEvent.WorkFailed(failure)

    private val failures: List<Row> =
        listOf(
            Row(
                "a change to the saved presets fails: reported, and listed again",
                listed,
                failed(WorkFailure.PRESET_NOT_CHANGED),
                Next(listed, listOf(ReportFailure(WorkFailure.PRESET_NOT_CHANGED.message), Perform(VisualsWork.LoadPresets))),
            ),
            Row(
                "an import cannot be saved: reported, and listed again",
                listed,
                failed(WorkFailure.PRESET_NOT_IMPORTED),
                Next(listed, listOf(ReportFailure(WorkFailure.PRESET_NOT_IMPORTED.message), Perform(VisualsWork.LoadPresets))),
            ),
            Row(
                "the presets cannot be listed",
                ready,
                failed(WorkFailure.PRESETS_NOT_LOADED),
                Next(ready, listOf(ReportFailure(WorkFailure.PRESETS_NOT_LOADED.message))),
            ),
            Row(
                "a preset cannot be applied",
                listed,
                failed(WorkFailure.PRESET_NOT_APPLIED),
                Next(listed, listOf(ReportFailure(WorkFailure.PRESET_NOT_APPLIED.message))),
            ),
            Row(
                "a preset cannot be read to share",
                listed,
                failed(WorkFailure.PRESET_NOT_SHARED),
                Next(listed, listOf(ReportFailure(WorkFailure.PRESET_NOT_SHARED.message))),
            ),
        )

    @Test
    fun `every row of the table`() {
        for (row in applying + renaming + moving + deleting + folders + sharing + importing + listing + failures) {
            assertEquals(row.name, row.next, reduce(row.state, row.event))
        }
    }

    @Test
    fun `only failures that may leave the phone different list the presets again`() {
        val resyncing = WorkFailure.entries.filter { it.resyncs }

        assertEquals(listOf(WorkFailure.PRESET_NOT_CHANGED, WorkFailure.PRESET_NOT_IMPORTED), resyncing)
    }

    @Test
    fun `a folder listed only through a preset deep inside it brings every folder above it`() {
        val tidied = PresetEdits.tidy(PresetShelf(saved = listOf(SavedPreset(calmId, tunnel, PresetFolderPath("a/b/c")))))

        assertEquals(listOf(PresetFolderPath("a"), PresetFolderPath("a/b"), PresetFolderPath("a/b/c")), tidied.folders)
    }

    @Test
    fun `names sort without regard to case`() {
        val tidied =
            PresetEdits.tidy(
                PresetShelf(
                    saved = listOf(SavedPreset(PresetId("b"), tunnel), SavedPreset(PresetId("A"), tunnel)),
                    folders = listOf(PresetFolderPath("b"), PresetFolderPath("A")),
                ),
            )

        assertEquals(listOf(PresetId("A"), PresetId("b")), tidied.saved.map { it.id })
        assertEquals(listOf(PresetFolderPath("A"), PresetFolderPath("b")), tidied.folders)
    }
}
