package dev.geode.visuals

import android.app.job.JobParameters
import android.app.job.JobService
import android.opengl.GLES30
import dev.geode.RingLog
import dev.geode.engine.types.Millis
import dev.geode.render.TransitionStyle
import dev.geode.render.bridge.NativeViz
import dev.geode.render.scene.SceneIds
import java.io.File

/**
 * The Style warm-up (#304, [StyleWarmUp]): while the phone is idle and charging, a renderer of its own
 * on an offscreen context draws every Style for a couple of frames and loads every MilkDrop preset, which
 * fills the engine's binary cache, and then lets go of everything. A warm-up already done for this
 * install, driver and set of presets returns at once. Stopped by the system, it is asked for again.
 */
class StyleWarmUpService : JobService() {
    @Volatile
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        Thread({
            val done = runCatching { warmUp() }.onFailure { RingLog.note(TAG, "warm-up failed", it) }.getOrDefault(true)
            if (!stopped) jobFinished(params, !done)
        }, TAG).start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true
    }

    /** True once every Style is warm (or already was); false when stopped part way. */
    private fun warmUp(): Boolean =
        OffscreenGl().use {
            val driver = DRIVER_STRINGS.joinToString("/") { GLES30.glGetString(it) ?: "" }
            val presets =
                StyleTargets
                    .milkFolder(this)
                    .listFiles { f -> f.isFile && f.extension == MILK }
                    .orEmpty()
                    .sortedBy { it.name }
            val install = File(applicationInfo.sourceDir).lastModified().toString()
            val stamp = StyleWarmUp.stamp(install, driver, presets.map { it.name })
            if (!StyleWarmUp.due(StyleWarmUp.storedStamp(this), stamp)) return@use true
            val viz = NativeViz(this)
            try {
                val warm = viz.create() && warmStyles(viz, presets)
                if (warm) StyleWarmUp.storeStamp(this, stamp)
                warm
            } finally {
                viz.destroy()
            }
        }

    private fun warmStyles(
        viz: NativeViz,
        presets: List<File>,
    ): Boolean {
        viz.setOffscreen(true)
        viz.surfaceCreated()
        viz.surfaceChanged(SIZE, SIZE)
        viz.setTransition(TransitionStyle.CUT.name.lowercase(), Millis.ZERO)
        var time = 0.0
        val draw = {
            repeat(FRAMES) {
                time += FRAME_SECONDS
                viz.render(time, targetFbo = 0)
            }
        }
        for (style in StyleWarmUp.order(viz.sceneIds(), SceneIds.MILKDROP)) {
            if (stopped) return false
            if (!viz.setScene(style)) continue
            if (style != SceneIds.MILKDROP) {
                draw()
            } else if (!warmPresets(viz, presets, draw)) {
                return false
            }
            // One Style built at a time: what is warm is the cache, not the scenes.
            viz.releaseScenes()
        }
        return true
    }

    /** Draws each preset in turn into the MilkDrop scene; false when the job was stopped part way. */
    private fun warmPresets(
        viz: NativeViz,
        presets: List<File>,
        draw: () -> Unit,
    ): Boolean {
        for (preset in presets) {
            if (stopped) return false
            viz.loadMilkPreset(preset.path)
            draw()
        }
        return true
    }

    private companion object {
        const val TAG = "StyleWarmUp"
        const val MILK = "milk"
        const val SIZE = 64
        const val FRAMES = 2
        const val FRAME_SECONDS = 1.0 / 30.0
        val DRIVER_STRINGS = intArrayOf(GLES30.GL_VENDOR, GLES30.GL_RENDERER, GLES30.GL_VERSION)
    }
}
