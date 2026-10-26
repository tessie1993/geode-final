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
import dev.geode.feature.customization.handlers.StyleCues
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.playback.PlaybackEngine
import dev.geode.render.VisualizerRenderer
import dev.geode.render.VisualizerView
import dev.geode.render.scene.PcmChunk

/**
 * Binds the one live Visual to its sources (#17). Customization reaches it through the plan alone:
 * the renderer is attached to the [snapshots] exchange, which Customization's store publishes to
 * (ADR-0002, ADR-0004). The sound never passes through the main thread: each frame the renderer reads
 * the analysis' latest features from the [AudioBus] and the newest samples from the engine's ring, on
 * its own thread. Only the Style to draw, how long a change of Style crossfades (Style switching's
 * transition while it is on, #282) and the reduce-motion switch are handed over from here, when they
 * change. The engine's word that Style switching is due goes back to Customization as [styleCues].
 */
@Composable
fun LiveVisualBindings(
    view: VisualizerView,
    customization: CustomizationFeature,
    snapshots: NativeSnapshots,
    styleCues: StyleCues,
    settings: Settings,
) {
    val context = LocalContext.current
    DisposableEffect(view, snapshots, styleCues) {
        val renderer = view.visualizerRenderer
        val session = PlaybackEngine.acquireForUi(context)
        renderer.snapshots = snapshots
        renderer.featureSource = AudioBus::features
        renderer.pcmProvider = LivePcm(session.ring)::next
        renderer.onShaderError = { error -> error?.let { RingLog.note(TAG, "shader: $it") } }
        renderer.onStyleDue = styleCues::raise
        // While the Visual is bound, the analysis runs.
        AudioBus.addConsumer()
        onDispose {
            renderer.onStyleDue = {}
            AudioBus.removeConsumer()
            renderer.pcmProvider = { null }
            renderer.featureSource = { null }
            PlaybackEngine.releaseUi()
        }
    }
    val state by customization.state.collectAsStateWithLifecycle()
    val style = state.current.style
    val transition = state.current.automations.style?.transition ?: VisualizerRenderer.DEFAULT_TRANSITION
    // The crossfade's length goes first, so the change of Style it is for already uses it.
    LaunchedEffect(transition) { view.visualizerRenderer.transitionDuration = transition }
    LaunchedEffect(style) { view.visualizerRenderer.requestedSceneId = style }
    val values by settings.state.collectAsStateWithLifecycle()
    val reducedMotion = (values as? SettingsState.Ready)?.values?.behavior?.reducedMotion ?: false
    LaunchedEffect(reducedMotion) { view.visualizerRenderer.reducedMotion = reducedMotion }
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
