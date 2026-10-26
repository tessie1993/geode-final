package dev.geode.feature.visuals.core

import dev.geode.engine.types.MilkdropStyles
import dev.geode.feature.visuals.api.BrokenFile
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropImportProblem
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.MilkdropShelf
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.feature.visuals.core.Fixtures.command
import dev.geode.feature.visuals.core.Fixtures.ready
import dev.geode.feature.visuals.core.Fixtures.reduce
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Listing and importing MilkDrop presets and textures (#145), as a transition table (ADR-0003): each
 * row is a state, an event, and the Next it must give.
 */
class MilkdropReducerTest {
    private val aurora = MilkdropPreset("aurora_veil.milk", "Aurora Veil", "Geode", builtIn = true)
    private val mine = MilkdropPreset("mine.milk", "mine", null, builtIn = false)
    private val presets = listOf(aurora)
    private val textures = listOf("clouds.png")

    // Every preset is a Style of its own (#305), in place of the plain MilkDrop Style.
    private val auroraStyle = StyleEntry(MilkdropStyles.of("aurora_veil.milk"), "Aurora Veil", StyleGroup.MILKDROP)
    private val mineStyle = StyleEntry(MilkdropStyles.of("mine.milk"), "mine", StyleGroup.MILKDROP)
    private val others = ready.styles.filterNot { it.group == StyleGroup.MILKDROP }

    private val listed = ready.copy(styles = others + auroraStyle, milkdrop = MilkdropShelf(presets, textures))
    private val importing = listed.copy(milkdrop = MilkdropShelf(presets, textures, importing = true))
    private val report =
        ImportReport(MilkdropKind.PRESETS, listOf("mine"), listOf(BrokenFile("bad.milk", MilkdropImportProblem.NOT_A_PRESET)))
    private val reported = listed.copy(milkdrop = MilkdropShelf(presets, textures, lastImport = report))

    private val picked = listOf("content://picked/1", "content://picked/2")
    private val importPresets = command(VisualsCommand.ImportMilkdrop(MilkdropKind.PRESETS, picked))
    private val importWork = Perform(VisualsWork.ImportMilkdrop(MilkdropKind.PRESETS, picked))

    private data class Row(
        val name: String,
        val state: VisualsState,
        val event: VisualsEvent,
        val next: Next<VisualsState, VisualsEffect>,
    )

    private fun same(state: VisualsState) = Next<VisualsState, VisualsEffect>(state)

    private val table: List<Row> =
        listOf(
            // Listing: once, when the store starts.
            Row("the folder is listed", ready, VisualsEvent.MilkdropListed(presets, textures), same(listed)),
            Row("a second listing is stale", listed, VisualsEvent.MilkdropListed(listOf(mine), emptyList()), same(listed)),
            // Importing: one at a time, once listed.
            Row(
                "import presets",
                listed,
                importPresets,
                Next(importing, listOf(importWork)),
            ),
            Row(
                "import textures",
                listed,
                command(VisualsCommand.ImportMilkdrop(MilkdropKind.TEXTURES, listOf("content://picked/3"))),
                Next(importing, listOf(Perform(VisualsWork.ImportMilkdrop(MilkdropKind.TEXTURES, listOf("content://picked/3"))))),
            ),
            Row(
                "the same file picked twice is imported once",
                listed,
                command(VisualsCommand.ImportMilkdrop(MilkdropKind.PRESETS, picked + picked.first())),
                Next(importing, listOf(importWork)),
            ),
            Row(
                "a new import forgets the last report",
                reported,
                importPresets,
                Next(importing, listOf(importWork)),
            ),
            Row("nothing picked", listed, command(VisualsCommand.ImportMilkdrop(MilkdropKind.PRESETS, emptyList())), same(listed)),
            Row("import while another runs", importing, importPresets, same(importing)),
            Row("import before the folder is listed", ready, importPresets, same(ready)),
            // The import's answer replaces the lists and carries its report.
            Row(
                "the import finishes",
                importing,
                VisualsEvent.MilkdropImported(report, listOf(aurora, mine), textures),
                same(
                    listed.copy(
                        styles = others + auroraStyle + mineStyle,
                        milkdrop = MilkdropShelf(listOf(aurora, mine), textures, importing = false, lastImport = report),
                    ),
                ),
            ),
            Row(
                "an empty folder lists no preset Styles and keeps the plain one",
                ready,
                VisualsEvent.MilkdropListed(emptyList(), emptyList()),
                same(ready.copy(milkdrop = MilkdropShelf())),
            ),
            // Dismissing the report.
            Row("dismiss the report", reported, command(VisualsCommand.DismissImportReport), same(listed)),
            Row("dismiss with no report", listed, command(VisualsCommand.DismissImportReport), same(listed)),
            Row("dismiss before the folder is listed", ready, command(VisualsCommand.DismissImportReport), same(ready)),
            // Failures: reported; an import that failed as a whole lets the next one start.
            Row(
                "the folder cannot be listed",
                ready,
                VisualsEvent.WorkFailed(WorkFailure.MILKDROP_NOT_LISTED),
                Next(ready, listOf(ReportFailure(WorkFailure.MILKDROP_NOT_LISTED.message))),
            ),
            Row(
                "the import fails as a whole",
                importing,
                VisualsEvent.WorkFailed(WorkFailure.MILKDROP_NOT_IMPORTED),
                Next(listed, listOf(ReportFailure(WorkFailure.MILKDROP_NOT_IMPORTED.message))),
            ),
            Row(
                "an import failure with nothing listed",
                ready,
                VisualsEvent.WorkFailed(WorkFailure.MILKDROP_NOT_IMPORTED),
                Next(ready, listOf(ReportFailure(WorkFailure.MILKDROP_NOT_IMPORTED.message))),
            ),
            Row(
                "another failure leaves an import running",
                importing,
                VisualsEvent.WorkFailed(WorkFailure.LIBRARY_NOT_SAVED),
                Next(importing, listOf(ReportFailure(WorkFailure.LIBRARY_NOT_SAVED.message))),
            ),
        )

    @Test
    fun `every row of the table`() {
        for (row in table) {
            assertEquals(row.name, row.next, reduce(row.state, row.event))
        }
    }
}
