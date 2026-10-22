package dev.geode.feature.visuals.core

import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.feature.visuals.core.Fixtures.command
import dev.geode.feature.visuals.core.Fixtures.fresh
import dev.geode.feature.visuals.core.Fixtures.gone
import dev.geode.feature.visuals.core.Fixtures.ink
import dev.geode.feature.visuals.core.Fixtures.milk
import dev.geode.feature.visuals.core.Fixtures.nested
import dev.geode.feature.visuals.core.Fixtures.one
import dev.geode.feature.visuals.core.Fixtures.ready
import dev.geode.feature.visuals.core.Fixtures.reduce
import dev.geode.feature.visuals.core.Fixtures.saved
import dev.geode.feature.visuals.core.Fixtures.three
import dev.geode.feature.visuals.core.Fixtures.tunnel
import dev.geode.feature.visuals.core.Fixtures.two
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The person's library edits as a transition table (ADR-0003): Favourites and folders that nest. Every
 * change is saved at once; an edit that changes nothing, names something that is not there, or
 * arrives before the library has loaded, does nothing.
 */
class LibraryEditsTest {
    private val folders = ready.copy(library = nested)

    private data class Row(
        val name: String,
        val state: VisualsState,
        val command: VisualsCommand,
        val next: Next<VisualsState, VisualsEffect>,
    )

    private fun same(state: VisualsState) = Next<VisualsState, VisualsEffect>(state)

    private fun changed(
        state: VisualsState,
        library: StyleLibrary,
    ) = Next<VisualsState, VisualsEffect>(state.copy(library = library), listOf(saved(library)))

    private fun withFolders(vararg changed: Folder) = nested.copy(folders = changed.toList())

    private val lateNight = StyleLibrary(recent = listOf(tunnel), folders = listOf(Folder(one, "Late night", null, emptyList())))

    private val calm = nested.folders[0]
    private val deep = nested.folders[1]
    private val deeper = nested.folders[2]

