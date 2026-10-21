package dev.geode.feature.library.core

import dev.geode.feature.library.api.IndexStatus
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.core.Fixtures.alpha
import dev.geode.feature.library.core.Fixtures.beta
import dev.geode.feature.library.core.Fixtures.ready
import dev.geode.feature.library.core.Fixtures.shown
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Library's index as a transition table (ADR-0003): every index status against every event that
 * moves it. The Commands that browse and play are in [LibraryCommandsTest].
 */
class LibraryReducerTest {
    private val loading = ready.copy(index = IndexStatus.Loading)
    private val scanning = ready.copy(index = IndexStatus.Scanning)
    private val needsPermission = ready.copy(index = IndexStatus.NeedsPermission)
    private val states = listOf(loading, scanning, ready, needsPermission)

    private val indexRead = LibraryEvent.IndexRead(listOf(alpha), favourites = setOf(beta.id), excludedFolders = setOf("/recordings"))
    private val songsFound = LibraryEvent.SongsFound(listOf(beta))
    private val noPermission = LibraryEvent.ScanFailed(ScanFailure.NO_PERMISSION)
    private val mediaIndexFailed = LibraryEvent.ScanFailed(ScanFailure.MEDIA_INDEX)
    private val rescan = LibraryEvent.Command(LibraryCommand.Rescan)
    private val readFailed = LibraryEvent.StorageFailed(StorageFailure.READ_INDEX)
    private val saveFailed = LibraryEvent.StorageFailed(StorageFailure.SAVE_INDEX)

    private val events: List<LibraryEvent> =
        listOf(
            indexRead,
            songsFound,
            LibraryEvent.ScanFinished,
            noPermission,
            mediaIndexFailed,
            LibraryEvent.MediaChanged,
            rescan,
            readFailed,
            saveFailed,
        )

    private val scan = Perform(LibraryWork.Scan)
    private val reportRead = ReportFailure("Could not read the saved library")
    private val reportSave = ReportFailure("Could not save the library")
    private val reportScan = ReportFailure("Could not look through the music on this phone")

    private fun read(state: LibraryState) = state.copy(favourites = setOf(beta.id), excludedFolders = setOf("/recordings"))

    private val table: List<Row> =
        listOf(
            // The saved index: read once, then scanned. Read after a scan, only the favourites and exclusions count.
            row(loading, indexRead, read(scanning).copy(songs = listOf(alpha)), scan),
            row(scanning, indexRead, read(scanning)),
            row(ready, indexRead, read(ready)),
            row(needsPermission, indexRead, read(needsPermission)),
            // What a scan finds is the newest truth, whenever it comes.
            row(loading, songsFound, loading.copy(songs = listOf(beta))),
            row(scanning, songsFound, scanning.copy(songs = listOf(beta))),
            row(ready, songsFound, ready.copy(songs = listOf(beta))),
            row(needsPermission, songsFound, needsPermission.copy(songs = listOf(beta))),
            row(loading, LibraryEvent.ScanFinished, loading),
            row(scanning, LibraryEvent.ScanFinished, ready),
            row(ready, LibraryEvent.ScanFinished, ready),
            row(needsPermission, LibraryEvent.ScanFinished, needsPermission),
            row(loading, noPermission, loading),
            row(scanning, noPermission, needsPermission),
            row(ready, noPermission, ready),
            row(needsPermission, noPermission, needsPermission),
            row(loading, mediaIndexFailed, loading),
            row(scanning, mediaIndexFailed, ready, reportScan),
            row(ready, mediaIndexFailed, ready),
            row(needsPermission, mediaIndexFailed, needsPermission),
            // A change while reading or scanning is seen by that scan.
            row(loading, LibraryEvent.MediaChanged, loading),
            row(scanning, LibraryEvent.MediaChanged, scanning),
            row(ready, LibraryEvent.MediaChanged, scanning, scan),
            row(needsPermission, LibraryEvent.MediaChanged, scanning, scan),
            row(loading, rescan, loading),
            row(scanning, rescan, scanning),
            row(ready, rescan, scanning, scan),
            row(needsPermission, rescan, scanning, scan),
            // Without the saved index, the scan goes ahead.
            row(loading, readFailed, scanning, scan, reportRead),
            row(scanning, readFailed, scanning, reportRead),
            row(ready, readFailed, ready, reportRead),
            row(needsPermission, readFailed, needsPermission, reportRead),
            row(loading, saveFailed, loading, reportSave),
            row(scanning, saveFailed, scanning, reportSave),
            row(ready, saveFailed, ready, reportSave),
            row(needsPermission, saveFailed, needsPermission, reportSave),
        )

    @Test
    fun `every index status handles every index event as the table says`() {
        for (row in table) {
            assertEquals("${row.state.index} + ${row.event}", row.next, LibraryReducer.reduce(row.state, row.event))
        }
    }

    @Test
    fun `the table covers every index status against every index event`() {
        val covered = table.map { it.state.index to it.event }.toSet()
        val missing = states.flatMap { state -> events.map { state.index to it } }.filterNot { it in covered }

        assertEquals(emptyList<Pair<IndexStatus, LibraryEvent>>(), missing)
    }

    @Test
    fun `a new store starts by reading the saved index`() {
        assertEquals(IndexStatus.Loading, LibraryState().index)
    }

    @Test
    fun `every failure is reported in words`() {
        assertEquals(
            listOf(
                "Could not read the saved library",
                "Could not save the library",
                "Could not save the favourite",
                "Could not save the song info",
                "Could not save the excluded folders",
                "Could not read the playlist file",
                "Could not write the playlist file",
            ),
            StorageFailure.entries.map(::describe),
        )
        assertEquals(
            listOf("Geode may not read the music on this phone", "Could not look through the music on this phone"),
            ScanFailure.entries.map(::describe),
        )
    }

    private fun row(
        state: LibraryState,
        event: LibraryEvent,
        next: LibraryState,
        vararg effects: LibraryEffect,
    ) = Row(state, event, Next(shown(next), effects.toList()))

    private class Row(
        val state: LibraryState,
        val event: LibraryEvent,
        val next: Next<LibraryState, LibraryEffect>,
    )
}
