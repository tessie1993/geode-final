package dev.geode.data

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.render.scene.SceneIds
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Preset files and preset links predate the typed ids, and both travel between installs, so the
 * scene id has to stay a plain string on the wire in both directions.
 */
class PresetFormatTest {
    @Test
    fun `the scene id is written as the plain id string`() {
        val json = PresetStore.toJson(Preset("Kyanite", SceneId("julia"), 0.6f, 0.12f))

        assertEquals("julia", JSONObject(json).get("sceneId"))
        assertEquals("Kyanite", JSONObject(json).get("name"))
    }

    @Test
    fun `a preset written before the types existed reads back the same ids`() {
        val old = """{"name":"Kyanite","sceneId":"fluid_ink","attack":0.6,"decay":0.12}"""

        val preset = PresetStore.fromJson(old)
        assertEquals(SceneId("fluid_ink"), preset.sceneId)
        assertEquals(PresetId("Kyanite"), preset.id)
    }

    @Test
    fun `a saved preset naming a deleted Silk Style loads the default Style and keeps the rest`() {
        val saved = """{"name":"Old web","sceneId":"silk_web","attack":0.4,"decay":0.2,"speed":1.5}"""

        val preset = PresetStore.fromJson(saved)

        assertEquals(SceneIds.DEFAULT, preset.sceneId)
        assertEquals(PresetId("Old web"), preset.id)
        assertEquals(0.4f, preset.attack, 0f)
        assertEquals(0.2f, preset.decay, 0f)
        assertEquals(1.5f, preset.params.speed, 0f)
    }
}
