package dev.geode.feature.playback.handlers

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.feature.settings.api.BackgroundSettings
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Leaving the app with Settings' "Music keeps playing" on and off (#28). */
class MusicInBackgroundTest {
    private val track = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val playing = PlayerState.Playing(Queue(listOf(track), 0), PlayMark(Millis(1_000), Millis(5_000), moving = true))
    private val paused = PlayerState.Paused(Queue(listOf(track), 0), PlayMark.still(Millis(1_000)))

    @Test
    fun `with music not to keep playing, leaving the app pauses it`() {
        val playback = FakePlayback(playing)

        MusicInBackground(playback, settingsWith(music = false)).onAppLeft()

        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Pause), playback.sent)
    }

    @Test
    fun `with music to keep playing, leaving the app leaves it playing`() {
        val playback = FakePlayback(playing)

        MusicInBackground(playback, settingsWith(music = true)).onAppLeft()

        assertEquals(emptyList<PlaybackCommand>(), playback.sent)
    }

    @Test
    fun `paused music is left alone`() {
        val playback = FakePlayback(paused)

        MusicInBackground(playback, settingsWith(music = false)).onAppLeft()

        assertEquals(emptyList<PlaybackCommand>(), playback.sent)
    }

    @Test
    fun `while the settings are still being read, the music keeps playing`() {
        val playback = FakePlayback(playing)

        MusicInBackground(playback, FakeSettings(SettingsState.Loading())).onAppLeft()

        assertEquals(emptyList<PlaybackCommand>(), playback.sent)
    }

    private fun settingsWith(music: Boolean): Settings =
        FakeSettings(SettingsState.Ready(SettingsValues(background = BackgroundSettings(music = music))))

    private class FakeSettings(
        state: SettingsState,
    ) : Settings {
        override val state: StateFlow<SettingsState> = MutableStateFlow(state)
        override val events: Flow<Nothing> = emptyFlow()

        override fun send(command: SettingsCommand) = Unit
    }
}
