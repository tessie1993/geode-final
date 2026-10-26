package dev.geode.render.offscreen

import android.content.Context
import android.opengl.GLES30
import dev.geode.analysis.FeatureTimeline
import dev.geode.engine.bridge.CustomizationSnapshot
import dev.geode.engine.bridge.NativeSnapshots
import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.render.SceneFactory
import dev.geode.render.TransitionStyle
import dev.geode.render.bridge.NativeViz
import dev.geode.render.scene.SceneIds
import dev.geode.util.bestEffort

/**
 * Everything needed to render one offscreen sequence: the frame grid, and the look it is drawn with.
 *
 * [look] is the Render's frozen Customization as the engine reads it: the same snapshot the live
 * Visual is handed for that Customization (ADR-0002, ADR-0004). The engine evaluates its plan exactly
 * as it does live, so a Render draws the Style's defaults, the Shared core, the Camera, the Extras,
 * the Macros, the Reactions, the Modulators and the Automations, and whatever the shared renderer
 * learns to draw reaches Renders with it (#337).
 *
 * Frames sit on the track's own grid: frame g is the moment g / [fps] seconds into the track, and the
 * sequence is [totalFrames] of them from [firstFrame]. A sequence that starts past the track's start is
 * pre-rolled ([OffscreenSceneRenderer.preRoll]) so it opens warm, as the same frame of a whole-track
 * Render does (#344). Its scene draws at the frame size times [supersample] (1..2, #345), which a Render
 * can afford because it is not bound to real time; the composite brings it down to the frame.
 */
