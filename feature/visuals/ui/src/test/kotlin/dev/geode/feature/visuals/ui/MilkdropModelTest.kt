package dev.geode.feature.visuals.ui

import dev.geode.engine.types.MilkdropStyles
import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.BrokenFile
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropImportProblem
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.MilkdropShelf
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MilkdropModelTest {
    private val presets = listOf(MilkdropPreset("aurora_veil.milk", "Aurora Veil", "Geode", builtIn = true))
    private val report =
        ImportReport(MilkdropKind.TEXTURES, listOf("clouds.png"), listOf(BrokenFile("x.gif", MilkdropImportProblem.WRONG_TYPE)))

    @Test
    fun `before the folder is listed the screen waits, and nothing can be imported`() {
        val model = VisualsState().toMilkdropModel()

        assertEquals(MilkdropModel(loading = true, importing = false, presets = emptyList(), textures = emptyList(), report = null), model)
        assertFalse(model.canImport)
        assertFalse(model.canDelete)
    }

    @Test
    fun `a listed folder shows its presets, textures and the last report`() {
        val model = VisualsState(milkdrop = MilkdropShelf(presets, listOf("clouds.png"), lastImport = report)).toMilkdropModel()

        assertEquals(
            MilkdropModel(loading = false, importing = false, presets = presets, textures = listOf("clouds.png"), report = report),
            model,
        )
        assertTrue(model.canImport)
        assertTrue(model.canDelete)
    }

    @Test
    fun `while importing nothing else can be imported`() {
        val model = VisualsState(milkdrop = MilkdropShelf(presets, importing = true)).toMilkdropModel()

        assertTrue(model.importing)
        assertFalse(model.canImport)
        assertFalse(model.canDelete)
    }

    @Test
    fun `a confirmed delete sends the file and its kind`() {
        assertEquals(
            VisualsCommand.DeleteMilkdrop(MilkdropKind.PRESETS, "mine.milk"),
            MilkdropAction.Delete(MilkdropKind.PRESETS, "mine.milk").toCommand(),
        )
        assertEquals(
            VisualsCommand.DeleteMilkdrop(MilkdropKind.TEXTURES, "clouds.png"),
            MilkdropAction.Delete(MilkdropKind.TEXTURES, "clouds.png").toCommand(),
        )
    }

    @Test
    fun `dismissing sends a Command, and picking stays in the route`() {
        assertEquals(VisualsCommand.DismissImportReport, MilkdropAction.DismissReport.toCommand())
        assertNull(MilkdropAction.Pick(MilkdropKind.PRESETS).toCommand())
        assertNull(MilkdropAction.Reload.toCommand())
        assertFalse(VisualsState(milkdrop = MilkdropShelf(presets)).toMilkdropModel().canReload)
    }

    @Test
    fun `a tap plays the preset as its own Style, and the one playing is marked`() {
        assertEquals(VisualsCommand.Choose(MilkdropStyles.of("aurora_veil.milk")), MilkdropAction.Play("aurora_veil.milk").toCommand())
        val shelf = MilkdropShelf(presets)

        val playing = VisualsState(current = MilkdropStyles.of("aurora_veil.milk"), milkdrop = shelf).toMilkdropModel()
        assertEquals("aurora_veil.milk", playing.playing)
        assertNull(VisualsState(current = SceneId("tunnel"), milkdrop = shelf).toMilkdropModel().playing)
        assertNull(VisualsState(milkdrop = shelf).toMilkdropModel().playing)
    }
}
