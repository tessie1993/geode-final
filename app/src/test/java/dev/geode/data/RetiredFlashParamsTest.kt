package dev.geode.data

import dev.geode.engine.types.SceneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The No-flash rule removed the Strobe and Flash parameters (#89). Presets saved before carry
 * `strobe` and `flash` keys; they must still load, with every other value intact, and nothing
 * written now may carry those keys again.
 */
class RetiredFlashParamsTest {
    @Test
    fun `a preset saved with strobe and flash still loads with its other values`() {
        val old =
            """{"name":"Old strobe","sceneId":"julia","attack":0.5,"decay":0.2,""" +
                """"strobe":0.5,"flash":0.6,"speed":1.5,"glitch":0.2,"bloom":0.45}"""

        val preset = PresetStore.fromJson(old)

        assertEquals("Old strobe", preset.name)
        assertEquals(SceneId("julia"), preset.sceneId)
        assertEquals(1.5f, preset.params.speed)
        assertEquals(0.2f, preset.params.glitch)
        assertEquals(0.45f, preset.params.bloom)
    }

    @Test
    fun `a preset written now carries neither strobe nor flash`() {
        val old = """{"name":"Old","sceneId":"plasma","attack":0.5,"decay":0.2,"strobe":0.4,"flash":0.3}"""

        val written = JSONObject(PresetStore.toJson(PresetStore.fromJson(old)))

        assertFalse(written.has("strobe"))
        assertFalse(written.has("flash"))
        assertEquals("plasma", written.getString("sceneId"))
    }
}
