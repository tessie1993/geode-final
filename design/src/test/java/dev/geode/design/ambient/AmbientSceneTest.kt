package dev.geode.design.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientSceneTest {
    private val far = ShippableAsset("mote", DepthLayer.Far, FAR_ART)
    private val mid = ShippableAsset("leaf", DepthLayer.Mid, MID_ART)
    private val near = ShippableAsset("pebble", DepthLayer.Near, NEAR_ART)
    private val assets = listOf(near, mid, far)

    @Test
    fun `nothing approved places nothing`() {
        assertEquals(emptyList<AmbientSprite>(), AmbientScene.place(emptyList(), SEED))
        assertEquals(emptyList<AmbientSprite>(), AmbientScene.place(assets, SEED, maxOnScreen = 0))
    }

    @Test
    fun `at most about twelve on screen`() {
        assertEquals(12, AmbientScene.MAX_ON_SCREEN)
        assertEquals(AmbientScene.MAX_ON_SCREEN, AmbientScene.place(assets, SEED).size)
        assertEquals(AmbientScene.MAX_ON_SCREEN, AmbientScene.place(List(MANY) { mid.copy(id = "leaf$it") }, SEED).size)
    }

    @Test
    fun `few assets are reused in turn`() {
        val placed = AmbientScene.place(assets, SEED)
        for (asset in assets) {
            assertEquals(AmbientScene.MAX_ON_SCREEN / assets.size, placed.count { it.asset == asset })
        }
    }

    @Test
    fun `the same seed places the same scene`() {
        assertEquals(AmbientScene.place(assets, SEED), AmbientScene.place(assets, SEED))
        assertNotEquals(AmbientScene.place(assets, SEED), AmbientScene.place(assets, OTHER_SEED))
    }

    @Test
    fun `sprites are drawn back to front`() {
        val layers = AmbientScene.place(assets, SEED).map { it.asset.layer.ordinal }
        assertEquals(layers.sorted(), layers)
    }

    @Test
    fun `the centre is left clear and both edges are used`() {
        val placed = AmbientScene.place(assets, SEED)
        for (sprite in placed) {
            val nearEdge = sprite.startX <= AmbientScene.EDGE_BAND || sprite.startX >= 1f - AmbientScene.EDGE_BAND
            assertTrue("${sprite.startX} is in the centre", nearEdge)
            assertTrue(sprite.startY in 0f..1f)
            assertTrue(sprite.startRotation in -AmbientScene.MAX_START_ROTATION..AmbientScene.MAX_START_ROTATION)
            assertTrue(sprite.spin == 1 || sprite.spin == -1)
        }
        assertTrue(placed.any { it.startX < HALF_WIDTH })
        assertTrue(placed.any { it.startX > HALF_WIDTH })
    }

    @Test
    fun `at rest a sprite is exactly where it was placed`() {
        for (sprite in AmbientScene.place(assets, SEED)) {
            assertEquals(SpritePose(sprite.startX, sprite.startY, sprite.startRotation), AmbientScene.poseAt(sprite, 0f))
        }
    }

    @Test
    fun `a sprite drifts down at its layer's pace and turns slowly`() {
        val sprite = sprite(near, startY = 0.2f)
        val pose = AmbientScene.poseAt(sprite, STEP_SECONDS)
        assertEquals(0.2f + STEP_SECONDS / DepthLayer.Near.traverseSeconds, pose.y, TOLERANCE)
        assertEquals(sprite.spin * AmbientScene.SPIN_DEGREES_PER_SECOND * STEP_SECONDS, pose.rotation, TOLERANCE)
        assertTrue(kotlin.math.abs(pose.x - sprite.startX) <= 2f * AmbientScene.SWAY_FRACTION)
        assertNotEquals(sprite.startX, pose.x)
    }

    @Test
    fun `nearer layers drift further in the same time`() {
        val drift =
            DepthLayer.entries.map { layer ->
                AmbientScene.poseAt(sprite(far.copy(layer = layer), startY = 0f), STEP_SECONDS).y
            }
        assertEquals(drift.sorted(), drift)
        assertTrue(drift.zipWithNext().all { (back, front) -> front > back })
    }

    @Test
    fun `scrolling moves each layer by its parallax`() {
        for (layer in DepthLayer.entries) {
            val pose = AmbientScene.poseAt(sprite(far.copy(layer = layer), startY = HALF_WIDTH), 0f, SCROLL)
            assertEquals(HALF_WIDTH - SCROLL * layer.parallax, pose.y, TOLERANCE)
        }
    }

    @Test
    fun `a sprite wraps round once it is out of sight`() {
        val bottom = 1f + AmbientScene.WRAP_MARGIN
        assertEquals(-AmbientScene.WRAP_MARGIN + PAST, AmbientScene.wrap(bottom + PAST), TOLERANCE)
        assertEquals(0.5f, AmbientScene.wrap(0.5f), TOLERANCE)
        assertEquals(1f, AmbientScene.wrap(1f), TOLERANCE)
        assertEquals(bottom - TOLERANCE * 2f, AmbientScene.wrap(bottom - TOLERANCE * 2f), TOLERANCE)
        assertEquals(0.5f, AmbientScene.wrap(0.5f - (1f + 2f * AmbientScene.WRAP_MARGIN)), TOLERANCE)
        for (seconds in listOf(0f, 10f, 1_000f, 100_000f)) {
            val y = AmbientScene.poseAt(sprite(near, startY = 0.9f), seconds).y
            assertTrue("$y out of band", y >= -AmbientScene.WRAP_MARGIN && y < bottom)
        }
    }

    private fun sprite(
        asset: ShippableAsset,
        startY: Float,
    ) = AmbientSprite(asset, startX = 0.1f, startY = startY, swayPhase = 0f, startRotation = 0f, spin = 1)

    private companion object {
        const val SEED = 7L
        const val OTHER_SEED = 8L
        const val MANY = 40
        const val FAR_ART = 1
        const val MID_ART = 2
        const val NEAR_ART = 3
        const val STEP_SECONDS = 4f
        const val SCROLL = 0.25f
        const val HALF_WIDTH = 0.5f
        const val TOLERANCE = 1e-4f
        const val PAST = 0.1f
    }
}
