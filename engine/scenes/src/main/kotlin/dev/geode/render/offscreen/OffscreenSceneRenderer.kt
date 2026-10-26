package dev.geode.render.offscreen

import android.content.Context
import dev.geode.analysis.FeatureTimeline
import dev.geode.engine.bridge.CustomizationSnapshot
import dev.geode.engine.bridge.NativeSnapshots
import dev.geode.engine.types.Millis
import dev.geode.render.SceneFactory
import dev.geode.render.TransitionStyle
import dev.geode.render.bridge.NativeViz
import dev.geode.util.bestEffort

/**
 * Everything needed to render one offscreen sequence: the frame grid, and the look it is drawn with.
 *
 * [look] is the Render's frozen Customization as the engine reads it: the same snapshot the live
 * Visual is handed for that Customization (ADR-0002, ADR-0004). The engine evaluates its plan exactly
 * as it does live, so a Render draws the Style's defaults, the Shared core, the Camera, the Extras,
 * the Macros, the Reactions, the Modulators and the Automations, and whatever the shared renderer
 * learns to draw reaches Renders with it (#337).
 */
data class OffscreenRenderSpec(
    val width: Int,
    val height: Int,
    val fps: Int,
    val totalFrames: Int,
    val rangeStartMs: Long,
    val look: CustomizationSnapshot,
)

/**
 * The sound a Render hands its scenes each frame (#342): the window of the track, interleaved stereo,
 * that ends at [timeUs]. Fills [out] and returns how many stereo frames it wrote (0 when there is none).
 * Asked in the order the frames are drawn.
 */
fun interface FramePcm {
    fun windowAt(
        timeUs: Long,
        out: FloatArray,
    ): Int
}

/**
 * Draws an analysed track to a GL target, one frame at a time, through its own native renderer.
 *
 * Callers own the EGL context, the surface and whatever they do with the rendered frames; the
 * scene, its simulations and the Customization's evaluation live inside `libgeode.so`. The renderer
 * has its own snapshot exchange, separate from the live Visual's (ADR-0004: a Render is its own
 * engine instance), and hands it the Render's look once. With [pcm], every frame also hands the
 * scenes the real sound heard at that moment (#342), as the live Visual's feed does, so projectM, the
 * shader PCM row and the simulations' strikes react to it. Not thread-safe, and every call must happen
 * on the thread holding the GL context.
 */
class OffscreenSceneRenderer(
    private val context: Context,
    private val sceneFactory: SceneFactory,
    private val timeline: FeatureTimeline,
    private val spec: OffscreenRenderSpec,
    private val pcm: FramePcm? = null,
) {
    private var native: NativeViz? = null
    private var snapshots: NativeSnapshots? = null
    private val window = FloatArray(PCM_WINDOW_FRAMES * 2)

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
     * Renders frame [frame] of the sequence into [targetFbo] (0 = the default framebuffer).
     *
     * [prepare] must have run first. The caller presents the result — swapping an encoder
     * surface, reading pixels back, whatever it needs.
     */
    fun renderFrame(
        frame: Int,
        targetFbo: Int = 0,
    ) {
        val viz = checkNotNull(native) { "renderFrame() before prepare()" }
        // Each frame shows the events heard within half a frame of it (#302): the windows of
        // consecutive frames share their edges, so every event reaches exactly one frame.
        val start = spec.rangeStartMs
        val features =
            timeline.featuresAround(start + frameStartMs(frame), start + halfwayBeforeMs(frame), start + halfwayBeforeMs(frame + 1))
        viz.setFeatures(features)
        pcm?.let { sound ->
            val frames = sound.windowAt(start * MICROS_PER_MILLI + frame * MICROS_PER_SECOND / spec.fps, window)
            if (frames > 0) viz.setFramePcm(window, frames)
        }
        // The render clock is exact: frame k is drawn k / fps seconds in, so every step is one frame
        // long, where whole milliseconds would alternate 16 and 17 ms at 60 fps.
        viz.render(frame.toDouble() / spec.fps, targetFbo)
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

    private fun frameStartMs(frame: Int): Long = frame * MILLIS_PER_SECOND / spec.fps

    /** The moment half a frame before frame [frame]: (2k − 1) / (2 fps) seconds. */
    private fun halfwayBeforeMs(frame: Int): Long = Math.floorDiv((2L * frame - 1) * MILLIS_PER_SECOND, 2L * spec.fps)

    private companion object {
        const val TAG = "OffscreenSceneRenderer"
        const val MILLIS_PER_SECOND = 1_000L
        const val MICROS_PER_MILLI = 1_000L
        const val MICROS_PER_SECOND = 1_000_000L

        /** The stereo window each frame hands the scenes: the engine's GEODE_FEED_WINDOW_FRAMES. */
        const val PCM_WINDOW_FRAMES = 576
    }
}
