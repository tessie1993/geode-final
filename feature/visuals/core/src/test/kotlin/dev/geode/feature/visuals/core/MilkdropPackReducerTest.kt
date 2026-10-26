package dev.geode.feature.visuals.core

import dev.geode.engine.types.MilkdropStyles
import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPackType
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.MilkdropShelf
import dev.geode.feature.visuals.api.PackLimit
import dev.geode.feature.visuals.api.PackProblem
import dev.geode.feature.visuals.api.SkippedEntry
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.feature.visuals.core.Fixtures.command
import dev.geode.feature.visuals.core.Fixtures.ready
import dev.geode.feature.visuals.core.Fixtures.reduce
import dev.geode.feature.visuals.core.Fixtures.saved
import dev.geode.feature.visuals.core.Fixtures.tunnel
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Importing a whole pack of MilkDrop presets, a folder or a zip (#399), as a transition table
 * (ADR-0003): it starts like any import, and its presets are filed in folders of the library named
 * for the pack's.
 */
class MilkdropPackReducerTest {
    private val aurora = MilkdropPreset("aurora_veil.milk", "Aurora Veil", "Geode", builtIn = true)
    private val deep = MilkdropPreset("deep.milk", "deep", null, builtIn = false)
    private val mine = MilkdropPreset("mine.milk", "mine", null, builtIn = false)
    private val textures = listOf("clouds.png")
    private val after = listOf(aurora, deep, mine)

    private val auroraStyle = StyleEntry(MilkdropStyles.of("aurora_veil.milk"), "Aurora Veil", StyleGroup.MILKDROP)
    private val deepId = MilkdropStyles.of("deep.milk")
    private val mineId = MilkdropStyles.of("mine.milk")
    private val others = ready.styles.filterNot { it.group == StyleGroup.MILKDROP }
    private val stylesAfter =
        others + auroraStyle + StyleEntry(deepId, "deep", StyleGroup.MILKDROP) + StyleEntry(mineId, "mine", StyleGroup.MILKDROP)

    private val listed = ready.copy(styles = others + auroraStyle, milkdrop = MilkdropShelf(listOf(aurora), textures))
    private val importing = listed.copy(milkdrop = MilkdropShelf(listOf(aurora), textures, importing = true))

    private val report =
        ImportReport(
            MilkdropKind.PRESETS,
            listOf("deep", "mine"),
            emptyList(),
            textures = listOf("clouds.png"),
            skipped = listOf(SkippedEntry("Mix/readme.txt", PackProblem.NOT_MILKDROP)),
            stopped = PackLimit.SIZE,
        )
    private val shelfAfter = MilkdropShelf(after, textures, importing = false, lastImport = report)

    private val zip = "content://downloads/Mix.zip"
    private val importZip = command(VisualsCommand.ImportMilkdropPack(MilkdropPackType.ZIP, zip))
    private val zipWork = Perform(VisualsWork.ImportMilkdropPack(MilkdropPackType.ZIP, zip))

    private fun finished(
        folders: Map<String, List<String>>,
        presets: List<MilkdropPreset> = after,
    ) = VisualsEvent.MilkdropImported(report, presets, textures, folders)

    /** [state] after the pack came in, with the lists and report it brings. */
    private fun done(state: VisualsState) = state.copy(styles = stylesAfter, milkdrop = shelfAfter)

    private fun libraryOf(vararg folders: Folder) = checkNotNull(ready.library).copy(folders = folders.toList())

    private val one = FolderId(1)
    private val two = FolderId(2)
    private val filed =
        libraryOf(
            Folder(one, "Mix", null, listOf(mineId)),
            Folder(two, "Geiss", one, listOf(deepId)),
        )

    private data class Row(
        val name: String,
        val state: VisualsState,
        val event: VisualsEvent,
        val next: Next<VisualsState, VisualsEffect>,
    )

    private fun same(state: VisualsState) = Next<VisualsState, VisualsEffect>(state)

    private fun savedAs(
        state: VisualsState,
        library: StyleLibrary,
    ) = Next(done(state).copy(library = library), listOf(saved(library)))

    private val table: List<Row> =
        listOf(
            // Starting: like any import, one at a time and once listed.
            Row("import a zip", listed, importZip, Next(importing, listOf(zipWork))),
            Row(
                "import a folder",
                listed,
                command(VisualsCommand.ImportMilkdropPack(MilkdropPackType.FOLDER, "content://tree/Mix")),
                Next(importing, listOf(Perform(VisualsWork.ImportMilkdropPack(MilkdropPackType.FOLDER, "content://tree/Mix")))),
            ),
            Row(
                "a new pack forgets the last report",
                listed.copy(milkdrop = MilkdropShelf(listOf(aurora), textures, lastImport = report)),
                importZip,
                Next(importing, listOf(zipWork)),
            ),
            Row("nothing picked", listed, command(VisualsCommand.ImportMilkdropPack(MilkdropPackType.ZIP, " ")), same(listed)),
            Row("a pack while another import runs", importing, importZip, same(importing)),
            Row("a pack before the folder is listed", ready, importZip, same(ready)),
            // Finishing: the lists and report, and each preset filed in its folder.
            Row(
                "the pack's presets are filed in folders named for its own",
                importing,
                finished(mapOf("mine.milk" to listOf("Mix"), "deep.milk" to listOf("Mix", "Geiss"))),
                savedAs(importing, filed),
            ),
            Row(
                "a folder already there by that name and parent is filled, not made again",
                importing.copy(
                    library = libraryOf(Folder(one, "Mix", null, listOf(tunnel)), Folder(FolderId(5), "Geiss", null, emptyList())),
                ),
                finished(mapOf("deep.milk" to listOf("Mix", "Geiss"))),
                savedAs(
                    importing,
                    libraryOf(
                        Folder(one, "Mix", null, listOf(tunnel)),
                        Folder(FolderId(5), "Geiss", null, emptyList()),
                        Folder(FolderId(6), "Geiss", one, listOf(deepId)),
                    ),
                ),
            ),
            Row(
                "a preset already in its folder is not filed twice",
                importing.copy(library = filed),
                finished(mapOf("mine.milk" to listOf("Mix"), "deep.milk" to listOf("Mix", "Geiss"))),
                same(done(importing.copy(library = filed))),
            ),
            Row(
                "folder names are cleaned, and a level with no name left is passed over",
                importing,
                finished(mapOf("mine.milk" to listOf("  Mix  ", " "), "deep.milk" to listOf(" ", "\t"))),
                savedAs(importing, libraryOf(Folder(one, "Mix", null, listOf(mineId)))),
            ),
            Row(
                "a preset the folder no longer lists is not filed",
                importing,
                finished(mapOf("gone.milk" to listOf("Mix"))),
                same(done(importing)),
            ),
            Row(
                "before the library has loaded, nothing is filed",
                importing.copy(library = null),
                finished(mapOf("mine.milk" to listOf("Mix"))),
                same(done(importing.copy(library = null))),
            ),
            Row(
                "a pack that brought no preset files nothing",
                importing,
                finished(emptyMap()),
                same(done(importing)),
            ),
        )

    @Test
    fun `every row of the table`() {
        for (row in table) {
            assertEquals(row.name, row.next, reduce(row.state, row.event))
        }
    }
}
