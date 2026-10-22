package dev.geode.feature.search.handlers

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.engine.types.TrackId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFact
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.library.api.Library
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryFact
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.Song
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFact
import dev.geode.feature.playback.api.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

fun song(
    id: String,
    title: String,
    artist: String = "",
    path: String = "/music/$id.mp3",
): Song = Song(TrackId(id), title, artist, "", "", "", "", path, "mp3", Millis(200_000), Millis(1))

/**
 * A feature as Search sees it: its state, which a test moves, and every Command sent to it, recorded.
 * The next send throws [failNextSend] when it is set.
 */
open class Recording<S, C>(
    initial: S,
) {
    val mutableState = MutableStateFlow(initial)
    val sent = mutableListOf<C>()
    var failNextSend: Exception? = null

    fun record(command: C) {
        failNextSend?.let {
            failNextSend = null
            throw it
        }
        sent += command
    }
}

class FakeLibrary(
    initial: LibraryState,
) : Recording<LibraryState, LibraryCommand>(initial),
    Library {
    override val state: StateFlow<LibraryState> = mutableState
    override val events: Flow<LibraryFact> = emptyFlow()

    override fun send(command: LibraryCommand) = record(command)
}

class FakePlayback(
    initial: PlaybackState,
) : Recording<PlaybackState, PlaybackCommand>(initial),
    Playback {
    override val state: StateFlow<PlaybackState> = mutableState
    override val events: Flow<PlaybackFact> = emptyFlow()

    override fun send(command: PlaybackCommand) = record(command)
}

class FakeCustomization :
    Recording<CustomizationState, CustomizationCommand>(
        CustomizationState(Customization(SceneId("tunnel"), emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList(), emptyMap())),
    ),
    CustomizationFeature {
    override val state: StateFlow<CustomizationState> = mutableState
    override val events: Flow<CustomizationFact> = emptyFlow()

    override fun send(command: CustomizationCommand) = record(command)
}
