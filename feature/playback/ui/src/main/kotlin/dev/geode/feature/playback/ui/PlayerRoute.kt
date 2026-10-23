package dev.geode.feature.playback.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlayerState
import kotlinx.coroutines.delay

/** How often the shown position is worked out again while it moves. It reads the clock, never the player. */
internal val POSITION_REFRESH = Millis(250)

/**
 * The player's place (ADR-0003): the only part that touches Playback. It reads Playback's state,
 * works out the position from the clock between announcements (ADR-0004), and turns the screen's
 * actions into Commands. The actions that are not Playback's (opening the queue or the tools, the
 * favourite heart) go to [onOther], for the App UI and Library; [favourite] is Library's to say.
 */
@Composable
fun PlayerRoute(
    playback: Playback,
    elapsedNow: () -> Millis,
    modifier: Modifier = Modifier,
    favourite: Boolean? = null,
    onOther: (PlayerAction) -> Unit = {},
) {
    val state by playback.state.collectAsStateWithLifecycle()
    val now by rememberNow(moving = state.player is PlayerState.Playing, elapsedNow)
    val model = state.toPlayerModel(now)
    PlayerScreen(
        model,
        onAction = { action -> action.toCommand(model)?.let(playback::send) ?: onOther(action) },
        modifier,
        favourite,
    )
}

/** The mini-player's place: as [PlayerRoute], and [onExpand] opens the player. */
@Composable
fun MiniPlayerRoute(
    playback: Playback,
    elapsedNow: () -> Millis,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by playback.state.collectAsStateWithLifecycle()
    val now by rememberNow(moving = state.player is PlayerState.Playing, elapsedNow)
    val model = state.toPlayerModel(now)
    MiniPlayer(
        model,
        onAction = { action -> if (action == PlayerAction.Expand) onExpand() else action.toCommand(model)?.let(playback::send) },
        modifier,
    )
}

/** The clock, read again every [POSITION_REFRESH] while the position [moving], and once when it stops. */
@Composable
private fun rememberNow(
    moving: Boolean,
    elapsedNow: () -> Millis,
): State<Millis> =
    produceState(elapsedNow(), moving) {
        value = elapsedNow()
        while (moving) {
            delay(POSITION_REFRESH.value)
            value = elapsedNow()
        }
    }
