package dev.geode.ui

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.geode.R
import dev.geode.RingLog
import dev.geode.design.glass.GlassButton
import dev.geode.design.glass.GlassSpacing
import dev.geode.render.VisualizerView
import dev.geode.util.bestEffort
import android.graphics.Color as AndroidColor

/**
 * The second screen's state (#407), kept current with the connected presentation displays while the
 * caller is composed. Changes go through [SecondScreen.reduce], with [send].
 */
@Composable
internal fun rememberSecondScreen(): MutableState<SecondScreenState> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(SecondScreenState()) }
    DisposableEffect(context) {
        val manager = context.getSystemService(DisplayManager::class.java)
        val refresh = { state.send(SecondScreenEvent.DisplaysChanged(presentationDisplays(context, manager))) }
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = refresh()

                override fun onDisplayRemoved(displayId: Int) = refresh()

                override fun onDisplayChanged(displayId: Int) = refresh()
            }
        refresh()
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
    return state
}

/** Applies [event] to the second screen's state. */
internal fun MutableState<SecondScreenState>.send(event: SecondScreenEvent) {
    value = SecondScreen.reduce(value, event)
}

/** The connected displays a Presentation can go to, minus the one the app itself is on. */
private fun presentationDisplays(
    context: Context,
    manager: DisplayManager?,
): List<ExternalDisplay> {
    val all =
        manager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .orEmpty()
            .map { ExternalDisplay(it.displayId, it.name.orEmpty()) }
    val own =
        context
            .findActivity()
            ?.window
            ?.decorView
            ?.display
            ?.displayId
    return SecondScreen.candidates(all, own)
}

/**
 * Shows the one live Visual full screen on [display] (#407), through the platform's Presentation: the
 * same [view], and so the same engine instance, moves into a window on that display, and the phone's
 * places stop hosting it ([VisualHome.SECOND_SCREEN]). Leaving the composition takes the window down
 * and gives the view back, for the phone's host to pick up. [onLost] is called when the window goes
 * away by itself (the display was unplugged) or cannot be shown at all.
 *
 * The window keeps the screen on while it is shown: a phone that slept would stop the app, and with it
 * the Visual on the big screen.
 */
@Composable
internal fun SecondScreenPresentation(
    display: ExternalDisplay,
    view: VisualizerView,
    onLost: () -> Unit,
) {
    val context = LocalContext.current
    val lost by rememberUpdatedState(onLost)
    DisposableEffect(display.id, view) {
        val target = context.getSystemService(DisplayManager::class.java)?.getDisplay(display.id)
        val activity = context.findActivity()
        val presentation = if (target != null && activity != null) VisualPresentation(activity, target, view) else null
        presentation?.setOnDismissListener { lost() }
        val shown = presentation != null && runCatching { presentation.show() }.onFailure { RingLog.note(TAG, "not shown", it) }.isSuccess
        if (shown) {
            // A Render gives way to the Visual while it is on the big screen, as on the phone (ADR-0004).
            LiveVisualPresence.shown()
        } else {
            presentation?.setOnDismissListener(null)
            lost()
        }
        onDispose {
            presentation?.setOnDismissListener(null)
            if (shown) {
                LiveVisualPresence.hidden()
                bestEffort(TAG, "dismiss") { presentation?.dismiss() }
            }
            presentation?.release()
        }
    }
}

/** The window on the connected display: the Visual's [view], full screen on black, keeping the screen on. */
private class VisualPresentation(
    activity: Activity,
    display: Display,
    private val view: VisualizerView,
) : Presentation(activity, display) {
    private val container = FrameLayout(activity).apply { setBackgroundColor(AndroidColor.BLACK) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(container)
    }

    /** Gives the view back, only from this window: the phone's host may already have taken it. */
    fun release() {
        if (view.parent === container) container.removeView(view)
    }
}

/**
 * What the phone shows where the Visual was while it is on the connected display named [displayName]:
 * a plain stand-in that says where it went, and a button that brings it back. A double tap does what
 * it does over the Visual ([onDoubleTap]), so full-screen mode's controls can still be shown and hidden.
 */
@Composable
internal fun SecondScreenStandIn(
    displayName: String,
    onBringBack: () -> Unit,
    onDoubleTap: (() -> Unit)?,
) {
    val doubleTap by rememberUpdatedState(onDoubleTap)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { doubleTap?.invoke() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(GlassSpacing.Large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GlassSpacing.Medium),
        ) {
            Text(
                stringResource(R.string.second_screen_showing_on, displayName),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            GlassButton(onClick = onBringBack, filled = false) { Text(stringResource(R.string.second_screen_bring_back)) }
        }
    }
}

/** The offer to show the Visual full screen on the connected display named [displayName]. */
@Composable
internal fun SecondScreenOffer(
    displayName: String,
    onShow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassButton(onClick = onShow, modifier = modifier, filled = false) {
        Text(stringResource(R.string.second_screen_show_on, displayName))
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

private const val TAG = "SecondScreen"
