package dev.geode.ui

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.ui.PlayerAction
import dev.geode.feature.playback.ui.PlayerModel
import dev.geode.feature.playback.ui.PlayerStatus
import dev.geode.feature.playback.ui.toCommand
import dev.geode.feature.playback.ui.toPlayerModel
import dev.geode.util.bestEffort

/** The buttons of the picture-in-picture window (#287), in the order it shows them. */
internal enum class PipButton(
    private val action: PlayerAction,
) {
    PLAY_PAUSE(PlayerAction.PlayPause),
    NEXT(PlayerAction.Next),
    ;

    /** Whether the button can be pressed while the player shows [model], as on the Player controls. */
    fun enabledFor(model: PlayerModel): Boolean =
        when (this) {
            PLAY_PAUSE -> model.status != PlayerStatus.IDLE
            NEXT -> model.canGoNext
        }

    /** The Command a press sends to Playback while it shows [model], or null when the button is off. */
    fun command(model: PlayerModel): PlaybackCommand? = action.toCommand(model).takeIf { enabledFor(model) }
}

/** The rules of picture-in-picture for the full-screen Visual (#287), as plain functions. */
internal object PictureInPicture {
    /**
     * Whether leaving the app turns the Visual into a picture-in-picture window: only from the
     * full-screen Visual, and never while Visuals off is on, since then there is no Visual to show.
     */
    fun allowed(
        fullScreen: Boolean,
        visualsOff: Boolean,
    ): Boolean = fullScreen && !visualsOff

    /**
     * The window's shape, width to height, for a Visual drawn [width] by [height]: the same shape,
     * kept inside the widest and tallest the platform accepts (2.39:1 either way).
     */
    fun aspect(
        width: Int,
        height: Int,
    ): Pair<Int, Int> =
        when {
            width <= 0 || height <= 0 -> defaultAspect
            width.toLong() * LIMIT_SHORT > height.toLong() * LIMIT_LONG -> LIMIT_LONG to LIMIT_SHORT
            height.toLong() * LIMIT_SHORT > width.toLong() * LIMIT_LONG -> LIMIT_SHORT to LIMIT_LONG
            else -> width to height
        }

    val defaultAspect = 16 to 9
    private const val LIMIT_LONG = 239
    private const val LIMIT_SHORT = 100
}

/**
 * Picture-in-picture for the full-screen Visual (#287), the Android counterpart of an always-on-top
 * window. While [allowed], the home gesture shrinks the activity into a small window instead of
 * stopping it: the Visual goes on drawing there, with play/pause and next as the window's buttons,
 * and the window's own expand button brings the full-screen Visual back. The Visual's surface is
 * the window, so its render size follows the window's size like any other surface change.
 *
 * Returns whether the activity is in picture-in-picture now: the host shows only the Visual then.
 */
@Composable
internal fun rememberPictureInPicture(
    allowed: Boolean,
    playback: Playback,
): Boolean {
    val context = LocalContext.current
    val activity =
        remember(context) {
            (context.findActivity() as? ComponentActivity)
                ?.takeIf { it.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
        } ?: return false
    var inPip by remember(activity) { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    PipButtonsReceiver(activity, playback)
    val playing by playback.state.collectAsStateWithLifecycle()
    val model = playing.toPlayerModel(pipNow())
    val configuration = LocalConfiguration.current
    // The window takes the full-screen Visual's shape; inside the window the screen is the window.
    val shape = remember { mutableStateOf(PictureInPicture.defaultAspect) }
    LaunchedEffect(
        activity,
        allowed,
        inPip,
        model.meantToPlay,
        PipButton.entries.map { it.enabledFor(model) },
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    ) {
        if (!inPip) shape.value = PictureInPicture.aspect(configuration.screenWidthDp, configuration.screenHeightDp)
        bestEffort(TAG, "setPictureInPictureParams") {
            activity.setPictureInPictureParams(pipParams(activity, allowed, model, shape.value))
        }
    }
    return inPip
}

/** Turns presses of the window's buttons into Commands to Playback, while the host is shown. */
@Composable
private fun PipButtonsReceiver(
    activity: ComponentActivity,
    playback: Playback,
) {
    DisposableEffect(activity, playback) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val button = PipButton.entries.getOrNull(intent.getIntExtra(EXTRA_BUTTON, -1)) ?: return
                    // What the player is now, not what it was when the window last drew its buttons.
                    button.command(playback.state.value.toPlayerModel(pipNow()))?.let(playback::send)
                }
            }
        // Not exported: only the window's own PendingIntents, sent as this app, reach it.
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(pipAction(activity)), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { activity.unregisterReceiver(receiver) }
    }
}

private fun pipParams(
    activity: ComponentActivity,
    allowed: Boolean,
    model: PlayerModel,
    aspect: Pair<Int, Int>,
): PictureInPictureParams =
    PictureInPictureParams
        .Builder()
        .setAutoEnterEnabled(allowed)
        .setAspectRatio(Rational(aspect.first, aspect.second))
        .setActions(PipButton.entries.take(activity.maxNumPictureInPictureActions).map { remoteAction(activity, it, model) })
        .build()

private fun remoteAction(
    context: Context,
    button: PipButton,
    model: PlayerModel,
): RemoteAction {
    val (icon, label) =
        when {
            button == PipButton.NEXT -> R.drawable.ic_widget_next to R.string.action_next
            model.meantToPlay -> R.drawable.ic_widget_pause to R.string.action_pause
            else -> R.drawable.ic_widget_play to R.string.action_play
        }
    val text = context.getString(label)
    val intent = Intent(pipAction(context)).setPackage(context.packageName).putExtra(EXTRA_BUTTON, button.ordinal)
    val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    val press = PendingIntent.getBroadcast(context, button.ordinal, intent, flags)
    return RemoteAction(Icon.createWithResource(context, icon), text, text, press).apply { isEnabled = button.enabledFor(model) }
}

private fun pipAction(context: Context): String = "${context.packageName}.PIP_BUTTON"

/** The monotonic clock Playback's marks use (ADR-0004). */
private fun pipNow(): Millis = Millis(SystemClock.elapsedRealtime())

private const val EXTRA_BUTTON = "button"
private const val TAG = "PictureInPicture"
