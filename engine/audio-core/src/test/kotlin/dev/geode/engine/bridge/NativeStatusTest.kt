package dev.geode.engine.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeStatusTest {
    @Test
    fun `codes are GeodeStatus's, in schema order`() {
        assertEquals(
            listOf(
                NativeStatus.OK,
                NativeStatus.NULL_HANDLE,
                NativeStatus.INVALID_ARGUMENT,
                NativeStatus.MALFORMED_SNAPSHOT,
                NativeStatus.UNSUPPORTED_VERSION,
                NativeStatus.BUSY,
                NativeStatus.OUT_OF_MEMORY,
                NativeStatus.ENGINE_FAILURE,
            ),
            (0..7).map { NativeStatus.of(it) },
        )
    }

    @Test
    fun `a code the schema does not know has no status`() {
        assertNull(NativeStatus.of(-1))
        assertNull(NativeStatus.of(NativeStatus.entries.size))
    }

    @Test
    fun `each status keeps its own code`() {
        NativeStatus.entries.forEach { assertEquals(it, NativeStatus.of(it.code)) }
        assertEquals(7, NativeStatus.ENGINE_FAILURE.code)
    }
}
