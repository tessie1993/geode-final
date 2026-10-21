package dev.geode.feature.playback.handlers

import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFact
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Playback as its interface shows it, with the state a test sets and the Commands it was sent. */
class FakePlayback(
    player: PlayerState,
) : Playback {
    private val mutableState = MutableStateFlow(PlaybackState(player = player))
    override val state: StateFlow<PlaybackState> = mutableState
    override val events: Flow<PlaybackFact> = emptyFlow()

    val sent = mutableListOf<PlaybackCommand>()

    var player: PlayerState
        get() = mutableState.value.player
        set(value) {
            mutableState.value = mutableState.value.copy(player = value)
        }

    override fun send(command: PlaybackCommand) {
        sent += command
    }
}
