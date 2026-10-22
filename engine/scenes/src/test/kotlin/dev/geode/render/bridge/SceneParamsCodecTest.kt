package dev.geode.render.bridge

import dev.geode.engine.bridge.CustomizationSnapshot
import dev.geode.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** The scene parameter frame that crosses the C ABI, after `formDrive` was retired (#88). */
class SceneParamsCodecTest {
    @Test
    fun `the frame has one slot per named field`() {
        assertEquals(SceneParamsCodec.FIELDS, SceneParamsCodec.NAMES.size)
        assertEquals(SceneParamsCodec.NAMES.size, SceneParamsCodec.NAMES.toSet().size)
    }

    @Test
    fun `the Customization snapshot carries exactly this frame`() {
        assertEquals(SceneParamsCodec.FIELDS, CustomizationSnapshot.PARAMS_COUNT)
    }

    @Test
    fun `formDrive no longer crosses the bridge`() {
        assertFalse("formDrive" in SceneParamsCodec.NAMES)
    }

    @Test
    fun `the last slot is the ripple overlay specular`() {
        val out = FloatArray(SceneParamsCodec.FIELDS)
        SceneParamsCodec.pack(SceneParams(rippleOverlaySpecular = LAST_VALUE), out)

        assertEquals("rippleOverlaySpecular", SceneParamsCodec.NAMES.last())
        assertEquals(LAST_VALUE, out.last(), 0f)
    }

    @Test
    fun `a frame shorter than the field count is refused`() {
        assertThrows(IllegalStateException::class.java) {
            SceneParamsCodec.pack(SceneParams.DEFAULT, FloatArray(SceneParamsCodec.FIELDS - 1))
        }
    }

    @Test
    fun `verify accepts the native order and refuses a frame that still names formDrive`() {
        SceneParamsCodec.verify(SceneParamsCodec.NAMES.joinToString("\n"))
        assertThrows(IllegalStateException::class.java) {
            SceneParamsCodec.verify((SceneParamsCodec.NAMES + "formDrive").joinToString("\n"))
        }
    }

    private companion object {
        const val LAST_VALUE = 0.77f
    }
}
