package dev.geode.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dagger.hilt.android.AndroidEntryPoint
import dev.geode.data.GeodePrefsFiles
import dev.geode.data.PresetStore
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.VisualSurface
import dev.geode.playback.PlaybackEngine
import dev.geode.render.FramePacer
import dev.geode.render.FrameRatePolicy
import dev.geode.render.TouchField
import dev.geode.render.VisualizerRenderer
import dev.geode.ui.SurfaceDrawing
import dev.geode.ui.ThemeStore
import dev.geode.util.bestEffort
import javax.inject.Inject

@AndroidEntryPoint
class VisualizerWallpaperService : WallpaperService() {
    /** The app's settings (#17): the wallpaper follows reduced motion as the app does. */
    @Inject
    lateinit var settings: Settings

    override fun onCreateEngine(): Engine = VisualizerEngine()

    private inner class VisualizerEngine : Engine() {
        private var glView: WallpaperGlSurfaceView? = null
        private var renderer: VisualizerRenderer? = null

        // Whether this engine watches the app's audio feed now: while it is visible.
        private var listening = false

        /**
         * Each engine instance — preview and home screen can both be live at once — owns its
         * own pacer, so the two never share a cadence or a frame-time window.
         */
        private val pacer =
            FramePacer(FrameRatePolicy.Capped(WALLPAPER_FPS)) { glView?.requestRender() }

        private var surfaceAvailable = false
        private var visible = false

        /**
         * Reused across touch events: a wallpaper is touched at the panel's rate, and this
         * runs on the main thread the launcher is also drawing on.
         */
        private val touchPoints = FloatArray(TouchField.MAX_POINTS * 2)

        /**
         * The surface the taps are mapped over. A launcher that pans a wider wallpaper places a
         * tap by the screen, so on those a tap lands a little off; a tap is a pulse, not a
         * precise point, and the platform reports no offset to correct it with.
         */
        private var surfaceWidth = 1f
        private var surfaceHeight = 1f

        private val mainHandler = Handler(Looper.getMainLooper())

        private inner class WallpaperGlSurfaceView(
            context: Context,
        ) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = surfaceHolder

            fun destroy() {
                super.onDetachedFromWindow()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            // Raw touches stay off: they include the launcher's own swipes and taps, which are not
            // the person touching the wallpaper. Only COMMAND_TAP (a tap on empty space) reaches it.
            setTouchEventsEnabled(false)
            val engine = VisualizerRenderer(this@VisualizerWallpaperService)
            renderer = engine
            restoreLiveState(engine)
            glView =
                WallpaperGlSurfaceView(this@VisualizerWallpaperService).apply {
                    setEGLContextClientVersion(3)
                    // No depth or stencil, as VisualizerView: the window only takes the finished frame.
                    setEGLConfigChooser(8, 8, 8, 0, 0, 0)
                    setRenderer(engine)
                    // The pacer asks for each frame; without a request the GL thread parks, which
                    // is how "invisible costs nothing" is enforced rather than merely intended.
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                }
        }

        private fun restoreLiveState(engine: VisualizerRenderer) {
            val prefsFiles = GeodePrefsFiles(this@VisualizerWallpaperService)
            val prefs = prefsFiles.viz
            prefs.getString("live_state", null)?.let { json ->
                runCatching { PresetStore.fromJson(json) }.getOrNull()?.let { preset ->
                    engine.requestedSceneId = preset.sceneId
                    engine.sceneParams = preset.params
                }
            }
            prefs.getString("milk_path", null)?.let { path ->
                if (java.io.File(path).isFile) engine.loadMilkPreset(path)
            }
            // Settings, once read; before then, what the old settings file said.
            engine.reducedMotion =
                (settings.state.value as? SettingsState.Ready)?.values?.behavior?.reducedMotion
                    ?: ThemeStore(prefsFiles.general).loadGui().reducedMotion
        }

        // The app's one audio feed, read natively by the renderer each frame: what plays in the app, or
        // silence when nothing does (the wallpaper then moves on the Style's own time, never on made-up
        // sound). The analysis runs while the wallpaper is visible.
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

        private fun syncRunState() {
            val engine = renderer ?: return
            if (surfaceAvailable && visible) {
                drawAsSettingsSay(engine)
                glView?.onResume()
                pacer.start()
                startListening(engine)
            } else {
                // Stopped first, and on this same main-thread callback: the vsync callback is gone
                // before onPause is even asked for, so no frame can be requested after the
                // wallpaper stops being visible.
                pacer.stop()
                glView?.onPause()
                stopListening()
            }
        }

        /**
         * The wallpaper's own quality and frame rate (#394), read each time it comes into view: a change
         * made in the app's Settings meanwhile is picked up the next time the home screen shows. Until
         * Settings are read, the engine's defaults stand.
         */
        private fun drawAsSettingsSay(engine: VisualizerRenderer) {
            val performance = (settings.state.value as? SettingsState.Ready)?.values?.performance ?: return
            SurfaceDrawing.of(performance, VisualSurface.WALLPAPER).applyTo(engine, pacer, surfaceHolder?.surface)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            // Going away with a finger still down is not guaranteed to come with a cancel,
            // and a pointer that is never retired stays pinned across the whole sleep.
            if (!visible) renderer?.submitTouchPoints(touchPoints, 0)
            syncRunState()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceAvailable = true
            pacer.applyTo(holder.surface)
            syncRunState()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width.toFloat().coerceAtLeast(1f)
            surfaceHeight = height.toFloat().coerceAtLeast(1f)
            // A rotation or a fold replaces the surface, and the rate preference lives on the
            // surface, so it has to be restated rather than assumed to have survived.
            pacer.applyTo(holder.surface)
        }

        /** Ends a tap's short touch; preallocated so a tap allocates nothing. */
        private val endTap = Runnable { renderer?.submitTouchPoints(touchPoints, 0) }

        /**
         * The wallpaper's whole input: a tap on empty space of the home screen, as the platform's
         * own live wallpapers take it. It becomes one short touch, in the same y-up NDC the app
         * publishes, so a Style answers it exactly as it answers a tap in the app; the release
         * wake does the fading.
         *
         * There is no drag, smear or pinch here on purpose: the launcher owns swipes (they page the
         * home screen), and a wallpaper has no controls to undo a stray pinch with.
         */
        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean,
        ): Bundle? {
            val engine = renderer
            if (action == WallpaperManager.COMMAND_TAP && engine != null) {
                touchPoints[0] = x / surfaceWidth * 2f - 1f
                touchPoints[1] = 1f - y / surfaceHeight * 2f
                engine.submitTouchPoints(touchPoints, 1)
                mainHandler.removeCallbacks(endTap)
                mainHandler.postDelayed(endTap, TAP_MS)
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceAvailable = false
            syncRunState()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            mainHandler.removeCallbacks(endTap)
            pacer.stop()
            stopListening()
            releaseScenesOnGlThread()
            glView?.destroy()
            glView = null
            renderer = null
            super.onDestroy()
        }

        /**
         * The engine is discarded here, and MilkdropScene's projectM instance lives in the native
         * heap, where losing the GL context reclaims nothing. A wallpaper engine is created and
         * destroyed every time the screen sleeps or the user switches launcher screens, so without
         * this each of those leaks one engine into a process that keeps running.
         *
         * Queued before destroy() because GLSurfaceView's thread stops draining events once it is
         * asked to exit, and waited on - briefly - because that thread is gone afterwards.
         */
        private fun releaseScenesOnGlThread() {
            val view = glView ?: return
            val active = renderer ?: return
            val done = java.util.concurrent.CountDownLatch(1)
            view.queueEvent {
                bestEffort(TAG, "active.releaseScenes()") { active.releaseScenes() }
                done.countDown()
            }
            bestEffort(TAG, "await scene release") {
                done.await(GL_RELEASE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    private companion object {
        const val TAG = "VisualizerWallpaper"

        /** Bounded on purpose: a wedged GL thread must not turn teardown into an ANR. */
        const val GL_RELEASE_WAIT_MS = 200L

        /** About five frames: long enough for the engine to see a tap, short enough not to linger. */
        const val TAP_MS = 90L

        /**
         * What the wallpaper asks for until Settings are read; after that its frame rate is the one
         * Settings give the wallpaper surface (#394). The note below is why this start is not lower.
         *
         * The same target the app renders at, deliberately — not the 24–30 fps §4.4 of the
         * quality bar wants from a wallpaper.
         *
         * The reduced rate is the right end state, but it cannot be switched on from here alone:
         * the native fluid quality ladder (`FluidSceneBase::autoQualityTick` in `core/viz/scenes`)
         * reads GPU pressure off the achieved frame rate through its `PerformanceMonitor`
         * (target 50 fps), an inference that only holds while the renderer
         * free-runs. Ask for 30 and every fluid scene on the wallpaper reads its own cap as a
         * device that cannot keep up and downgrades itself two quality tiers within three
         * seconds. Teaching that monitor the difference between "capped" and "struggling" comes
         * first; then this becomes a one-line change.
         */
        const val WALLPAPER_FPS = FramePacer.DEFAULT_TARGET_FPS
    }
}
