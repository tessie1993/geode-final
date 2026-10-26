package dev.geode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import dev.geode.RingLog
import dev.geode.engine.bridge.NativeSnapshots
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.settings.api.BehaviorSettings
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.VisualSurface
import dev.geode.playback.PlaybackEngine
import dev.geode.render.VisualizerView

/**
 * Binds the one live Visual to its sources (#17). Customization reaches it through the plan alone:
 * the renderer is attached to the [snapshots] exchange, which Customization's store publishes to
 * (ADR-0002, ADR-0004). The sound never passes through the main thread or Kotlin: the renderer is
 * attached to the engine's one audio feed, and each frame it reads the features heard now and the
 * stereo window natively, on its own thread. Only the Style to draw, the Visual behaviours from
 * Settings (reduce motion, the finger smear) and the [surface]'s quality and frame rate are handed
 * over from here, when they change.
 *
 * The analysis runs only while the Visual is [shown] and the app is started (#298): on another tab,
 * behind the lock screen or with the screen off it sleeps, and it catches up within a frame of the
 * Visual coming back. Picture-in-picture keeps the app started, so the Visual goes on reacting there.
 */
@Composable
fun LiveVisualBindings(
    view: VisualizerView,
    customization: CustomizationFeature,
    snapshots: NativeSnapshots,
    settings: Settings,
    surface: VisualSurface,
    shown: Boolean,
) {
    val context = LocalContext.current
    DisposableEffect(view, snapshots) {
        val renderer = view.visualizerRenderer
        val session = PlaybackEngine.acquireForUi(context)
        renderer.snapshots = snapshots
        renderer.feed = session.feed
        renderer.onShaderError = { error -> error?.let { RingLog.note(TAG, "shader: $it") } }
        onDispose {
            renderer.feed = null
            PlaybackEngine.releaseUi()
        }
    }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val watching = VisualWatch.watching(shown, lifecycleState.isAtLeast(Lifecycle.State.STARTED))
    DisposableEffect(watching) {
        RingLog.note(TAG, if (watching) "analysis runs" else "analysis sleeps")
        val feed = PlaybackEngine.feed.takeIf { watching }
        feed?.acquire()
        onDispose { feed?.release() }
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
    // The quality and frame rate of where the Visual is shown now (#394); until Settings are read, the engine's own.
    val drawing = (values as? SettingsState.Ready)?.values?.performance?.let { SurfaceDrawing.of(it, surface) }
    LaunchedEffect(drawing) { drawing?.applyTo(view.visualizerRenderer, view.framePacer, view.holder.surface) }
}

/** When the live Visual needs the analysis (#298). */
internal object VisualWatch {
    /**
     * Whether the analysis runs for the live Visual: only while it is [shown] (the Visuals tab, the
     * full-screen Visual or its picture-in-picture window) and the app is [started]. A stopped app (the
     * screen off, another app on top) shows no Visual, wherever it was left.
     */
    fun watching(
        shown: Boolean,
        started: Boolean,
    ): Boolean = shown && started

    /** Whether the live Visual is on screen: full screen, or not full screen but on the Visuals tab. */
    fun shown(
        onVisualsTab: Boolean,
        fullScreen: Boolean,
    ): Boolean = fullScreen || onVisualsTab
}

private const val TAG = "LiveVisual"
