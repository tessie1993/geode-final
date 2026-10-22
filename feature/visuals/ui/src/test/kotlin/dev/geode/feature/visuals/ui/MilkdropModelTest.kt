package dev.geode.feature.visuals.ui

import dev.geode.feature.visuals.api.BrokenFile
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.ImportReport
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
    private val report = ImportReport(MilkdropKind.TEXTURES, listOf("clouds.png"), listOf(BrokenFile("x.gif", ImportProblem.WRONG_TYPE)))

    @Test
    fun `before the folder is listed the screen waits, and nothing can be imported`() {
        val model = VisualsState().toMilkdropModel()

        assertEquals(MilkdropModel(loading = true, importing = false, presets = emptyList(), textures = emptyList(), report = null), model)
        assertFalse(model.canImport)
    }

    @Test
    fun `a listed folder shows its presets, textures and the last report`() {
        val model = VisualsState(milkdrop = MilkdropShelf(presets, listOf("clouds.png"), lastImport = report)).toMilkdropModel()

        assertEquals(
            MilkdropModel(loading = false, importing = false, presets = presets, textures = listOf("clouds.png"), report = report),
            model,
        )
        assertTrue(model.canImport)
    }

    @Test
    fun `while importing nothing else can be imported`() {
        val model = VisualsState(milkdrop = MilkdropShelf(presets, importing = true)).toMilkdropModel()

        assertTrue(model.importing)
        assertFalse(model.canImport)
    }

    @Test
    fun `dismissing sends a Command, and picking stays in the route`() {
        assertEquals(VisualsCommand.DismissImportReport, MilkdropAction.DismissReport.toCommand())
        assertNull(MilkdropAction.Pick(MilkdropKind.PRESETS).toCommand())
    }
}
