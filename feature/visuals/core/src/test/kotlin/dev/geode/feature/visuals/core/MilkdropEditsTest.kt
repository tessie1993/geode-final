package dev.geode.feature.visuals.core

import dev.geode.engine.types.MilkdropStyles
import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.MilkdropShelf
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.StyleMark
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.feature.visuals.core.Fixtures.command
import dev.geode.feature.visuals.core.Fixtures.one
import dev.geode.feature.visuals.core.Fixtures.ready
import dev.geode.feature.visuals.core.Fixtures.reduce
import dev.geode.feature.visuals.core.Fixtures.saved
import dev.geode.feature.visuals.core.Fixtures.tunnel
import dev.geode.feature.visuals.core.Fixtures.two
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** Deleting the person's own MilkDrop presets and textures (#360), as a transition table (ADR-0003). */
class MilkdropEditsTest {
    private val aurora = MilkdropPreset("aurora_veil.milk", "Aurora Veil", "Geode", builtIn = true)
    private val mine = MilkdropPreset("mine.milk", "mine", null, builtIn = false)
    private val zeta = MilkdropPreset("zeta.milk", "zeta", null, builtIn = false)
    private val presets = listOf(aurora, mine, zeta)
    private val textures = listOf("clouds.png", "worms.jpg")

    private val auroraId = MilkdropStyles.of(aurora.file)
    private val mineId = MilkdropStyles.of(mine.file)
    private val zetaId = MilkdropStyles.of(zeta.file)
    private val others = ready.styles.filterNot { it.group == StyleGroup.MILKDROP }
    private val milkStyles =
        listOf(
            StyleEntry(auroraId, "Aurora Veil", StyleGroup.MILKDROP),
            StyleEntry(mineId, "mine", StyleGroup.MILKDROP),
            StyleEntry(zetaId, "zeta", StyleGroup.MILKDROP),
        )

    /** Mine is a favourite, in Recent, in a folder and Loved; the tunnel shows. */
    private val library =
        StyleLibrary(
            favourites = listOf(mineId, tunnel),
            recent = listOf(tunnel, mineId),
            folders = listOf(Folder(one, "Calm", null, listOf(mineId, tunnel)), Folder(two, "Empty", null, emptyList())),
            marks = mapOf(mineId to StyleMark.LOVE, tunnel to StyleMark.NEVER),
        )
    private val withoutMine =
        StyleLibrary(
            favourites = listOf(tunnel),
            recent = listOf(tunnel),
            folders = listOf(Folder(one, "Calm", null, listOf(tunnel)), Folder(two, "Empty", null, emptyList())),
            marks = mapOf(tunnel to StyleMark.NEVER),
        )

    private val listed = ready.copy(styles = others + milkStyles, library = library, milkdrop = MilkdropShelf(presets, textures))
    private val importing = listed.copy(milkdrop = MilkdropShelf(presets, textures, importing = true))
    private val playingMine = listed.copy(current = mineId)
    private val playingZeta = listed.copy(current = zetaId)
    private val report = ImportReport(MilkdropKind.TEXTURES, listOf("a.png"), emptyList())

    private fun delete(
        kind: MilkdropKind,
        file: String,
    ) = command(VisualsCommand.DeleteMilkdrop(kind, file))

    private fun work(
        kind: MilkdropKind,
        file: String,
    ) = Perform(VisualsWork.DeleteMilkdrop(kind, file))

    private data class Row(
        val name: String,
        val state: VisualsState,
        val event: VisualsEvent,
        val next: Next<VisualsState, VisualsEffect>,
    )

    private fun same(state: VisualsState) = Next<VisualsState, VisualsEffect>(state)

