package dev.geode.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import dev.geode.analysis.AudioFeatures
import dev.geode.analysis.DisplayEvents
import dev.geode.analysis.SignalHold
import dev.geode.engine.audio.VisualFeed
import dev.geode.engine.bridge.NativeSnapshots
import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.render.bridge.NativeViz
import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.SceneParams
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The GL-thread adapter over the native renderer: every frame is one `geode_viz_render`.
 *
 * The `@Volatile` properties are the state the UI publishes from other threads; each frame hands
 * the current values across before rendering.
 */
class VisualizerRenderer(
    context: Context,
) : GLSurfaceView.Renderer {
    companion object {
        /** How long a change of Style crossfades when Style switching does not say. */
        val DEFAULT_TRANSITION: Millis = Millis(1200L)

        /** The floats a parameter fade must never glide, and why. */
        val NOT_FADED: Map<String, String> =
            mapOf(
                "paramFadeSec" to "the fade's own time constant: gliding it would make every fade chase a moving target",
                "paletteBaseOverride" to "UNSET_OVERRIDE (-1) is a sentinel: a glide through it flickers between set and unset",
                "paletteRangeOverride" to "UNSET_OVERRIDE sentinel, as paletteBaseOverride",
                "palette2BaseOverride" to "UNSET_OVERRIDE sentinel, as paletteBaseOverride",
                "palette2RangeOverride" to "UNSET_OVERRIDE sentinel, as paletteBaseOverride",
            )
    }

    @Volatile
    var features: AudioFeatures = AudioFeatures.empty()

    @Volatile
    var requestedSceneId: SceneId = SceneIds.DEFAULT

    @Volatile
    var sceneParams: SceneParams = SceneParams.DEFAULT

    /**
     * The Visual quality on screen (Settings' CPU/GPU section, #28) as a render scale ceiling, 1 for
     * full quality. The automatic cooling when the phone is hot can only lower it further.
     */
    @Volatile
    var qualityCeiling: Float = 1f

    /** Vestibular accessibility only; the flash clamp is unconditional inside the native safety pass. */
    @Volatile
    var reducedMotion: Boolean = false

    /**
     * Settings' "smear the Visual with a finger": whether a Style that does not draw the finger's
     * strokes itself (Water does) shows them as the shared ripple smear. Off by default, as in Settings.
     */
    @Volatile
    var touchSmear: Boolean = false

    /** How strongly the smear answers a stroke; Settings' smear strength. */
    @Volatile
    var touchSmearStrength: Float = 1f

    /**
     * Freeze reactions (#366): the Sound signals hold steady while the picture's own motion goes on.
     * Live only; the MilkDrop preset gets no new samples meanwhile, so it holds too.
     */
    @Volatile
    var reactionsHeld: Boolean = false

    /** The built-in style behind [transitionId], kept for the settings model; the renderer reads the id. */
    @Volatile
    var transitionStyle: TransitionStyle = TransitionStyle.FADE

    /**
     * How long a change of Style crossfades: [DEFAULT_TRANSITION], or Style switching's own transition
     * while it is on (#282).
     */
    @Volatile
    var transitionDuration: Millis = DEFAULT_TRANSITION

    /**
     * The look of a change of Style (#309): one of the No-flash safe looks the Settings picker offers,
     * or "random" for a draw from them seeded by [transitionSeed]. The engine draws the fade for any
     * other, and starts each change on the next downbeat while a tempo is heard.
     */
    @Volatile
    var transitionId: String = TransitionStyle.FADE.name.lowercase()

    /** The seed of the random look's draws: Style switching's own while it is on, so the draws repeat. */
    @Volatile
    var transitionSeed: Long = 0L

    @Volatile
    var onShaderError: (String?) -> Unit = {}

    // Assigned from Main and read on the GL thread in onDrawFrame.
    @Volatile
    var onMilkPresetLoaded: (String) -> Unit = {}

    /**
     * The engine heard the moment Style switching waits for (#282): called on the GL thread, at most
     * once a frame, each time the engine's count of due switches rises.
     */
    @Volatile
    var onStyleDue: () -> Unit = {}

    /**
     * The live sound: while set, the native renderer reads the features heard now and the stereo
     * window for the scenes straight from this feed at the start of each frame, on the GL thread, and
     * [features] is not sent. Null draws [features].
     */
    @Volatile
    var feed: VisualFeed? = null

    // GL thread only: the feed the native renderer is attached to now.
    private var attachedFeed: VisualFeed? = null

    /**
     * When each frame is expected on screen (#295): the pacer that asks for the frames. With a [feed],
     * the frame draws the audio heard at that moment, so the picture lands on the beat being heard
     * rather than one or two refreshes early. Null draws the audio heard as the frame starts.
     */
    @Volatile
    var presentation: FramePresentation? = null

    /**
     * The Customization snapshots each frame picks up (ADR-0004). While they are attached the engine
     * takes its parameters and Modulators from them, so [sceneParams] and the LFO and envelope
     * configs are not sent.
     */
    @Volatile
    var snapshots: NativeSnapshots? = null

    // GL thread only: the snapshots the native renderer is attached to now.
    private var attachedSnapshots: NativeSnapshots? = null

    /**
     * A Recording's encoder surface (#317): while set, every frame drawn to the window is also copied
     * into it, stamped with [System.nanoTime] when it was drawn. Set from any thread; null stops, and
     * the renderer marks the target released once it has let the surface go.
     */
    @Volatile
    var recording: RecordingTarget? = null

    // GL thread only: the encoder surface the frames are copied into now.
    private var recorder: FrameRecorder? = null

    /** The window's size in pixels as last drawn, 0 before it has one: a Recording's picture size. */
    @Volatile
    var surfaceWidth: Int = 0
        private set

    @Volatile
    var surfaceHeight: Int = 0
        private set

    // Config holders only: native ticks its own LfoEngine/AdsrEngine against the configs
    // syncNativeState forwards below (core/viz/RendererFrame.cpp).
    val lfoEngine = LfoEngine()

    val adsrEngine = AdsrEngine()

    private val appContext = context.applicationContext
    private val nativeViz = NativeViz(context)
    private var nativeParams: SceneParams? = null
    private var nativeLfo: List<LfoConfig>? = null
    private var nativeAdsr: List<AdsrConfig>? = null
    private var nativeThermalSampleMs = 0L

    // GL thread only: the performance-hint session that reports each frame's work (#286).
    private val frameHint = FrameWorkHint(context)

    // GL thread only: stepped once per drawn frame, so each live event reaches exactly one frame.
    private val displayEvents = DisplayEvents()

    // GL thread only: the engine's count of due Style switches after the last frame.
    private var stylesDue = 0

    // GL thread only: after displayEvents, so the events that fire during a hold are never replayed.
    private val signalHold = SignalHold()

    @Volatile
    private var lastMilkPreset: String? = null

    val milkdropAvailable: Boolean
        get() {
            nativeViz.create()
            return nativeViz.knows(SceneIds.MILKDROP)
        }

    fun availableSceneIds(): List<SceneId> {
        nativeViz.create()
        return nativeViz.sceneIds()
    }

    /** Frees the native renderer; only when this renderer is being discarded, on the GL thread. */
    fun releaseScenes() {
        recorder?.release()
        recorder = null
        frameHint.close()
        nativeViz.destroy()
    }

    fun loadMilkPreset(path: String) {
        lastMilkPreset = path
        nativeViz.create()
        nativeViz.loadMilkPreset(path)
    }

    fun reloadCurrentMilkPreset() = nativeViz.reloadMilkPreset()

    fun warmTransition(id: String) = nativeViz.warmTransition(id)

    fun queueTouchStroke(
        nx: Float,
        ny: Float,
        ndx: Float,
        ndy: Float,
        dt: Float,
        strength: Float,
    ) = nativeViz.queueTouchStroke(nx, ny, ndx, ndy, dt, strength)

    /** The pointers that are down right now, in y-up NDC; `n = 0` says every finger has lifted. */
    fun submitTouchPoints(
        xy: FloatArray,
        n: Int,
    ) = nativeViz.submitTouchPoints(xy, n)

    /**
     * The phone's down in screen space (x right, y up, z out of the screen), any length, latest-wins;
     * `0, 0, 0` says there is no reading. [GravitySource] calls it; the engine smooths it.
     */
    fun submitGravity(
        x: Float,
        y: Float,
        z: Float,
    ) = nativeViz.submitGravity(x, y, z)

    fun beginParamMorph(seconds: Float) {
        if (seconds <= 0f) return
        nativeViz.beginParamMorph(seconds)
    }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        ThermalGovernor.attach(appContext)
        frameHint.open(pacedFps())
        nativeViz.surfaceCreated()
        styleLab?.attach(appContext, nativeViz)
        attachedSnapshots = null
        attachedFeed = null
        // A new context: the encoder surface went with the old one, and is made again on this one.
        recorder?.forget()
        nativeParams = null
        nativeLfo = null
        nativeAdsr = null
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width
        surfaceHeight = height
        nativeViz.surfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val workStart = System.nanoTime()
        nativeViz.setScene(requestedSceneId)
        syncNativeState()
        nativeViz.setPresentTime(presentation?.expectedPresentNanos() ?: 0L)
        nativeViz.render(SystemClock.elapsedRealtimeNanos() / NANOS_PER_SECOND, targetFbo = 0)
        record()
        nativeViz.pollError(onShaderError)
        nativeViz.takeMilkPresetLoaded()?.let { path ->
            lastMilkPreset = path
            onMilkPresetLoaded(path)
        }
        // A drop is a fresh start (a new plan, or none): only a rise is a switch.
        val due = nativeViz.stylesDue()
        if (due > stylesDue) onStyleDue()
        stylesDue = due
        frameHint.report(System.nanoTime() - workStart)
    }

    /** Copies the frame just drawn into the Recording's encoder surface, while one is set (#317). */
    private fun record() {
        val wanted = recording
        val current = recorder
        if (current != null && current.target !== wanted) {
            current.release()
            recorder = null
        }
        if (wanted == null || wanted.failed) return
        val into = recorder ?: FrameRecorder(wanted).also { recorder = it }
        if (into.draw(System.nanoTime())) {
            wanted.framesDrawn++
        } else {
            wanted.failed = true
            into.release()
            recorder = null
        }
    }

    /** The rate the pacer actually draws at: its own cap held to the thermal frame cap. */
    private fun pacedFps(): Float = FrameCap.effectiveFps(ThermalGovernor.pacedFps, ThermalGovernor.frameCapFps)

    private fun syncNativeState() {
        val wanted = snapshots
        if (wanted !== attachedSnapshots) {
            nativeViz.attachSnapshots(wanted)
            attachedSnapshots = wanted
            nativeParams = null
            nativeLfo = null
            nativeAdsr = null
        }
        if (wanted == null) syncSceneParams()
        val live = feed
        if (live !== attachedFeed) {
            nativeViz.attachFeed(live)
            attachedFeed = live
        }
        val held = reactionsHeld
        signalHold.held = held
        if (live == null) {
            nativeViz.setFeatures(signalHold.frame(displayEvents.frame(features)))
        } else {
            // The feed's frames are held natively, with the audio window MilkDrop is fed, so it holds too.
            nativeViz.setReactionsHeld(held)
        }
        nativeViz.setReducedMotion(reducedMotion)
        nativeViz.setTouchSmear(touchSmear, touchSmearStrength)
        nativeViz.setTransition(transitionId, transitionDuration, transitionSeed)
        // The rate the pacer actually draws at, the thermal frame cap included, so the load scale and
        // the hint session judge each frame against the time it really has.
        val paced = pacedFps()
        nativeViz.setPacedFps(paced)
        frameHint.retarget(paced)
        nativeViz.setQualityCeiling(qualityCeiling)
        val now = SystemClock.elapsedRealtime()
        if (now - nativeThermalSampleMs >= THERMAL_SAMPLE_MS) {
            nativeThermalSampleMs = now
            nativeViz.setThermal(ThermalGovernor.platformStatus, ThermalGovernor.thermalHeadroom())
            nativeViz.setFrameLoad(FramePacer.activeStats())
            ThermalGovernor.frameCapFps = nativeViz.frameCapFps()
        }
    }

    private fun syncSceneParams() {
        val p = sceneParams
        if (p !== nativeParams) {
            nativeViz.setParams(p)
            nativeParams = p
        }
        val lfo = lfoEngine.configs
        if (lfo !== nativeLfo) {
            nativeViz.setLfoConfigs(lfo)
            nativeLfo = lfo
        }
        val adsr = adsrEngine.configs
        if (adsr !== nativeAdsr) {
            nativeViz.setAdsrConfigs(adsr)
            nativeAdsr = adsr
        }
    }

    fun exportSceneFactory(sceneId: SceneId): SceneFactory {
        val id = sceneId
        val preset = lastMilkPreset
        return object : SceneFactory {
            override val sceneId: SceneId = id

            override val milkPresetPath: String? = preset
        }
    }
}

private const val THERMAL_SAMPLE_MS = 1000L
private const val NANOS_PER_SECOND = 1_000_000_000.0
