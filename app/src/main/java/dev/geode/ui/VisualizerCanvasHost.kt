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
 * the surface ([VisualTouchInput]): finger positions and smears reach the engine, and a tap means
 * whatever the place says. The Visuals tab passes no [onTap]; full-screen mode passes the one that
 * shows and hides its controls, so its overlay no longer needs a second, competing gesture layer.
 */
@Composable
fun VisualizerCanvasHost(
    view: VisualizerView,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    // The tap is read when it happens: entering or leaving full-screen reuses the same view without
    // rebuilding the listener, and a recomposition never happens per touch event.
    val tap = rememberUpdatedState(onTap)
    val touch =
        remember(view) {
            VisualTouch(
                submit = view.visualizerRenderer::submitTouchPoints,
                stroke = view.visualizerRenderer::queueTouchStroke,
            )
        }
    val input =
        remember(view, touch) {
            VisualTouchInput(view.context, touch) { tap.value?.invoke() }
        }
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
