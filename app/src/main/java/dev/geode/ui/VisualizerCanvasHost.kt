package dev.geode.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
 * Visual is on screen: the sensor runs only while a host is shown, the app is resumed, [tilt] (the
 * weight Settings asks for, 0 for off) is above zero and Battery Saver is off, and stops the moment
 * any of those ends.
 */
@Composable
fun VisualizerCanvasHost(
    view: VisualizerView,
    modifier: Modifier = Modifier,
    onDoubleTap: (() -> Unit)? = null,
    tilt: Float = 0f,
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
    gravity.weight = tilt
    val powerSave = rememberPowerSave()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val wanted = tilt > 0f && !powerSave
    DisposableEffect(gravity, wanted, lifecycle) {
        val observer =
            LifecycleEventObserver { _, _ ->
                if (wanted && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) gravity.start() else gravity.stop()
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            gravity.stop()
        }
    }
}

/** Whether Battery Saver is on, kept current while the caller is composed: tilt is off under it. */
@Composable
private fun rememberPowerSave(): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var on by remember(power) { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(context, power) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    on = power?.isPowerSaveMode == true
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        on = power?.isPowerSaveMode == true
        onDispose { context.unregisterReceiver(receiver) }
    }
    return on
}
