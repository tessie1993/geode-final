package dev.geode.engine.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The Kotlin half of the one snapshot schema. The fixture is the one core/tests/bridge_snapshot_codec_test.cpp
 * encodes, so the shared digest proves Kotlin writes byte for byte what C++ reads.
 */
class CustomizationSnapshotTest {
    private fun fixture(): CustomizationSnapshot =
        CustomizationSnapshot(
            revision = 7,
            reducedMotion = true,
            params = List(CustomizationSnapshot.PARAMS_COUNT) { it * 0.5f },
            lfo = List(CustomizationSnapshot.LFO_COUNT) { it * 0.25f },
            adsr = List(CustomizationSnapshot.ADSR_COUNT) { -it * 0.125f },
            soundSensitivity = 1.5f,
            soundRefractoryMs = 80f,
            soundAttackSeconds = 0.25f,
            soundReleaseSeconds = 0.5f,
        )

    private fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun wire(snapshot: CustomizationSnapshot): ByteBuffer = ByteBuffer.wrap(snapshot.encode()).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `encodes byte for byte what the C++ codec expects`() {
        assertEquals(FIXTURE_DIGEST, hex(fixture().encode()))
    }

    @Test
    fun `is exactly the schema's size`() {
        assertEquals(CustomizationSnapshot.WIRE_BYTES, fixture().encode().size)
        assertEquals(HEADER + PAYLOAD, CustomizationSnapshot.WIRE_BYTES)
    }

    @Test
    fun `starts with the magic, the version, a zero and the payload length`() {
        val bytes = fixture().encode()
        assertArrayEquals("GCS1".toByteArray(Charsets.US_ASCII), bytes.copyOfRange(0, MAGIC_BYTES))
        val header = wire(fixture())
        assertEquals(CustomizationSnapshot.MAGIC, header.getInt(0))
        assertEquals(SCHEMA_VERSION, header.getShort(VERSION_AT))
        assertEquals(0.toShort(), header.getShort(RESERVED_AT))
        assertEquals(PAYLOAD, header.getInt(PAYLOAD_LENGTH_AT))
    }

    @Test
    fun `writes the fields in schema order`() {
        val body = wire(fixture())
        assertEquals(7, body.getInt(REVISION_AT))
        assertEquals(1.toByte(), body.get(REDUCED_MOTION_AT))
        assertEquals(0.5f, body.getFloat(PARAMS_AT + Float.SIZE_BYTES))
        assertEquals(0.25f, body.getFloat(LFO_AT + Float.SIZE_BYTES))
        assertEquals(-0.125f, body.getFloat(ADSR_AT + Float.SIZE_BYTES))
        assertEquals(1.5f, body.getFloat(SOUND_AT))
        assertEquals(0.5f, body.getFloat(SOUND_AT + 3 * Float.SIZE_BYTES))
    }

    @Test
    fun `writes reduced motion off as zero`() {
        assertEquals(0.toByte(), wire(fixture().copy(reducedMotion = false)).get(REDUCED_MOTION_AT))
    }

    @Test
    fun `a changed field changes the bytes`() {
        assertNotEquals(hex(fixture().encode()), hex(fixture().copy(soundRefractoryMs = 81f).encode()))
    }

    @Test
    fun `refuses arrays of the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) { fixture().copy(params = List(1) { 0f }) }
        assertThrows(IllegalArgumentException::class.java) { fixture().copy(lfo = List(CustomizationSnapshot.LFO_COUNT + 1) { 0f }) }
        assertThrows(IllegalArgumentException::class.java) { fixture().copy(adsr = emptyList()) }
    }

    @Test
    fun `refuses a negative revision but takes zero`() {
        assertThrows(IllegalArgumentException::class.java) { fixture().copy(revision = -1) }
        assertEquals(0, fixture().copy(revision = 0).revision)
    }

    private companion object {
        // The digest core/tests/bridge_snapshot_codec_test.cpp expects too; it changes with the schema's layout.
        const val FIXTURE_DIGEST = "283b73e6f1ee6bdb852e39f44b13ed9bdba8c07ad82075b2fe515a9a3eacbb1b"
        const val HEADER = 12
        const val SOUND_FIELDS = 4
        const val ARRAY_FLOATS = CustomizationSnapshot.PARAMS_COUNT + CustomizationSnapshot.LFO_COUNT + CustomizationSnapshot.ADSR_COUNT
        const val PAYLOAD = Int.SIZE_BYTES + 1 + (ARRAY_FLOATS + SOUND_FIELDS) * Float.SIZE_BYTES
        const val MAGIC_BYTES = 4
        const val SCHEMA_VERSION: Short = 1
        const val VERSION_AT = 4
        const val RESERVED_AT = 6
        const val PAYLOAD_LENGTH_AT = 8
        const val REVISION_AT = HEADER
        const val REDUCED_MOTION_AT = REVISION_AT + Int.SIZE_BYTES
        const val PARAMS_AT = REDUCED_MOTION_AT + 1
        const val LFO_AT = PARAMS_AT + CustomizationSnapshot.PARAMS_COUNT * Float.SIZE_BYTES
        const val ADSR_AT = LFO_AT + CustomizationSnapshot.LFO_COUNT * Float.SIZE_BYTES
        const val SOUND_AT = ADSR_AT + CustomizationSnapshot.ADSR_COUNT * Float.SIZE_BYTES
    }
}
