package dev.geode.render.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shader cache belongs to one install of the app (#304): another install, or none noted, empties it. */
class ShaderCacheStampTest {
    @Test
    fun `the same install keeps the cache`() {
        assertFalse(ShaderCacheStamp.stale("1700000000000", "1700000000000"))
    }

    @Test
    fun `an update or a cache from before the note empties it`() {
        assertTrue(ShaderCacheStamp.stale("1700000000000", "1700000999000"))
        assertTrue(ShaderCacheStamp.stale(null, "1700000000000"))
    }
}
