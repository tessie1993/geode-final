package dev.geode.feature.playback.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.Playback

/**
 * The queue's place (ADR-0003): it reads Playback's state and turns the screen's actions into
 * Commands. `:app` wires it at the switch-over (#17).
 */
@Composable
fun QueueRoute(
    playback: Playback,
    modifier: Modifier = Modifier,
) {
    val state by playback.state.collectAsStateWithLifecycle()
    val model = state.toQueueModel()
    QueueScreen(model, onAction = { playback.send(it.toCommand(model)) }, modifier)
}

/**
 * The listening tools' place: as [QueueRoute]. An action reads the clock when it is tapped, so that
 * the A-B loop is marked where the track is at that moment (ADR-0004: the clock, never the player).
 */
@Composable
fun ListeningRoute(
    playback: Playback,
    elapsedNow: () -> Millis,
    modifier: Modifier = Modifier,
) {
    val state by playback.state.collectAsStateWithLifecycle()
    ListeningScreen(
        state.toListeningModel(elapsedNow()),
        onAction = { action -> playback.send(action.toCommand(playback.state.value.toListeningModel(elapsedNow()))) },
        modifier,
    )
}
