package dev.geode.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.Surface
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig as Egl10Config
import javax.microedition.khronos.egl.EGLDisplay as Egl10Display

/**
 * A Recording's encoder surface as the live renderer draws into it (#317). Handed to
 * [VisualizerRenderer.recording]; the renderer counts the frames it put there, says when it could not,
 * and when it has let the surface go, so the recorder knows when the encoder may stop.
 */
class RecordingTarget(
    val surface: Surface,
) {
    /** Frames drawn into [surface] so far. */
    @Volatile
    var framesDrawn: Long = 0L
        internal set

    /** The live renderer could not draw into [surface]; it stopped trying. */
    @Volatile
    var failed: Boolean = false
        internal set

    /** The live renderer no longer holds [surface]: it was taken off, or failed. */
    @Volatile
    var released: Boolean = false
        internal set
}

/**
 * The second place each live frame goes while a Recording runs (#317): the encoder's surface, on the
 * live renderer's own EGL display and context. After a frame is drawn to the window, it is copied from
 * the window to the encoder surface and stamped with its time, and the window is made current again,
 * so the Visual is drawn once and the Recording holds the Visual alone, never the App UI over it.
 * GL thread only.
 */
internal class FrameRecorder(
    val target: RecordingTarget,
) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** Copies the frame just drawn to the window into the encoder; false when it could not. */
    fun draw(timeNs: Long): Boolean {
        val current = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        val window = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        if (current == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT || window == EGL14.EGL_NO_SURFACE) return false
        if (surface == EGL14.EGL_NO_SURFACE || display != current) {
            display = current
            surface = createSurface(current, context) ?: return false
        }
        val width = query(window, EGL14.EGL_WIDTH)
        val height = query(window, EGL14.EGL_HEIGHT)
        val toWidth = query(surface, EGL14.EGL_WIDTH)
        val toHeight = query(surface, EGL14.EGL_HEIGHT)
        if (width <= 0 || height <= 0 || toWidth <= 0 || toHeight <= 0) return false
        // Drawn into the encoder, read from the window: one blit copies the finished frame across.
        if (!EGL14.eglMakeCurrent(display, surface, window, context)) return false
        val scissor = GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST)
        if (scissor) GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, toWidth, toHeight, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
        if (scissor) GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        EGLExt.eglPresentationTimeANDROID(display, surface, timeNs)
        val swapped = EGL14.eglSwapBuffers(display, surface)
        // The window again, whatever happened, so the frame on screen is presented as always.
        val back = EGL14.eglMakeCurrent(display, window, window, context)
        return swapped && back
    }

    /** Lets the encoder surface go; the renderer stops drawing into it. */
    fun release() {
        if (surface != EGL14.EGL_NO_SURFACE && display != EGL14.EGL_NO_DISPLAY) EGL14.eglDestroySurface(display, surface)
        surface = EGL14.EGL_NO_SURFACE
        display = EGL14.EGL_NO_DISPLAY
        target.released = true
    }

    /**
     * Forgets the encoder surface without destroying it: the live context and display it was made on
     * are gone (GLSurfaceView tore them down), and with them every surface on them.
     */
    fun forget() {
        surface = EGL14.EGL_NO_SURFACE
        display = EGL14.EGL_NO_DISPLAY
    }

    /** A window surface on the encoder, in the live context's own config so the context can draw it. */
    private fun createSurface(
        display: EGLDisplay,
        context: EGLContext,
    ): EGLSurface? {
        val id = IntArray(1)
        if (!EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0)) return null
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val wanted = intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE)
        if (!EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) || count[0] < 1) return null
        val config = configs[0] ?: return null
        val made = EGL14.eglCreateWindowSurface(display, config, target.surface, intArrayOf(EGL14.EGL_NONE), 0)
        return made.takeIf { it != EGL14.EGL_NO_SURFACE }
    }

    private fun query(
        of: EGLSurface,
        what: Int,
    ): Int {
        val value = IntArray(1)
        return if (EGL14.eglQuerySurface(display, of, what, value, 0)) value[0] else 0
    }
}

/**
 * The live Visual's EGL config: 8-bit RGB with no alpha, depth or stencil (#311), recordable when the
 * phone offers it, so a Recording's encoder surface can be drawn in the same context (#317). Without a
 * recordable one it takes the same config it always did.
 */
internal class RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(
        egl: EGL10,
        display: Egl10Display,
    ): Egl10Config =
        pick(egl, display, recordable = true)
            ?: pick(egl, display, recordable = false)
            ?: throw IllegalArgumentException("No EGL config for the live Visual")

    private fun pick(
        egl: EGL10,
        display: Egl10Display,
        recordable: Boolean,
    ): Egl10Config? {
        val base =
            intArrayOf(
                EGL10.EGL_RED_SIZE,
                COLOUR_BITS,
                EGL10.EGL_GREEN_SIZE,
                COLOUR_BITS,
                EGL10.EGL_BLUE_SIZE,
                COLOUR_BITS,
                EGL10.EGL_ALPHA_SIZE,
                0,
                EGL10.EGL_DEPTH_SIZE,
                0,
                EGL10.EGL_STENCIL_SIZE,
                0,
                EGL10.EGL_RENDERABLE_TYPE,
                EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            )
        val attribs = if (recordable) base + intArrayOf(EGL_RECORDABLE_ANDROID, 1, EGL10.EGL_NONE) else base + EGL10.EGL_NONE
        val count = IntArray(1)
        if (!egl.eglChooseConfig(display, attribs, null, 0, count) || count[0] <= 0) return null
        val configs = arrayOfNulls<Egl10Config>(count[0])
        if (!egl.eglChooseConfig(display, attribs, configs, count[0], count)) return null
        // As GLSurfaceView's own chooser: exactly the colour asked for, the first that matches.
        return configs.filterNotNull().firstOrNull { config ->
            attribute(egl, display, config, EGL10.EGL_RED_SIZE) == COLOUR_BITS &&
                attribute(egl, display, config, EGL10.EGL_GREEN_SIZE) == COLOUR_BITS &&
                attribute(egl, display, config, EGL10.EGL_BLUE_SIZE) == COLOUR_BITS &&
                attribute(egl, display, config, EGL10.EGL_ALPHA_SIZE) == 0
        }
    }

    private fun attribute(
        egl: EGL10,
        display: Egl10Display,
        config: Egl10Config,
        name: Int,
    ): Int {
        val value = IntArray(1)
        return if (egl.eglGetConfigAttrib(display, config, name, value)) value[0] else -1
    }

    private companion object {
        const val COLOUR_BITS = 8

        /** EGL_RECORDABLE_ANDROID: the config can draw into a video encoder's surface. */
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