    private val table: List<Row> =
        listOf(
            // Favourites: the latest first.
            Row(
                "favourite a Style",
                ready,
                VisualsCommand.SetFavourite(ink, true),
                changed(ready, StyleLibrary(listOf(ink), listOf(tunnel))),
            ),
            Row(
                "a new favourite goes first",
                ready.copy(library = StyleLibrary(favourites = listOf(ink))),
                VisualsCommand.SetFavourite(milk, true),
                changed(ready, StyleLibrary(favourites = listOf(milk, ink))),
            ),
            Row(
                "favourite it again",
                ready.copy(library = StyleLibrary(favourites = listOf(ink))),
                VisualsCommand.SetFavourite(ink, true),
                same(ready.copy(library = StyleLibrary(favourites = listOf(ink)))),
            ),
            Row("favourite a Style this version does not draw", ready, VisualsCommand.SetFavourite(gone, true), same(ready)),
            Row(
                "take a favourite out",
                ready.copy(library = StyleLibrary(favourites = listOf(milk, ink))),
                VisualsCommand.SetFavourite(milk, false),
                changed(ready, StyleLibrary(favourites = listOf(ink))),
            ),
            Row(
                "take out a favourite this version no longer draws",
                ready.copy(library = StyleLibrary(favourites = listOf(gone))),
                VisualsCommand.SetFavourite(gone, false),
                changed(ready, StyleLibrary()),
            ),
            Row("take out a Style that is no favourite", ready, VisualsCommand.SetFavourite(ink, false), same(ready)),
            Row("favourite before the library loads", fresh, VisualsCommand.SetFavourite(ink, true), same(fresh)),
            // Making folders: numbered after the highest, named without stray spaces.
            Row(
                "make a folder at the top",
                ready,
                VisualsCommand.CreateFolder("  Late   night "),
                Next(
                    ready.copy(library = lateNight),
                    listOf(
                        saved(lateNight),
                        Emit(VisualsFact.FolderCreated(one)),
                    ),
                ),
            ),
            Row(
                "make a folder inside another",
                folders,
                VisualsCommand.CreateFolder("Deepest", three),
                Next(
                    folders.copy(library = withFolders(calm, deep, deeper, Folder(FolderId(4), "Deepest", three, emptyList()))),
                    listOf(
                        saved(withFolders(calm, deep, deeper, Folder(FolderId(4), "Deepest", three, emptyList()))),
                        Emit(VisualsFact.FolderCreated(FolderId(4))),
                    ),
                ),
            ),
            Row(
                "a deleted folder's number is not given out while a higher one is in use",
                ready.copy(library = withFolders(deeper.copy(parent = null))),
                VisualsCommand.CreateFolder("New"),
                Next(
                    ready.copy(library = withFolders(deeper.copy(parent = null), Folder(FolderId(4), "New", null, emptyList()))),
                    listOf(
                        saved(withFolders(deeper.copy(parent = null), Folder(FolderId(4), "New", null, emptyList()))),
                        Emit(VisualsFact.FolderCreated(FolderId(4))),
                    ),
                ),
            ),
            Row("make a folder with a blank name", ready, VisualsCommand.CreateFolder(" \n "), same(ready)),
            Row("make a folder inside one that is not there", ready, VisualsCommand.CreateFolder("Lost", FolderId(9)), same(ready)),
            Row("make a folder before the library loads", fresh, VisualsCommand.CreateFolder("Early"), same(fresh)),
            // Renaming.
            Row(
                "rename a folder",
                folders,
                VisualsCommand.RenameFolder(two, " Deep\tblue "),
                changed(folders, withFolders(calm, deep.copy(name = "Deep blue"), deeper)),
            ),
            Row("rename to the same name", folders, VisualsCommand.RenameFolder(two, "Deep"), same(folders)),
            Row("rename to a blank name", folders, VisualsCommand.RenameFolder(two, "  "), same(folders)),
            Row("rename a folder that is not there", folders, VisualsCommand.RenameFolder(FolderId(9), "Gone"), same(folders)),
            // Moving: anywhere but into itself or a folder inside it.
            Row(
                "move a folder to the top",
                folders,
                VisualsCommand.MoveFolder(three, null),
                changed(folders, withFolders(calm, deep, deeper.copy(parent = null))),
            ),
            Row(
                "move a folder into another",
                folders,
                VisualsCommand.MoveFolder(three, one),
                changed(folders, withFolders(calm, deep, deeper.copy(parent = one))),
            ),
            Row("move a folder where it already is", folders, VisualsCommand.MoveFolder(three, two), same(folders)),
            Row("move a folder into itself", folders, VisualsCommand.MoveFolder(two, two), same(folders)),
            Row("move a folder into one inside it", folders, VisualsCommand.MoveFolder(one, three), same(folders)),
            Row("move a folder into one that is not there", folders, VisualsCommand.MoveFolder(three, FolderId(9)), same(folders)),
            Row("move a folder that is not there", folders, VisualsCommand.MoveFolder(FolderId(9), null), same(folders)),
            // Deleting takes every folder inside with it; the Styles stay.
            Row("delete a folder and those inside it", folders, VisualsCommand.DeleteFolder(two), changed(folders, withFolders(calm))),
            Row("delete the innermost folder", folders, VisualsCommand.DeleteFolder(three), changed(folders, withFolders(calm, deep))),
            Row("delete a folder that is not there", folders, VisualsCommand.DeleteFolder(FolderId(9)), same(folders)),
            // Filling folders.
            Row(
                "add a Style to a folder",
                folders,
                VisualsCommand.AddToFolder(one, ink),
                changed(folders, withFolders(calm.copy(styles = listOf(tunnel, ink)), deep, deeper)),
            ),
            Row("add a Style already there", folders, VisualsCommand.AddToFolder(one, tunnel), same(folders)),
            Row("add a Style this version does not draw", folders, VisualsCommand.AddToFolder(one, gone), same(folders)),
            Row("add to a folder that is not there", folders, VisualsCommand.AddToFolder(FolderId(9), ink), same(folders)),
            Row(
                "take a Style out of a folder",
                folders,
                VisualsCommand.RemoveFromFolder(one, tunnel),
                changed(folders, withFolders(calm.copy(styles = emptyList()), deep, deeper)),
            ),
            Row("take out a Style that is not there", folders, VisualsCommand.RemoveFromFolder(two, tunnel), same(folders)),
            Row("take out of a folder that is not there", folders, VisualsCommand.RemoveFromFolder(FolderId(9), tunnel), same(folders)),
            Row("edit a folder before the library loads", fresh, VisualsCommand.DeleteFolder(one), same(fresh)),
        )

    @Test
    fun `every row of the table`() {
        for (row in table) {
            assertEquals(row.name, row.next, reduce(row.state, command(row.command)))
        }
    }

    @Test
    fun `a folder name is cut to its limit`() {
        val long = "a".repeat(LibraryEdits.FOLDER_NAME_LIMIT + 5)

        val next = reduce(ready, command(VisualsCommand.CreateFolder(long)))

        assertEquals("a".repeat(LibraryEdits.FOLDER_NAME_LIMIT), next.state.library?.folders?.single()?.name)
    }
}
