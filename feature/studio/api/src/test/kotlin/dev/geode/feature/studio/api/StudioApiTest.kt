package dev.geode.feature.studio.api

import dev.geode.engine.types.Millis
import org.junit.Assert.assertEquals
import org.junit.Test

class StudioApiTest {
    private fun sized(
        width: Int,
        height: Int,
    ) = Video(VideoId(1), ProjectId(1), VideoKind.SHORT, "v", "content://v", null, Millis(1), width, height, "")

    @Test
    fun `a video's shape follows its size`() {
        assertEquals(Shape.LANDSCAPE, sized(1920, 1080).shape)
        assertEquals(Shape.PORTRAIT, sized(1080, 1920).shape)
        assertEquals(Shape.SQUARE, sized(1080, 1080).shape)
    }

    @Test
    fun `ids name themselves`() {
        assertEquals("project 3", ProjectId(3).toString())
        assertEquals("video 4", VideoId(4).toString())
    }
}