    private val table: List<Row> =
        listOf(
            // A texture: only deleted; the shelf changes when the folder answers.
            Row(
                "delete a texture",
                listed,
                delete(MilkdropKind.TEXTURES, "worms.jpg"),
                Next(listed, listOf(work(MilkdropKind.TEXTURES, "worms.jpg"))),
            ),
            Row("a texture the shelf does not list", listed, delete(MilkdropKind.TEXTURES, "lichen.png"), same(listed)),
            Row("a preset's name is not a texture", listed, delete(MilkdropKind.TEXTURES, "mine.milk"), same(listed)),
            // A preset of the person's own: deleted, and its Style leaves the library.
            Row(
                "delete my preset",
                listed,
                delete(MilkdropKind.PRESETS, "mine.milk"),
                Next(listed.copy(library = withoutMine), listOf(work(MilkdropKind.PRESETS, "mine.milk"), saved(withoutMine))),
            ),
            Row(
                "a preset the library never held saves nothing",
                listed,
                delete(MilkdropKind.PRESETS, "zeta.milk"),
                Next(listed, listOf(work(MilkdropKind.PRESETS, "zeta.milk"))),
            ),
            Row(
                "before the library has loaded, only the file goes",
                listed.copy(library = null),
                delete(MilkdropKind.PRESETS, "mine.milk"),
                Next(listed.copy(library = null), listOf(work(MilkdropKind.PRESETS, "mine.milk"))),
            ),
            // The preset the Visual shows: it moves on first.
            Row(
                "delete the preset that shows: on to the next Style",
                playingMine,
                delete(MilkdropKind.PRESETS, "mine.milk"),
                Next(
                    playingMine.copy(library = withoutMine),
                    listOf(Perform(VisualsWork.ChooseStyle(zetaId)), work(MilkdropKind.PRESETS, "mine.milk"), saved(withoutMine)),
                ),
            ),
            Row(
                "the last Style that shows moves back one",
                playingZeta,
                delete(MilkdropKind.PRESETS, "zeta.milk"),
                Next(playingZeta, listOf(Perform(VisualsWork.ChooseStyle(mineId)), work(MilkdropKind.PRESETS, "zeta.milk"))),
            ),
            Row(
                "the only Style there is stays chosen",
                playingMine.copy(styles = milkStyles.subList(1, 2)),
                delete(MilkdropKind.PRESETS, "mine.milk"),
                Next(
                    playingMine.copy(styles = milkStyles.subList(1, 2), library = withoutMine),
                    listOf(work(MilkdropKind.PRESETS, "mine.milk"), saved(withoutMine)),
                ),
            ),
            Row(
                "a showing preset not yet listed as a Style is just deleted",
                playingMine.copy(styles = others),
                delete(MilkdropKind.PRESETS, "mine.milk"),
                Next(
                    playingMine.copy(styles = others, library = withoutMine),
                    listOf(work(MilkdropKind.PRESETS, "mine.milk"), saved(withoutMine)),
                ),
            ),
            // What cannot be deleted.
            Row("a preset Geode ships", listed, delete(MilkdropKind.PRESETS, "aurora_veil.milk"), same(listed)),
            Row("a preset the shelf does not list", listed, delete(MilkdropKind.PRESETS, "gone.milk"), same(listed)),
            Row("a texture's name is not a preset", listed, delete(MilkdropKind.PRESETS, "clouds.png"), same(listed)),
            Row("while an import runs", importing, delete(MilkdropKind.PRESETS, "mine.milk"), same(importing)),
            Row("a texture while an import runs", importing, delete(MilkdropKind.TEXTURES, "clouds.png"), same(importing)),
            Row("before the folder is listed", ready, delete(MilkdropKind.TEXTURES, "clouds.png"), same(ready)),
            // The folder's answer replaces the lists and the preset Styles, and keeps the last report.
            Row(
                "a preset is gone",
                listed,
                VisualsEvent.MilkdropDeleted(listOf(aurora, zeta), textures),
                same(
                    listed.copy(
                        styles = others + milkStyles[0] + milkStyles[2],
                        milkdrop = MilkdropShelf(listOf(aurora, zeta), textures),
                    ),
                ),
            ),
            Row(
                "a texture is gone, the import report stays",
                listed.copy(milkdrop = MilkdropShelf(presets, textures, lastImport = report)),
                VisualsEvent.MilkdropDeleted(presets, listOf("clouds.png")),
                same(listed.copy(milkdrop = MilkdropShelf(presets, listOf("clouds.png"), lastImport = report))),
            ),
            Row("an answer before the folder is listed", ready, VisualsEvent.MilkdropDeleted(presets, textures), same(ready)),
            Row(
                "a delete that fails is reported",
                listed,
                VisualsEvent.WorkFailed(WorkFailure.MILKDROP_NOT_DELETED),
                Next(listed, listOf(ReportFailure(WorkFailure.MILKDROP_NOT_DELETED.message))),
            ),
        )

    @Test
    fun `every row of the table`() {
        for (row in table) {
            assertEquals(row.name, row.next, reduce(row.state, row.event))
        }
    }
}
