package dev.geode.data

import dev.geode.engine.types.SceneId
import dev.geode.render.scene.SceneParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `formDrive` was retired (#88). Preset files saved before that still carry it; they have to load
 * as if the key had never been written, keep everything else they hold, and lose it on the next save.
 */
class FormDriveRetiredPresetTest {
    private val savedWithFormDrive =
        """
        {"name":"Kyanite","sceneId":"julia","attack":0.6,"decay":0.12,
         "zoom":1.4,"rotation":0.25,"audioDrive":1.8,"formDrive":0.9}
        """.trimIndent()

    @Test
    fun `a preset that carries formDrive still loads with everything else it holds`() {
        val preset = PresetStore.fromJson(savedWithFormDrive)

        assertEquals("Kyanite", preset.name)
        assertEquals(SceneId("julia"), preset.sceneId)
        assertEquals(ATTACK, preset.attack, TOLERANCE)
        assertEquals(DECAY, preset.decay, TOLERANCE)
        assertEquals(ZOOM, preset.params.zoom, TOLERANCE)
        assertEquals(ROTATION, preset.params.rotation, TOLERANCE)
        assertEquals(AUDIO_DRIVE, preset.params.audioDrive, TOLERANCE)
    }

    @Test
    fun `a field the old preset did not set keeps its default`() {
        val preset = PresetStore.fromJson(savedWithFormDrive)

        assertEquals(SceneParams.DEFAULT.speed, preset.params.speed, TOLERANCE)
        assertEquals(SceneParams.DEFAULT.beatResponse, preset.params.beatResponse, TOLERANCE)
    }

    @Test
    fun `saving the loaded preset again drops formDrive`() {
        val resaved = JSONObject(PresetStore.toJson(PresetStore.fromJson(savedWithFormDrive)))

        assertFalse(resaved.has("formDrive"))
        assertEquals(ZOOM.toDouble(), resaved.getDouble("zoom"), TOLERANCE.toDouble())
    }

    private companion object {
        const val TOLERANCE = 1e-6f
        const val ATTACK = 0.6f
        const val DECAY = 0.12f
        const val ZOOM = 1.4f
        const val ROTATION = 0.25f
        const val AUDIO_DRIVE = 1.8f
    }
}
