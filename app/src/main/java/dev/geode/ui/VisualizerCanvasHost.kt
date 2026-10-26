package dev.geode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.geode.render.VisualizerView
import java.util.concurrent.atomic.AtomicInteger

/** Hosts the Visual's view in the App UI. The Visual itself stays out of :design. */
@Composable
fun VisualizerCanvasHost(
    view: VisualizerView,
    modifier: Modifier = Modifier,
) {
    // On screen only while the app is in front: a Visual hosted behind another app is not seen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle) {
        var counted = false
        val follow = { on: Boolean ->
            if (on != counted) {
                counted = on
                if (on) LiveVisualPresence.shown() else LiveVisualPresence.hidden()
            }
        }
        val observer = LifecycleEventObserver { _, _ -> follow(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            follow(false)
        }
    }
    AndroidView(
        factory = {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view
        },
        modifier = modifier,
    )
}

/**
 * Whether the live Visual is on screen: a Render gives way to it while it is (ADR-0004). Counted, as a
 * place may host the Visual for a moment before the one it moves from lets it go.
 */
object LiveVisualPresence {
    private val hosts = AtomicInteger()

    val isShown: Boolean get() = hosts.get() > 0

    fun shown() {
        hosts.incrementAndGet()
    }

    fun hidden() {
        hosts.decrementAndGet()
    }
}
