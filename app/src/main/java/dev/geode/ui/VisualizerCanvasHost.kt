package dev.geode.ui

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.geode.render.GravityMath
import dev.geode.render.GravitySource
import dev.geode.render.VisualizerView

/**
 * Hosts the Visual's view in the App UI. The Visual itself stays out of :design.
 *
 * Every place that shows the Visual goes through here, so there is exactly one gesture pipeline on
 * the surface ([VisualTouchInput]): finger positions and strokes reach the engine, and a double tap
 * means whatever the place says. The Visuals tab passes no [onDoubleTap]; full-screen mode passes
 * the one that shows and hides its controls, so its overlay needs no second, competing gesture layer.
 *
 * The phone's tilt reaches the engine from here too ([GravitySource]), because this is where the
 * Visual is on screen: the sensor runs only while a host is shown, the app is resumed and [tilt] is
 * on, and stops the moment any of those ends.
 */
@Composable
fun VisualizerCanvasHost(
    view: VisualizerView,
    modifier: Modifier = Modifier,
    onDoubleTap: (() -> Unit)? = null,
    tilt: Boolean = false,
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
    val gravity =
        remember(view) {
            GravitySource(
                view.context,
                rotation = { view.display?.rotation ?: GravityMath.ROTATION_0 },
                sink = view.visualizerRenderer::submitGravity,
            )
        }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(gravity, tilt, lifecycle) {
        val observer =
            LifecycleEventObserver { _, _ ->
                if (tilt && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) gravity.start() else gravity.stop()
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            gravity.stop()
        }
    }
}
