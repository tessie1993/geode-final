package dev.geode.feature.visuals.core

import dev.geode.feature.visuals.api.SnapshotProblem
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.feature.visuals.core.Fixtures.command
import dev.geode.feature.visuals.core.Fixtures.fresh
import dev.geode.feature.visuals.core.Fixtures.gone
import dev.geode.feature.visuals.core.Fixtures.ink
import dev.geode.feature.visuals.core.Fixtures.ready
import dev.geode.feature.visuals.core.Fixtures.reduce
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * Snapshots (#405) as a transition table (ADR-0003): one at a time, named for the Style shown, and
 * announced when saved or not; and how their files are named.
 */
class SnapshotsTest {
    private val snapshot = command(VisualsCommand.Snapshot)
    private val saving = ready.copy(snapshotting = true)

    private data class Row(
        val name: String,
        val state: VisualsState,
        val event: VisualsEvent,
        val next: Next<VisualsState, VisualsEffect>,
    )

    private val table: List<Row> =
        listOf(
            Row("a Snapshot of the Style shown", ready, snapshot, Next(saving, listOf(Perform(VisualsWork.TakeSnapshot("Tunnel"))))),
            Row(
                "a Snapshot of another Style",
                ready.copy(current = ink),
                snapshot,
                Next(ready.copy(current = ink, snapshotting = true), listOf(Perform(VisualsWork.TakeSnapshot("Fluid Ink")))),
            ),
            Row(
                "a Snapshot before Customization has said",
                fresh,
                snapshot,
                Next(fresh.copy(snapshotting = true), listOf(Perform(VisualsWork.TakeSnapshot(null)))),
            ),
            Row(
                "a Snapshot of a Style this version does not list",
                ready.copy(current = gone),
                snapshot,
                Next(ready.copy(current = gone, snapshotting = true), listOf(Perform(VisualsWork.TakeSnapshot(null)))),
            ),
            Row("a second Snapshot while one is saved", saving, snapshot, Next(saving)),
            Row(
                "the Snapshot is saved",
                saving,
                VisualsEvent.SnapshotSaved("Tunnel.png"),
                Next(ready, listOf(Emit(VisualsFact.SnapshotSaved("Tunnel.png")))),
            ),
            Row(
                "no Visual to take a still of",
                saving,
                VisualsEvent.SnapshotFailed(SnapshotProblem.NO_VISUAL),
                Next(ready, listOf(Emit(VisualsFact.SnapshotNotSaved(SnapshotProblem.NO_VISUAL)))),
            ),
            Row(
                "the Snapshot could not be written",
                saving,
                VisualsEvent.SnapshotFailed(SnapshotProblem.NOT_SAVED),
                Next(ready, listOf(Emit(VisualsFact.SnapshotNotSaved(SnapshotProblem.NOT_SAVED)))),
            ),
        )

    @Test
    fun `every row of the table`() {
        for (row in table) {
            assertEquals(row.name, row.next, reduce(row.state, row.event))
        }
    }

    private val at = LocalDateTime.of(2026, 10, 6, 9, 3, 2)

    @Test
    fun `a Snapshot is named for the Style and the moment, padded so names sort by time`() {
        assertEquals("Tunnel (Geode Snapshot 2026-10-06 09-03-02).png", SnapshotNames.fileName("Tunnel", at))
        assertEquals(
            "Fluid Ink (Geode Snapshot 2026-12-31 23-59-59).png",
            SnapshotNames.fileName("Fluid Ink", LocalDateTime.of(2026, 12, 31, 23, 59, 59)),
        )
    }

    @Test
    fun `characters a file name cannot hold become single spaces`() {
        assertEquals("a b c d (Geode Snapshot 2026-10-06 09-03-02).png", SnapshotNames.fileName(" a/\\b:*?\"c<>|\td\n ", at))
        assertEquals("Beach at dusk.milk (Geode Snapshot 2026-10-06 09-03-02).png", SnapshotNames.fileName("Beach at dusk.milk", at))
    }

    @Test
    fun `with no name the Snapshot is named for the Visual`() {
        val fallback = "${SnapshotNames.FALLBACK} (Geode Snapshot 2026-10-06 09-03-02).png"
        assertEquals(fallback, SnapshotNames.fileName(null, at))
        assertEquals(fallback, SnapshotNames.fileName("", at))
        assertEquals(fallback, SnapshotNames.fileName(" /:| ", at))
    }

    @Test
    fun `a long name is cut to the limit, with no space left at the cut`() {
        val long = "a".repeat(SnapshotNames.MAX_NAME + 5)
        assertEquals("${"a".repeat(SnapshotNames.MAX_NAME)} (Geode Snapshot 2026-10-06 09-03-02).png", SnapshotNames.fileName(long, at))
        val spaced = "a".repeat(SnapshotNames.MAX_NAME - 1) + " b"
        assertEquals(
            "${"a".repeat(SnapshotNames.MAX_NAME - 1)} (Geode Snapshot 2026-10-06 09-03-02).png",
            SnapshotNames.fileName(spaced, at),
        )
    }
}
