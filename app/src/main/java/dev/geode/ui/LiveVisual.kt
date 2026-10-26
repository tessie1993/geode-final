package dev.geode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.RingLog
import dev.geode.engine.bridge.NativeSnapshots
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.playback.PlaybackEngine
import dev.geode.render.VisualizerView

/**
 * Binds the one live Visual to its sources (#17). Customization reaches it through the plan alone:
 * the renderer is attached to the [snapshots] exchange, which Customization's store publishes to
 * (ADR-0002, ADR-0004). The sound never passes through the main thread or Kotlin: the renderer is
 * attached to the engine's one audio feed, and each frame it reads the features heard now and the
 * stereo window natively, on its own thread. Only the Style to draw and the reduce-motion switch are
 * handed over from here, when they change.
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
        renderer.feed = session.feed
        renderer.onShaderError = { error -> error?.let { RingLog.note(TAG, "shader: $it") } }
        // While the Visual is bound, the analysis runs.
        session.feed.acquire()
        onDispose {
            session.feed.release()
            renderer.feed = null
            PlaybackEngine.releaseUi()
        }
    }
    val state by customization.state.collectAsStateWithLifecycle()
    val style = state.current.style
    LaunchedEffect(style) { view.visualizerRenderer.requestedSceneId = style }
    val values by settings.state.collectAsStateWithLifecycle()
    val reducedMotion = (values as? SettingsState.Ready)?.values?.behavior?.reducedMotion ?: false
    LaunchedEffect(reducedMotion) { view.visualizerRenderer.reducedMotion = reducedMotion }
}

private const val TAG = "LiveVisual"
