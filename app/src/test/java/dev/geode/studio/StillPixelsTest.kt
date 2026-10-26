package dev.geode.studio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/** A Snapshot's rows put the right way up (#405): GL reads the bottom row first. */
class StillPixelsTest {
    private fun flipped(
        bytes: ByteArray,
        rowBytes: Int,
        rows: Int,
        direct: Boolean = false,
    ): Pair<ByteArray, Int> {
        val buffer = if (direct) ByteBuffer.allocateDirect(bytes.size).put(bytes) else ByteBuffer.wrap(bytes.copyOf())
        buffer.position(bytes.size / 2)
        StillPixels.flipRows(buffer, rowBytes, rows)
        val position = buffer.position()
        val out = ByteArray(bytes.size)
        buffer.get(out)
        return out to position
    }

    @Test
    fun `an even number of rows is turned upside down, each row kept whole`() {
        val (out, position) = flipped(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), rowBytes = 2, rows = 4)

        assertArrayEquals(byteArrayOf(7, 8, 5, 6, 3, 4, 1, 2), out)
        assertEquals(0, position)
    }

    @Test
    fun `an odd number of rows keeps the middle one in place`() {
        val (out, _) = flipped(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9), rowBytes = 3, rows = 3, direct = true)

        assertArrayEquals(byteArrayOf(7, 8, 9, 4, 5, 6, 1, 2, 3), out)
    }

    @Test
    fun `one row stays as it is`() {
        val (out, position) = flipped(byteArrayOf(1, 2, 3, 4), rowBytes = 4, rows = 1)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), out)
        assertEquals(0, position)
    }
}