data class OffscreenRenderSpec(
    val width: Int,
    val height: Int,
    val fps: Int,
    val totalFrames: Int,
    val firstFrame: Long,
    val look: CustomizationSnapshot,
    val supersample: Float = 1f,
) {
    companion object {
        /** The frame of the track's grid at [fps] nearest [startMs]: where a sequence from there starts. */
        fun firstFrameAt(
            startMs: Long,
            fps: Int,
        ): Long = if (startMs <= 0 || fps <= 0) 0L else (startMs * fps + MILLIS_PER_SECOND / 2) / MILLIS_PER_SECOND

        /** Where frame [frame] of the grid at [fps] falls, in whole milliseconds, rounded down. */
        fun frameMs(
            frame: Long,
            fps: Int,
        ): Long = Math.floorDiv(frame * MILLIS_PER_SECOND, fps.toLong())

        private const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * One frame's sound (#342): [frames] stereo frames ending at the moment the frame shows, the newest
 * [fresh] of them heard since the frame before, as the live feed hands them over.
 */
data class PcmSpan(
    val frames: Int,
    val fresh: Int,
) {
    companion object {
        val NONE: PcmSpan = PcmSpan(0, 0)
    }
}

/**
 * The sound a Render hands its scenes each frame (#342): the track up to [timeUs], interleaved stereo,
 * into [out]: the window every scene reads, and before it whatever was heard since the last frame, so
 * MilkDrop misses no beat between frames. Asked in the order the frames are drawn.
 */
fun interface FramePcm {
    fun spanAt(
        timeUs: Long,
        out: FloatArray,
    ): PcmSpan
}

/** A change of Style a Render makes (#343): to [sceneId], drawn with [look], crossfading over [transition]. */
class RenderSwitch(
    val sceneId: SceneId,
    val look: CustomizationSnapshot,
    val transition: Millis,
)

/**
 * A Render's Style switching (#343): asked each time the engine says the look's switching is due, it
 * gives the change of Style to make, or null to stay. Asked on the Render's own thread, in frame order.
 */
fun interface StyleSwitches {
    fun due(): RenderSwitch?
}

/**
 * Draws an analysed track to a GL target, one frame at a time, through its own native renderer.
 *
 * Callers own the EGL context, the surface and whatever they do with the rendered frames; the
 * scene, its simulations and the Customization's evaluation live inside `libgeode.so`. The renderer
 * has its own snapshot exchange, separate from the live Visual's (ADR-0004: a Render is its own
 * engine instance), and hands it the Render's look once. With [pcm], every frame also hands the
 * scenes the real sound heard at that moment (#342), as the live Visual's feed does, so projectM, the
 * shader PCM row and the simulations' strikes react to it. With [switches], the look's Style switching
 * runs as it does live (#343): each time the engine's count of due switches rises, the Render changes
 * Style, crossfading over the switching's transition, and hands the engine the switched look. Not
 * thread-safe, and every call must happen on the thread holding the GL context.
 */
class OffscreenSceneRenderer(
    private val context: Context,
    private val sceneFactory: SceneFactory,
    private val timeline: FeatureTimeline,
    private val spec: OffscreenRenderSpec,
    private val pcm: FramePcm? = null,
    private val switches: StyleSwitches? = null,
) {
    private var native: NativeViz? = null
    private var snapshots: NativeSnapshots? = null

    // The engine's count of due Style switches after the last frame, and the revision of the look last handed over.
    private var stylesDue = 0
    private var revision = 0
    private val span = FloatArray(PCM_SPAN_FRAMES * 2)

    /** Builds the native renderer, its scene and its look; must run with the target GL context current. */
    fun prepare() {
        val viz = NativeViz(context)
        check(viz.create()) { "native renderer could not be created" }
        // Owned from here on, so release() frees it even if a check below throws.
        native = viz
        // Pinned to the full tier: an export renders as fast as the encoder takes frames, so its
        // wall-clock frame time says nothing about the device. MilkDrop runs on the render clock with
        // the Render seed in this mode, so the same Render gives the same frames.
        viz.setOffscreen(true)
        viz.setExportSupersample(spec.supersample)
        viz.surfaceCreated()
        viz.surfaceChanged(spec.width, spec.height)
        check(viz.setScene(sceneFactory.sceneId)) { "native renderer cannot draw \"${sceneFactory.sceneId}\"" }
        sceneFactory.milkPresetPath?.let { viz.loadMilkPreset(it) }
        viz.setTransition(TransitionStyle.CUT.name.lowercase(), Millis.ZERO)
        val exchange = NativeSnapshots().also { snapshots = it }
        check(exchange.handle != 0L) { "the engine could not make a Customization exchange for the Render" }
        val refused = exchange.offer(spec.look)
        check(refused == null) { "the engine refused the Render's Customization: $refused" }
        // The first frame picks the look up before it draws, as the live Visual's frames do.
        viz.attachSnapshots(exchange)
    }

    /**
     * Renders frame [frame] of the sequence into [targetFbo] (0 = the default framebuffer): frame
     * [OffscreenRenderSpec.firstFrame] + [frame] of the track's grid. A negative [frame] is one before
     * the sequence, as [preRoll] draws them.
     *
     * [prepare] must have run first. The caller presents the result — swapping an encoder
     * surface, reading pixels back, whatever it needs.
     */
    fun renderFrame(
        frame: Int,
        targetFbo: Int = 0,
    ) {
        val viz = checkNotNull(native) { "renderFrame() before prepare()" }
        val g = spec.firstFrame + frame
        // Each frame shows the events heard within half a frame of it (#302): the windows of
        // consecutive frames share their edges, so every event reaches exactly one frame.
        val features = timeline.featuresAround(frameMs(g), halfwayBeforeMs(g), halfwayBeforeMs(g + 1))
        viz.setFeatures(features)
        pcm?.let { sound ->
            val heard = sound.spanAt(g * MICROS_PER_SECOND / spec.fps, span)
            if (heard.frames > 0) viz.setFramePcm(span, heard.frames, heard.fresh)
        }
        // The render clock is exact: frame g is drawn g / fps seconds into the track, so every step is
        // one frame long, where whole milliseconds would alternate 16 and 17 ms at 60 fps, and a ranged
        // sequence keeps the clock a whole-track Render has at the same frame.
        viz.render(g.toDouble() / spec.fps, targetFbo)
        switches?.let { switching ->
            // A drop is a fresh start (a new plan): only a rise is a switch, as live.
            val due = viz.stylesDue()
            if (due > stylesDue) switching.due()?.let { switchTo(viz, it) }
            stylesDue = due
        }
    }

    /** Crossfades into [switch]'s Style from the next frame on, with its look handed to the engine first. */
    private fun switchTo(
        viz: NativeViz,
        switch: RenderSwitch,
    ) {
        val exchange = snapshots ?: return
        revision++
        val refused = exchange.offer(switch.look.copy(revision = revision))
        if (refused != null) return
        viz.setTransition(TransitionStyle.FADE.name.lowercase(), switch.transition)
        viz.setScene(switch.sceneId)
        // The MilkDrop preset waits for the MilkDrop scene: a switch into it loads the Render's preset.
        if (switch.sceneId == SceneIds.MILKDROP) sceneFactory.milkPresetPath?.let { viz.loadMilkPreset(it) }
    }

    /**
     * Draws every frame of the track before the sequence, without presenting any (#344): simulations,
     * trails, Reaction followers, Modulators and the Automations' beat clock then stand at the first
     * frame exactly as they do in a whole-track Render, given the same seeds (#339). [afterFrame] runs
     * after each, with how many are done of how many, so the caller can pace and report; it stops early
     * once [isCancelled].
     */
    fun preRoll(
        isCancelled: () -> Boolean,
        afterFrame: (done: Long, of: Long) -> Unit = { _, _ -> },
    ) {
        val count = spec.firstFrame
        var done = 0L
        while (done < count && !isCancelled()) {
            renderFrame((done - count).toInt())
            // Nothing is swapped, so the driver is told to get on with each frame rather than pile them up.
            GLES30.glFlush()
            done++
            afterFrame(done, count)
        }
    }

    /** Frees the native renderer and its exchange. Safe to call more than once. */
    fun release() {
        // Detached before the exchange closes, as NativeSnapshots asks.
        bestEffort(TAG, "native.attachSnapshots(null)") { native?.attachSnapshots(null) }
        bestEffort(TAG, "native.destroy()") { native?.destroy() }
        native = null
        bestEffort(TAG, "snapshots.close()") { snapshots?.close() }
        snapshots = null
    }

    private fun frameMs(frame: Long): Long = OffscreenRenderSpec.frameMs(frame, spec.fps)

    /** The moment half a frame before frame [frame]: (2g − 1) / (2 fps) seconds. */
    private fun halfwayBeforeMs(frame: Long): Long = Math.floorDiv((2L * frame - 1) * MILLIS_PER_SECOND, 2L * spec.fps)

    private companion object {
        const val TAG = "OffscreenSceneRenderer"
        const val MILLIS_PER_SECOND = 1_000L
        const val MICROS_PER_SECOND = 1_000_000L

        /** The longest span of sound a frame hands the scenes: the engine's GEODE_FEED_SPAN_FRAMES. */
        const val PCM_SPAN_FRAMES = 4096
    }
}
