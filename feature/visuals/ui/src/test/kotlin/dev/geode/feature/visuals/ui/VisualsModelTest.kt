package dev.geode.feature.visuals.ui

import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisualsModelTest {
    private val tunnel = StyleEntry(SceneId("tunnel"), "Tunnel", StyleGroup.SHADERS)
    private val bars = StyleEntry(SceneId("bars"), "Bars", StyleGroup.SHADERS)
    private val ink = StyleEntry(SceneId("fluid_ink"), "Fluid Ink", StyleGroup.FLUIDS)
    private val gone = SceneId("silk")

    private val calm = Folder(FolderId(1), "Calm", null, listOf(tunnel.id, gone))
    private val deep = Folder(FolderId(2), "Deep", calm.id, listOf(ink.id))
    private val loud = Folder(FolderId(3), "Loud", null, emptyList())

    private val state =
        VisualsState(
            styles = listOf(tunnel, bars, ink),
            current = tunnel.id,
            library =
                StyleLibrary(
                    favourites = listOf(ink.id, gone),
                    recent = listOf(tunnel.id, gone, bars.id),
                    folders = listOf(calm, deep, loud),
                ),
        )

    private fun item(
        entry: StyleEntry,
        favourite: Boolean = false,
    ) = StyleItem(entry.id, entry.name, entry.group, current = entry == tunnel, favourite = favourite)

    @Test
    fun `the top level shows Favourites, Recent, the top folders and every family`() {
        val model = state.toTabModel(query = "", openFolder = null)

        assertEquals(tunnel.name, model.currentName)
        assertEquals(listOf(item(ink, favourite = true)), model.favourites)
        assertEquals(listOf(item(tunnel), item(bars)), model.recent)
        assertEquals(listOf(FolderItem(calm.id, "Calm", 2), FolderItem(loud.id, "Loud", 0)), model.folders)
        assertEquals(
            listOf(
                GroupSection(StyleGroup.SHADERS, listOf(item(tunnel), item(bars))),
                GroupSection(StyleGroup.FLUIDS, listOf(item(ink, favourite = true))),
            ),
            model.groups,
        )
        assertEquals(emptyList<FolderItem>(), model.path)
        assertNull(model.results)
        assertEquals(
            listOf(FolderItem(calm.id, "Calm", 2), FolderItem(deep.id, "Calm / Deep", 1), FolderItem(loud.id, "Loud", 0)),
            model.allFolders,
        )
    }

    @Test
    fun `an open folder shows where it is, its folders and its Styles, without the families`() {
        val model = state.toTabModel(query = "", openFolder = calm.id)

        assertEquals(listOf(FolderItem(calm.id, "Calm", 2)), model.path)
        assertEquals(listOf(FolderItem(deep.id, "Deep", 1)), model.folders)
        assertEquals(listOf(item(tunnel)), model.folderStyles)
        assertEquals(emptyList<GroupSection>(), model.groups)
        assertEquals(emptyList<StyleItem>(), model.favourites)
        assertEquals(emptyList<StyleItem>(), model.recent)

        assertEquals(listOf(FolderItem(calm.id, "Calm", 2), FolderItem(deep.id, "Deep", 1)), state.toTabModel("", deep.id).path)
    }

    @Test
    fun `a folder that is gone opens the top level`() {
        assertEquals(state.toTabModel("", null), state.toTabModel("", FolderId(9)))
    }

    @Test
    fun `searching lists the Styles whose name matches, whatever the case`() {
        assertEquals(listOf(item(tunnel), item(ink, favourite = true)), state.toTabModel(query = " N ", openFolder = null).results)
        assertEquals(emptyList<StyleItem>(), state.toTabModel(query = "zzz", openFolder = null).results)
        assertNull(state.toTabModel(query = "  ", openFolder = null).results)
    }

    @Test
    fun `before the library loads the families are there and nothing else`() {
        val model = state.copy(library = null, current = null).toTabModel("", null)

        assertEquals(true, model.loading)
        assertNull(model.currentName)
        assertEquals(emptyList<StyleItem>(), model.favourites)
        assertEquals(emptyList<FolderItem>(), model.folders)
        assertEquals(2, model.groups.size)
        assertEquals(false, state.toTabModel("", null).loading)
    }

    @Test
    fun `actions on the library become Commands, and a new folder goes in the open one`() {
        assertEquals(VisualsCommand.Choose(ink.id), VisualsAction.Choose(ink.id).toCommand(openFolder = null))
        assertEquals(VisualsCommand.SetFavourite(ink.id, true), VisualsAction.SetFavourite(ink.id, true).toCommand(null))
        assertEquals(VisualsCommand.CreateFolder("New", calm.id), VisualsAction.CreateFolder("New").toCommand(calm.id))
        assertEquals(VisualsCommand.CreateFolder("New", null), VisualsAction.CreateFolder("New").toCommand(null))
        assertEquals(VisualsCommand.RenameFolder(calm.id, "Still"), VisualsAction.RenameFolder(calm.id, "Still").toCommand(null))
        assertEquals(VisualsCommand.DeleteFolder(calm.id), VisualsAction.DeleteFolder(calm.id).toCommand(null))
        assertEquals(VisualsCommand.AddToFolder(calm.id, ink.id), VisualsAction.AddToFolder(calm.id, ink.id).toCommand(null))
        assertEquals(VisualsCommand.RemoveFromFolder(calm.id, ink.id), VisualsAction.RemoveFromFolder(calm.id, ink.id).toCommand(null))
        assertNull(VisualsAction.Search("x").toCommand(null))
        assertNull(VisualsAction.OpenFolder(calm.id).toCommand(null))
        assertNull(VisualsAction.EnterFullScreen.toCommand(null))
    }

    @Test
    fun `full-screen mode names the Style and steps through the list, round the ends`() {
        val model = state.toFullScreenModel(controlsShown = true)

        assertEquals(FullScreenModel("Tunnel", previous = ink.id, next = bars.id, controlsShown = true), model)
        assertEquals(
            FullScreenModel("Fluid Ink", previous = bars.id, next = tunnel.id, controlsShown = false),
            state.copy(current = ink.id).toFullScreenModel(controlsShown = false),
        )
        assertEquals(FullScreenModel(null, null, null, true), state.copy(current = null).toFullScreenModel(true))
        assertEquals(FullScreenModel(null, null, null, true), VisualsState(current = gone).toFullScreenModel(true))
    }
}
