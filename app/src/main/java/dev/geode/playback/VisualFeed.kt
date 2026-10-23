package dev.geode.playback

import android.content.Context
import dev.geode.analysis.AudioFeatures
import dev.geode.audio.AudioBus
import dev.geode.render.VisualizerRenderer
import dev.geode.render.scene.PcmChunk

/**
 * The sound the live Visual reacts to, read by the GL thread straight from the playback engine: the
 * analysis's latest features and the PCM written since the last frame. Nothing passes through the
 * main thread, so the Visual keeps reacting while the UI recomposes or is busy, and nothing is
 * smoothed or predicted on the way.
 *
 * [attach] holds the engine and keeps its analysis running for as long as the renderer is fed;
 * [detach] lets both go.
 */
class VisualFeed(
    context: Context,
) {
    private val app = context.applicationContext
    private var session: PlaybackSession? = null

    // GL thread only: the PCM copied so far, and where the next copy starts.
    private val scratch = FloatArray(PCM_FLOATS)
    private var cursor = 0L

    /** Feeds [renderer] from the engine from its next frame on. Main thread. */
    fun attach(renderer: VisualizerRenderer) {
        val engine =
            session ?: PlaybackEngine.acquireForUi(app).also {
                session = it
                AudioBus.addConsumer()
            }
        cursor = engine.ring.currentWriteIndex()
        renderer.featuresProvider = { features(engine) }
        renderer.pcmProvider = { latestPcm(engine) }
    }

    /** Stops feeding [renderer] and lets the engine go. Main thread. */
    fun detach(renderer: VisualizerRenderer) {
        renderer.featuresProvider = null
        renderer.pcmProvider = { null }
        if (session == null) return
        session = null
        AudioBus.removeConsumer()
        PlaybackEngine.releaseUi()
    }

    private fun features(engine: PlaybackSession): AudioFeatures = engine.analysis.features.value

    private fun latestPcm(engine: PlaybackSession): PcmChunk? {
        val n = engine.ring.copyNewSince(cursor, scratch)
        cursor = engine.ring.lastCopyEndIndex
        return if (n > 0) PcmChunk(scratch, n) else null
    }

    private companion object {
        const val PCM_FLOATS = 4096
    }
}
