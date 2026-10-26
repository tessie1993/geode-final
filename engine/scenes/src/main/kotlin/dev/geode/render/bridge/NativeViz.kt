package dev.geode.render.bridge

import android.content.Context
import android.content.res.AssetManager
import dev.geode.analysis.AudioFeatures
import dev.geode.engine.bridge.FeatureFrameLayout
import dev.geode.engine.bridge.GeodeNative
import dev.geode.engine.bridge.NativeFailures
import dev.geode.engine.bridge.NativeSnapshots
import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.render.AdsrConfig
import dev.geode.render.AdsrEngine
import dev.geode.render.FrameStats
import dev.geode.render.LfoConfig
import dev.geode.render.LfoEngine
import dev.geode.render.scene.SceneParams
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Owns one `geode_viz` handle: the native renderer behind a GL surface or an offscreen target.
 *
 * Every call is a no-op until [create] (or [surfaceCreated]) has run; the GL-thread rules of the
 * C API apply unchanged.
 */
class NativeViz(
    context: Context,
) {
    // Kept referenced for as long as the native AAssetManager resolved from it may be used.
    private val assets: AssetManager = context.assets
    private val cacheDir: String = context.cacheDir.absolutePath
    private val milkTextureDir: String = File(context.filesDir, "milk/textures").absolutePath

    // Written on the GL thread (surfaceCreated) and read from the main and GL threads. Every call uses
    // the handle under the read lock and create()/destroy() swap it under the write lock, so destroy()
    // waits for a call in flight instead of freeing the renderer under it, and two threads racing on a
    // 0L handle cannot both create (and leak) a renderer.
    @Volatile
    private var handle = 0L
    private val lock = ReentrantReadWriteLock()
    private val paramFrame = FloatArray(SceneParamsCodec.FIELDS)
    private val featureFrame = FloatArray(FeatureFrameLayout.FLOATS)
    private var reportedError = ""

    val isCreated: Boolean get() = handle != 0L

    fun create(): Boolean =
        lock.write {
            if (handle == 0L) {
                verifyLayouts()
                val created = GeodeNative.vizCreate(assets, cacheDir)
                if (created != 0L) GeodeNative.vizSetMilkTextureDir(created, milkTextureDir)
                handle = created
            }
            handle != 0L
        }

    fun destroy() {
        lock.write {
            if (handle == 0L) return
            val gone = handle
            handle = 0L
            GeodeNative.vizDestroy(gone)
        }
    }

    private inline fun live(block: (Long) -> Unit) {
        lock.read {
            val h = handle
            if (h != 0L) block(h)
        }
    }

    private inline fun <T> liveOr(
        otherwise: T,
        block: (Long) -> T,
    ): T =
        lock.read {
            val h = handle
            if (h == 0L) otherwise else block(h)
        }

    /** Runs [block] with the live native handle under the read lock, or not at all without one (the debug Style lab). */
    internal fun withHandle(block: (Long) -> Unit) = live(block)

    fun knows(sceneId: SceneId): Boolean = liveOr(false) { h -> GeodeNative.vizKnows(h, sceneId.value) }

    fun sceneIds(): List<SceneId> {
        val ids = liveOr("") { h -> GeodeNative.vizSceneIds(h) }
        return ids
            .split('\n')
            .filter { it.isNotEmpty() }
            .map(::SceneId)
    }

    fun setParams(p: SceneParams) {
        live { h ->
            SceneParamsCodec.pack(p, paramFrame)
            GeodeNative.vizSetParams(h, paramFrame)
        }
    }

    fun setFeatures(f: AudioFeatures) {
        live { h ->
            FeatureFrameCodec.pack(f, featureFrame)
            GeodeNative.vizSetFeatures(h, featureFrame)
        }
    }

    fun setReducedMotion(on: Boolean) {
        live { h -> GeodeNative.vizSetReducedMotion(h, on) }
    }

    fun setTransition(
        id: String,
        duration: Millis,
    ) {
        live { h -> GeodeNative.vizSetTransition(h, id, duration.value) }
    }

    fun beginParamMorph(seconds: Float) {
        live { h -> GeodeNative.vizBeginParamMorph(h, seconds) }
    }

    fun submitTouchPoints(
        xy: FloatArray,
        n: Int,
    ) {
        live { h -> GeodeNative.vizSetTouch(h, xy, n) }
    }

    fun queueTouchStroke(
        nx: Float,
        ny: Float,
        ndx: Float,
        ndy: Float,
        dt: Float,
        strength: Float,
    ) {
        live { h -> GeodeNative.vizQueueTouchStroke(h, nx, ny, ndx, ndy, dt, strength) }
    }

    fun loadMilkPreset(path: String) {
        live { h -> GeodeNative.vizLoadMilkPreset(h, path) }
    }

    fun reloadMilkPreset() {
        live { h -> GeodeNative.vizReloadMilkPreset(h) }
    }

    /** The preset MilkDrop last compiled, handed out once; null until the next one. */
    fun takeMilkPresetLoaded(): String? = liveOr(null) { h -> GeodeNative.vizTakeMilkPresetLoaded(h) }

    fun pushPcm(
        mono: FloatArray,
        count: Int,
    ) {
        if (count > 0) live { h -> GeodeNative.vizPushPcm(h, mono, count) }
    }

    fun setLfoConfigs(configs: List<LfoConfig>) {
        live { h ->
            configs.take(LfoEngine.SLOTS).forEachIndexed { slot, c -> GeodeNative.vizSetLfo(h, slot, ModConfigCodec.packLfo(c)) }
        }
    }

    fun setAdsrConfigs(configs: List<AdsrConfig>) {
        live { h ->
            configs.take(AdsrEngine.COUNT).forEachIndexed { slot, c -> GeodeNative.vizSetAdsr(h, slot, ModConfigCodec.packAdsr(c)) }
        }
    }

    /**
     * From the next frame on, the renderer picks up each Customization [snapshots] receives at the
     * start of the frame; null detaches.
     */
    fun attachSnapshots(snapshots: NativeSnapshots?) {
        live { h -> NativeFailures.check(GeodeNative.vizAttachSnapshots(h, snapshots?.handle ?: 0L), "vizAttachSnapshots") }
    }

    fun setThermal(
        platformStatus: Int,
        headroom: Float,
    ) {
        live { h -> GeodeNative.vizSetThermal(h, platformStatus, headroom) }
    }

    fun setPacedFps(fps: Float) {
        live { h -> GeodeNative.vizSetPacedFps(h, fps) }
    }

    /** The frame rate the hottest thermal tiers ask the pacer to hold to, or 0 for no cap. */
    fun frameCapFps(): Float = liveOr(0f) { h -> GeodeNative.vizFrameCapFps(h) }

    /** The Visual quality on screen as a render scale ceiling; the thermal ceiling can only lower it. */
    fun setQualityCeiling(ceiling: Float) {
        if (handle != 0L) GeodeNative.vizSetQualityCeiling(handle, ceiling)
    }

    /** The frame pacer's latest window, which the scene and trail buffers scale with. */
    fun setFrameLoad(stats: FrameStats) {
        live { h -> GeodeNative.vizSetFrameLoad(h, stats.frames, stats.p95Ms) }
    }

    fun setOffscreen(on: Boolean) {
        live { h -> GeodeNative.vizSetOffscreen(h, on) }
    }

    fun surfaceCreated() {
        if (create()) live { h -> GeodeNative.vizSurfaceCreated(h) }
    }

    fun surfaceChanged(
        width: Int,
        height: Int,
    ) {
        live { h -> GeodeNative.vizSurfaceChanged(h, width, height) }
    }

    fun setScene(sceneId: SceneId): Boolean = liveOr(false) { h -> GeodeNative.vizSetScene(h, sceneId.value) }

    fun warmTransition(id: String) {
        live { h -> GeodeNative.vizWarmTransition(h, id) }
    }

    fun cut() {
        live { h -> GeodeNative.vizCut(h) }
    }

    fun render(
        timeSeconds: Double,
        targetFbo: Int,
    ) {
        live { h -> GeodeNative.vizRender(h, timeSeconds, targetFbo) }
    }

    fun releaseScenes() {
        live { h -> GeodeNative.vizReleaseScenes(h) }
    }

    /** Full-frame RGBA8 overlay drawn last, premultiplied alpha; null clears. Any thread; latched for the next frame. */
    fun setOverlay(
        pixels: IntArray?,
        width: Int,
        height: Int,
    ) {
        live { h -> GeodeNative.vizSetOverlay(h, pixels, width, height) }
    }

    /**
     * Full-frame RGBA8 underlay blended UNDER/INTO the scene: blend 0 = replace scene where scene is
     * black (screen), 1 = multiply, 2 = add; amount 0..1. null clears. Any thread; latched.
     */
    fun setUnderlay(
        pixels: IntArray?,
        width: Int,
        height: Int,
        blend: Int,
        amount: Float,
    ) {
        live { h -> GeodeNative.vizSetUnderlay(h, pixels, width, height, blend, amount) }
    }

    /** Reports the native error state once per change: a message, or null when it clears. */
    fun pollError(onChange: (String?) -> Unit) {
        val error = liveOr<String?>(null) { h -> GeodeNative.vizLastError(h) } ?: return
        if (error == reportedError) return
        reportedError = error
        onChange(error.ifEmpty { null })
    }

    private companion object {
        @Volatile
        private var verified = false

        fun verifyLayouts() {
            if (verified) return
            SceneParamsCodec.verify(GeodeNative.vizParamNames())
            check(GeodeNative.featureFrameFloats() == FeatureFrameLayout.FLOATS) {
                "GeodeFeatureFrame has ${GeodeNative.featureFrameFloats()} floats, FeatureFrameLayout expects ${FeatureFrameLayout.FLOATS}"
            }
            verified = true
        }
    }
}
