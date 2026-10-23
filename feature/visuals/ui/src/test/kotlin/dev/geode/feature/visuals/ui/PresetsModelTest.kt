package dev.geode.feature.visuals.ui

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.BuiltInPreset
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.PresetFolderPath
import dev.geode.feature.visuals.api.PresetShelf
import dev.geode.feature.visuals.api.SavedPreset
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Presets browser's model (#144): what each place lists, and the Command each action sends. */
class PresetsModelTest {
    private val tunnel = StyleEntry(SceneId("tunnel"), "Tunnel", StyleGroup.SHADERS)
    private val ink = StyleEntry(SceneId("fluid_ink"), "Fluid Ink", StyleGroup.FLUIDS)
    private val gone = SceneId("silk")

    private val calm = PresetFolderPath("Calm")
    private val rainy = PresetFolderPath("Calm/Rainy")
    private val chill = BuiltInPreset(PresetId("Tunnel · Chill"), tunnel.id)
    private val wash = BuiltInPreset(PresetId("Ink · Wash"), ink.id)
    private val drive = SavedPreset(PresetId("Drive"), ink.id)
    private val old = SavedPreset(PresetId("Old"), gone)
    private val slow = SavedPreset(PresetId("Slow"), tunnel.id, calm)

    private val state =
        VisualsState(
            styles = listOf(tunnel, ink),
            current = tunnel.id,
            presets = PresetShelf(listOf(chill, wash), listOf(drive, old, slow), listOf(calm, rainy)),
        )

    private fun saved(
        preset: SavedPreset,
        styleName: String?,
    ) = PresetItem(preset.id, preset.id.value, styleName, builtIn = false)

    @Test
    fun `the first page shows the built-in presets for the Style shown, every built-in one, and the top`() {
        val model = state.toPresetsModel(PresetPlace.TOP)

        assertFalse(model.loading)
        assertEquals(PresetPlace.TOP, model.place)
        assertEquals(emptyList<Crumb>(), model.trail)
        assertEquals("Tunnel", model.currentName)
        assertEquals(listOf(PresetItem(chill.id, chill.id.value, "Tunnel", builtIn = true)), model.forCurrent)
        assertEquals(2, model.builtInCount)
        assertEquals(listOf(PresetFolderItem(PresetPlace.Saved(calm), "Calm", 2)), model.folders)
        assertEquals(listOf(saved(drive, "Fluid Ink"), saved(old, null)), model.presets)
        assertTrue(model.editable)
        assertNull(model.notice)
    }

    @Test
    fun `a folder shows its folders and presets, and the way back`() {
        val model = state.toPresetsModel(PresetPlace.Saved(rainy))

        assertEquals(listOf(Crumb(PresetPlace.Saved(calm), "Calm"), Crumb(PresetPlace.Saved(rainy), "Rainy")), model.trail)
        assertEquals(emptyList<PresetItem>(), model.forCurrent)
        assertEquals(0, model.builtInCount)
        assertEquals(emptyList<PresetFolderItem>(), model.folders)
        assertEquals(emptyList<PresetItem>(), model.presets)

        val calmModel = state.toPresetsModel(PresetPlace.Saved(calm))
        assertEquals(listOf(PresetFolderItem(PresetPlace.Saved(rainy), "Rainy", 0)), calmModel.folders)
        assertEquals(listOf(saved(slow, "Tunnel")), calmModel.presets)
    }

    @Test
    fun `the built-in presets are a folder per Style, by name`() {
        val model = state.toPresetsModel(PresetPlace.BuiltIn)

        assertEquals(listOf(Crumb(PresetPlace.BuiltIn, null)), model.trail)
        assertEquals(
            listOf(
                PresetFolderItem(PresetPlace.BuiltInFor(ink.id), "Fluid Ink", 1),
                PresetFolderItem(PresetPlace.BuiltInFor(tunnel.id), "Tunnel", 1),
            ),
            model.folders,
        )
        assertEquals(emptyList<PresetItem>(), model.presets)
        assertFalse(model.editable)

        val tunnelModel = state.toPresetsModel(PresetPlace.BuiltInFor(tunnel.id))
        assertEquals(listOf(Crumb(PresetPlace.BuiltIn, null), Crumb(PresetPlace.BuiltInFor(tunnel.id), "Tunnel")), tunnelModel.trail)
        assertEquals(listOf(PresetItem(chill.id, chill.id.value, "Tunnel", builtIn = true)), tunnelModel.presets)
    }

