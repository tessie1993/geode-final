package dev.geode.feature.visuals.api

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folders of presets by path, and what the preset shelf answers (#144). */
class PresetsTest {
    private val top = PresetFolderPath.TOP
    private val calm = PresetFolderPath("Calm")
    private val rainy = PresetFolderPath("Calm/Rainy")
    private val calmer = PresetFolderPath("Calmer")

    @Test
    fun `a path knows its name and the folder it is in`() {
        assertTrue(top.isTop)
        assertFalse(calm.isTop)
        assertEquals("", top.name)
        assertEquals("Calm", calm.name)
        assertEquals("Rainy", rainy.name)
        assertNull(top.parent)
        assertEquals(top, calm.parent)
        assertEquals(calm, rainy.parent)
        assertEquals("Calm/Rainy", rainy.toString())
    }

    @Test
    fun `a folder inside another`() {
        assertEquals(calm, top.child("Calm"))
        assertEquals(rainy, calm.child("Rainy"))
    }

    @Test
    fun `a folder lies within itself, the top and the folders above it, never a folder named alike`() {
        assertTrue(rainy.within(rainy))
        assertTrue(rainy.within(calm))
        assertTrue(rainy.within(top))
        assertTrue(top.within(top))
        assertFalse(calmer.within(calm))
        assertFalse(calm.within(rainy))
        assertFalse(top.within(calm))
    }

    @Test
    fun `the shelf answers by id, folder and Style`() {
        val tunnel = SceneId("tunnel")
        val chill = BuiltInPreset(PresetId("Chill"), tunnel)
        val other = BuiltInPreset(PresetId("Other"), SceneId("bars"))
        val slow = SavedPreset(PresetId("Slow"), tunnel, calm)
        val drive = SavedPreset(PresetId("Drive"), tunnel)
        val shelf = PresetShelf(listOf(chill, other), listOf(slow, drive), listOf(calm, rainy, calmer))

        assertEquals(slow, shelf.saved(slow.id))
        assertNull(shelf.saved(chill.id))
        assertEquals(chill, shelf.builtIn(chill.id))
        assertNull(shelf.builtIn(slow.id))
        assertEquals(listOf(calm, calmer), shelf.foldersIn(top))
        assertEquals(listOf(rainy), shelf.foldersIn(calm))
        assertEquals(listOf(drive), shelf.savedIn(top))
        assertEquals(listOf(slow), shelf.savedIn(calm))
        assertEquals(listOf(chill), shelf.builtInFor(tunnel))
    }
}
