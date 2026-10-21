package dev.geode.ui

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.render.scene.SceneIds
import org.junit.Assert.assertEquals
import org.junit.Test

/** A stored Style playlist that names a deleted Silk Style plays the default Style in its place. */
class AutoVisualsPlaylistFallbackTest {
    @Test
    fun `a playlist entry naming a deleted Silk Style plays the default Style under its label`() {
        val stored = """[{"sceneId":"silk_bloom","label":"Cosine Bloom"}]"""

        val entry = AutoVisualsPrefsStore.entriesFromJson(stored).single()

        assertEquals(SceneIds.DEFAULT, entry.sceneId)
        assertEquals(sceneDisplayLabel(SceneIds.DEFAULT), entry.label)
    }

    @Test
    fun `a preset entry whose Style is gone keeps the preset's name`() {
        val stored = """[{"sceneId":"silk_web","presetName":"Evening","label":"Evening"}]"""

        val entry = AutoVisualsPrefsStore.entriesFromJson(stored).single()

        assertEquals(SceneIds.DEFAULT, entry.sceneId)
        assertEquals(PresetId("Evening"), entry.presetName)
        assertEquals("Evening", entry.label)
    }

    @Test
    fun `an entry naming a Style that exists is read back unchanged`() {
        val entries = listOf(VizPlaylistEntry(sceneId = SceneId("julia"), label = "Julia"))

        assertEquals(entries, AutoVisualsPrefsStore.entriesFromJson(AutoVisualsPrefsStore.entriesToJson(entries)))
    }
}
