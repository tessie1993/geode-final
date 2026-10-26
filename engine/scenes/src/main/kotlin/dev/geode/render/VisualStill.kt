package dev.geode.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One still of the live Visual (#405): the frame as it was drawn to the window, the Visual alone and
 * never the App UI, at the window's full size. [rgba] holds [width] x [height] pixels of 8-bit red,
 * green, blue and alpha, as GL reads them: the bottom row first. Each request gets a still of its
 * own, so whoever asked may change it.
 */
class VisualStill(
    val width: Int,
    val height: Int,
    val rgba: ByteBuffer,
)

/** Reads the frame just drawn to the window, before it is shown. GL thread only. */
internal object StillReader {
    private const val BYTES_PER_PIXEL = 4

    /** GL's own pack alignment, put back after the read. */
    private const val DEFAULT_PACK_ALIGNMENT = 4

    /** How many errors left over from the frame are cleared before the read: GL keeps one per kind. */
    private const val MAX_STALE_ERRORS = 8

    /** The window's frame at [width] x [height]; null when there is none or it could not be read. */
    @Suppress("SwallowedException") // A still too big to hold is a still not taken; the Visual goes on.
    fun read(
        width: Int,
        height: Int,
    ): VisualStill? {
        if (width <= 0 || height <= 0) return null
        val pixels =
            try {
                ByteBuffer.allocateDirect(width * height * BYTES_PER_PIXEL).order(ByteOrder.nativeOrder())
            } catch (tooBig: OutOfMemoryError) {
                return null
            }
        // Errors the frame left are not this read's.
        var stale = 0
        while (stale < MAX_STALE_ERRORS && GLES30.glGetError() != GLES30.GL_NO_ERROR) stale++
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        val error = GLES30.glGetError()
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, DEFAULT_PACK_ALIGNMENT)
        if (error != GLES30.GL_NO_ERROR) return null
        pixels.rewind()
        return VisualStill(width, height, pixels)
    }
}
