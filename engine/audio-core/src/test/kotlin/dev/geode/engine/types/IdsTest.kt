package dev.geode.engine.types

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Ids are written into JSON, SharedPreferences and log lines by string interpolation in places
 * that predate the types, so printing one must give the raw value and never `TrackId(value=…)`.
 */
class IdsTest {
    @Test
    fun `a track id prints as its uri`() {
        assertEquals("content://media/external/audio/media/42", TrackId("content://media/external/audio/media/42").toString())
        assertEquals("uri:content://a", "uri:${TrackId("content://a")}")
    }

    @Test
    fun `a scene id prints as the engine's id`() {
        assertEquals("silk_web", SceneId("silk_web").toString())
    }

    @Test
    fun `a preset id prints as the preset's name`() {
        assertEquals("Kyanite", PresetId("Kyanite").toString())
    }

    @Test
    fun `ids are equal exactly when their values are`() {
        assertEquals(TrackId("content://a"), TrackId("content://a"))
        assertNotEquals(TrackId("content://a"), TrackId("content://A"))
        assertEquals(setOf(SceneId("julia")), setOf(SceneId("julia"), SceneId("julia")))
        assertNotEquals(PresetId("a"), PresetId("b"))
    }
}