    @Test
    fun `a place that is gone shows the first page`() {
        assertEquals(PresetPlace.TOP, state.toPresetsModel(PresetPlace.Saved(PresetFolderPath("Gone"))).place)
        assertEquals(PresetPlace.TOP, state.toPresetsModel(PresetPlace.BuiltInFor(gone)).place)
        assertEquals(PresetPlace.TOP, state.copy(presets = PresetShelf()).toPresetsModel(PresetPlace.BuiltIn).place)
    }

    @Test
    fun `before the presets are listed the browser is loading`() {
        val model = state.copy(presets = null).toPresetsModel(PresetPlace.Saved(calm), PresetNotice.ClipboardEmpty)

        assertTrue(model.loading)
        assertEquals(PresetPlace.TOP, model.place)
        assertEquals(PresetNotice.ClipboardEmpty, model.notice)
    }

    @Test
    fun `presets move to the top or any folder, named by its path, and their names are known for renaming`() {
        val model = state.toPresetsModel(PresetPlace.TOP)

        assertEquals(
            listOf(MoveTarget(PresetFolderPath.TOP, ""), MoveTarget(calm, "Calm"), MoveTarget(rainy, "Calm / Rainy")),
            model.moveTargets,
        )
        assertEquals(setOf("Drive", "Old", "Slow"), model.savedNames)
    }

    @Test
    fun `each action sends its Command`() {
        val id = drive.id
        val at = PresetPlace.Saved(calm)

        assertEquals(VisualsCommand.ApplyPreset(id), PresetsAction.Apply(id).toCommand(at))
        assertEquals(VisualsCommand.RenamePreset(id, "New"), PresetsAction.Rename(id, "New").toCommand(at))
        assertEquals(VisualsCommand.MovePreset(id, rainy), PresetsAction.Move(id, rainy).toCommand(at))
        assertEquals(VisualsCommand.DeletePreset(id), PresetsAction.Delete(id).toCommand(at))
        assertEquals(VisualsCommand.SharePreset(id), PresetsAction.Share(id).toCommand(at))
        assertEquals(VisualsCommand.CreatePresetFolder("New", calm), PresetsAction.CreateFolder("New").toCommand(at))
        assertNull(PresetsAction.CreateFolder("New").toCommand(PresetPlace.BuiltIn))
        assertEquals(VisualsCommand.RenamePresetFolder(calm, "New"), PresetsAction.RenameFolder(calm, "New").toCommand(at))
        assertEquals(VisualsCommand.DeletePresetFolder(calm), PresetsAction.DeleteFolder(calm).toCommand(at))
        for (action in listOf(PresetsAction.Open(at), PresetsAction.PasteLink, PresetsAction.OpenFile, PresetsAction.DismissNotice)) {
            assertNull(action.toCommand(at))
        }
    }

    @Test
    fun `back goes up a folder, out of the built-in presets, and from the first page leaves`() {
        assertEquals(PresetPlace.Saved(calm), PresetPlace.Saved(rainy).up())
        assertEquals(PresetPlace.TOP, PresetPlace.Saved(calm).up())
        assertNull(PresetPlace.TOP.up())
        assertEquals(PresetPlace.TOP, PresetPlace.BuiltIn.up())
        assertEquals(PresetPlace.BuiltIn, PresetPlace.BuiltInFor(tunnel.id).up())
    }

    @Test
    fun `a place survives as a string`() {
        val places = listOf(PresetPlace.TOP, PresetPlace.Saved(rainy), PresetPlace.BuiltIn, PresetPlace.BuiltInFor(tunnel.id))

        for (place in places) assertEquals(place, presetPlaceOf(place.key()))
        assertEquals(PresetPlace.TOP, presetPlaceOf("something else"))
    }

    @Test
    fun `a notice is carried as it is`() {
        val notice = PresetNotice.NotImported(ImportProblem.NO_LINK)

        assertEquals(notice, state.toPresetsModel(PresetPlace.TOP, notice).notice)
    }
}
