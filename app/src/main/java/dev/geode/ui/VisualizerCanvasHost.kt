package dev.geode.ui

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.geode.render.VisualizerView

/**
 * Hosts the Visual's view in the App UI. The Visual itself stays out of :design.
 *
 * Every place that shows the Visual goes through here, so there is exactly one gesture pipeline on
 * the surface ([VisualTouchInput]): finger positions and strokes reach the engine, and a double tap
 * means whatever the place says. The Visuals tab passes no [onDoubleTap]; full-screen mode passes
 * the one that shows and hides its controls, so its overlay needs no second, competing gesture layer.
 */
@Composable
fun VisualizerCanvasHost(
    view: VisualizerView,
    modifier: Modifier = Modifier,
    onDoubleTap: (() -> Unit)? = null,
) {
    // Read when it happens: entering or leaving full-screen reuses the same view without rebuilding
    // the listener, and nothing recomposes per touch event.
    val doubleTap = rememberUpdatedState(onDoubleTap)
    val touch =
        remember(view) {
            VisualTouch(
                submit = view.visualizerRenderer::submitTouchPoints,
                stroke = view.visualizerRenderer::queueTouchStroke,
            )
        }
    val input = remember(view, touch) { VisualTouchInput(view.context, touch) { doubleTap.value?.invoke() } }
    AndroidView(
        factory = {
            (view.parent as? ViewGroup)?.removeView(view)
            view.setOnTouchListener(input)
            view
        },
        modifier = modifier,
    )
    // Leaving a place must not leave a finger pinned on the engine; the renderer's own reset path
    // stays authoritative for everything else.
    DisposableEffect(touch) {
        onDispose { touch.clear() }
    }
}
