package dev.geode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.RingLog
import dev.geode.audio.AudioBus
import dev.geode.audio.PcmRingBuffer
import dev.geode.engine.bridge.NativeSnapshots
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.settings.api.BehaviorSettings
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.playback.PlaybackEngine
import dev.geode.render.VisualizerView
import dev.geode.render.scene.PcmChunk

/**
 * Binds the one live Visual to its sources (#17). Customization reaches it through the plan alone:
 * the renderer is attached to the [snapshots] exchange, which Customization's store publishes to
 * (ADR-0002, ADR-0004). The sound never passes through the main thread: each frame the renderer reads
 * the analysis' latest features from the [AudioBus] and the newest samples from the engine's ring, on
 * its own thread. Only the Style to draw and the Visual behaviours from Settings (reduce motion, the
 * finger smear) are handed over from here, when they change.
 */
@Composable
fun LiveVisualBindings(
    view: VisualizerView,
    customization: CustomizationFeature,
    snapshots: NativeSnapshots,
    settings: Settings,
) {
    val context = LocalContext.current
    DisposableEffect(view, snapshots) {
        val renderer = view.visualizerRenderer
        val session = PlaybackEngine.acquireForUi(context)
        renderer.snapshots = snapshots
        renderer.featureSource = AudioBus::features
        renderer.pcmProvider = LivePcm(session.ring)::next
        renderer.onShaderError = { error -> error?.let { RingLog.note(TAG, "shader: $it") } }
        // While the Visual is bound, the analysis runs.
        AudioBus.addConsumer()
        onDispose {
            AudioBus.removeConsumer()
            renderer.pcmProvider = { null }
            renderer.featureSource = { null }
            PlaybackEngine.releaseUi()
        }
    }
    val state by customization.state.collectAsStateWithLifecycle()
    val style = state.current.style
    LaunchedEffect(style) { view.visualizerRenderer.requestedSceneId = style }
    val values by settings.state.collectAsStateWithLifecycle()
    val behavior = (values as? SettingsState.Ready)?.values?.behavior ?: BehaviorSettings()
    LaunchedEffect(behavior.reducedMotion) { view.visualizerRenderer.reducedMotion = behavior.reducedMotion }
    // The fingers themselves reach the engine through the host (VisualizerCanvasHost); this is only
    // whether a Style that does not draw their strokes itself shows them as the shared smear.
    LaunchedEffect(behavior.touchSmear, behavior.touchSmearStrength) {
        view.visualizerRenderer.touchSmear = behavior.touchSmear
        view.visualizerRenderer.touchSmearStrength = behavior.touchSmearStrength
    }
}

/** The samples the engine's ring received since the last frame; read on the GL thread only. */
private class LivePcm(
    private val ring: PcmRingBuffer,
) {
    private val scratch = FloatArray(PCM_FLOATS)
    private var cursor = 0L

    fun next(): PcmChunk? {
        val count = ring.copyNewSince(cursor, scratch)
        cursor = ring.lastCopyEndIndex
        return if (count > 0) PcmChunk(scratch, count) else null
    }
}

private const val PCM_FLOATS = 4096
private const val TAG = "LiveVisual"
