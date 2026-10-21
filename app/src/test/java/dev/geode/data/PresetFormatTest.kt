package dev.geode.data

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.render.scene.SceneIds
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a preset saved with a user shader loads on the default Style and keeps its Customization`() {
        val old =
            """{"name":"Mine","sceneId":"julia","attack":0.4,"decay":0.2,"zoom":1.7,""" +
                """"customShader":"void main() { fragColor = vec4(1.0); }"}"""

        val preset = PresetStore.fromJson(old)

        assertEquals(SceneIds.DEFAULT, preset.sceneId)
        assertEquals(PresetId("Mine"), preset.id)
        assertEquals(0.4f, preset.attack, EXACT)
        assertEquals(0.2f, preset.decay, EXACT)
        assertEquals(1.7f, preset.params.zoom, EXACT)
    }

    @Test
    fun `a preset without a user shader keeps the Style it names`() {
        val preset = PresetStore.fromJson("""{"name":"Kyanite","sceneId":"julia","attack":0.6,"decay":0.12}""")

        assertEquals(SceneId("julia"), preset.sceneId)
    }

    @Test
    fun `a preset loaded from a user-shader file is saved back without the shader`() {
        val old = """{"name":"Mine","sceneId":"julia","attack":0.4,"decay":0.2,"customShader":"void main() {}"}"""

        val resaved = JSONObject(PresetStore.toJson(PresetStore.fromJson(old)))

        assertFalse(resaved.has(PresetStore.LEGACY_USER_SHADER_KEY))
        assertEquals(SceneIds.DEFAULT.value, resaved.get("sceneId"))
    }
}

/** The values round-trip through JSON as the same floats, so no tolerance is needed. */
private const val EXACT = 0f
