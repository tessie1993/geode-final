package dev.geode.engine.types

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        assertEquals("life_orbium", SceneId("life_orbium").toString())
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

    @Test
    fun `a MilkDrop preset's Style is named by its file, and gives it back`() {
        val ocean = MilkdropStyles.of("Ocean Deep.milk")
        assertEquals(SceneId("milkdrop:Ocean Deep.milk"), ocean)
        assertEquals("Ocean Deep.milk", MilkdropStyles.file(ocean))
        assertNull(MilkdropStyles.file(SceneId("milkdrop")))
        assertNull(MilkdropStyles.file(MilkdropStyles.of("")))
        assertNull(MilkdropStyles.file(SceneId("my milkdrop:x.milk")))
        assertEquals(SceneId("milkdrop"), MilkdropStyles.scene(ocean))
        assertEquals(SceneId("tunnel"), MilkdropStyles.scene(SceneId("tunnel")))
        assertEquals(MilkdropStyles.of(""), MilkdropStyles.scene(MilkdropStyles.of("")))
    }
}
