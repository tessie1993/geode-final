package dev.geode.screensaver

import android.graphics.Color
import android.opengl.GLSurfaceView
import android.service.dreams.DreamService
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dagger.hilt.android.AndroidEntryPoint
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.playback.PlaybackEngine
import dev.geode.render.FramePacer
import dev.geode.render.FrameRatePolicy
import dev.geode.render.VisualizerRenderer
import dev.geode.ui.SurfaceDrawing
import dev.geode.util.bestEffort
import dev.geode.wallpaper.LiveLook
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * The Visual as Android's screen saver (#406), a surface beside the widget and the live wallpaper
 * (ADR-0004): while the phone charges or sits docked, it draws the live wallpaper's look, a quality
 * tier lower ([SurfaceDrawing.forScreenSaver]).
 *
 * It listens to the app's one audio feed, as the wallpaper does: what the player plays, or silence,
 * when the Style moves on its own time. It never opens the microphone and reads no sensor. It fades
 * in from black over [FADE_IN_MS] (No-flash rule: no jump to a full-brightness frame), and stopping
 * releases its GL context and native scenes and lets go of the feed.
 */
@AndroidEntryPoint
class GeodeDreamService : DreamService() {
    /** The app's settings: the screen saver's quality and frame rate, and reduced motion. */
    @Inject
    lateinit var settings: Settings

    private var glView: GLSurfaceView? = null
    private var curtain: View? = null
    private var renderer: VisualizerRenderer? = null
    private var listening = false
    private var scenesReleased = false

    private val pacer = FramePacer(FrameRatePolicy.Capped(FramePacer.DEFAULT_TARGET_FPS)) { glView?.requestRender() }

    /** The surface's rate preference goes with the surface, so it is restated whenever one appears. */
    private val surfaceWatch =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                pacer.applyTo(holder.surface)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                pacer.applyTo(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Nothing to restate: the pacer stops with the screen saver, not with the surface.
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A touch ends the screen saver, as the platform's own do; there is nothing here to control.
        isInteractive = false
        isFullscreen = true
        val engine = VisualizerRenderer(this)
        engine.presentation = pacer
        LiveLook.restoreInto(this, engine, settings)
        renderer = engine
        val view =
            GLSurfaceView(this).apply {
                setEGLContextClientVersion(3)
                // No depth or stencil, as the wallpaper: the window only takes the finished frame.
                setEGLConfigChooser(8, 8, 8, 0, 0, 0)
                setRenderer(engine)
                // Frames only when the pacer asks, so a stopped screen saver draws nothing.
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                holder.addCallback(surfaceWatch)
            }
        // Black over the Visual until it has drawn, then faded away: the screen comes up from black.
        val cover = View(this).apply { setBackgroundColor(Color.BLACK) }
        val root =
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(view, match())
                addView(cover, match())
            }
        glView = view
        curtain = cover
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val engine = renderer ?: return
        val performance = (settings.state.value as? SettingsState.Ready)?.values?.performance
        // Visuals off (#196): nothing draws for the Visual, so the screen saver stays black.
        if (performance?.visualsOff == true) return
        performance?.let { SurfaceDrawing.forScreenSaver(it).applyTo(engine, pacer, glView?.holder?.surface) }
        glView?.onResume()
        pacer.start()
        startListening(engine)
        curtain
            ?.animate()
            ?.alpha(0f)
            ?.setStartDelay(FADE_DELAY_MS)
            ?.setDuration(FADE_IN_MS)
            ?.start()
    }

    override fun onDreamingStopped() {
        curtain?.animate()?.cancel()
        // Stopped first, so no frame is requested after this; then the native scenes go while the
        // GL thread still runs, and pausing the view drops its EGL context.
        pacer.stop()
        stopListening()
        releaseScenesOnGlThread()
        glView?.onPause()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        pacer.stop()
        stopListening()
        // Detached without having stopped dreaming (or started): the scenes still need freeing.
        releaseScenesOnGlThread()
        glView?.holder?.removeCallback(surfaceWatch)
        glView = null
        curtain = null
        renderer = null
        super.onDetachedFromWindow()
    }

    private fun startListening(engine: VisualizerRenderer) {
        if (listening) return
        listening = true
        PlaybackEngine.feed.acquire()
        engine.feed = PlaybackEngine.feed
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        PlaybackEngine.feed.release()
    }

    /**
     * projectM lives in the native heap, where losing the GL context reclaims nothing, so the scenes
     * are freed on the GL thread before it stops; waited on briefly, as the wallpaper does.
     */
    private fun releaseScenesOnGlThread() {
        val view = glView ?: return
        val active = renderer ?: return
        if (scenesReleased) return
        scenesReleased = true
        val done = CountDownLatch(1)
        view.queueEvent {
            bestEffort(TAG, "active.releaseScenes()") { active.releaseScenes() }
            done.countDown()
        }
        bestEffort(TAG, "await scene release") { done.await(GL_RELEASE_WAIT_MS, TimeUnit.MILLISECONDS) }
    }

    private companion object {
        const val TAG = "GeodeDream"

        /** Bounded on purpose: a wedged GL thread must not turn teardown into an ANR. */
        const val GL_RELEASE_WAIT_MS = 200L

        /** A few frames for the first picture to land under the black before it shows. */
        const val FADE_DELAY_MS = 150L

        /** Slow enough that the picture rises out of black rather than appearing. */
        const val FADE_IN_MS = 2_000L

        fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
