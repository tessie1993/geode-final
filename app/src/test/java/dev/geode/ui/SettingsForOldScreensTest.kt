package dev.geode.ui

import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFact
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.settings.api.EqualizerCommand
import dev.geode.feature.settings.api.LookCommand
import dev.geode.feature.settings.api.LookSettings
import dev.geode.feature.settings.api.Millibels
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.geode.feature.settings.api.PlaybackCommand as SettingsPlayback
import dev.geode.feature.settings.api.PlayerPosition as SettingsPosition

/** The old screens read and save their settings through the one Settings store (#17, Settings slice). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsForOldScreensTest {
    private class FakeSettings : Settings {
        override val state = MutableStateFlow<SettingsState>(SettingsState.Loading())
        override val events: Flow<Nothing> = emptyFlow()
        val sent = mutableListOf<SettingsCommand>()

        override fun send(command: SettingsCommand) {
            sent += command
        }
    }

    private class FakePlayback : Playback {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState(options = ListeningOptions(shuffle = false)))
        override val events: Flow<PlaybackFact> = emptyFlow()
        val sent = mutableListOf<PlaybackCommand>()

        override fun send(command: PlaybackCommand) {
            sent += command
        }
    }

    private val settings = FakeSettings()
    private val playback = FakePlayback()
    private val presets = listOf("Flat", "Bass", "Treble", "Vocal", "Rock", "Electronic")

    // The app names the equalizer's bands from the native chain, which the plain JVM cannot load.
    private val old = SettingsForOldScreens(settings, playback, presets, { "band $it" }, CoroutineScope(UnconfinedTestDispatcher()))

    @Test
    fun `the old screens wait for the stored settings, then show them`() {
        assertFalse(old.loaded.value)

        settings.state.value = SettingsState.Ready(SettingsValues(look = LookSettings(playerPosition = SettingsPosition.TOP)))

        assertTrue(old.loaded.value)
        assertEquals(PlayerPosition.TOP, old.guiPrefs.value.playerPosition)
    }

    @Test
    fun `saving the App UI settings sends Settings what changed`() =
        runTest {
            settings.state.value = SettingsState.Ready(SettingsValues())

            old.setGuiPrefs(old.guiPrefs.value.copy(compactPlayer = true))

            assertEquals(listOf(LookCommand.SetCompactPlayer(true)), settings.sent)
        }

    @Test
    fun `saving the player settings keeps them in Settings only, and Playback orders the tracks`() {
        settings.state.value = SettingsState.Ready(SettingsValues())

        old.setPlayerPrefs(old.prefs.value.copy(crossfadeMs = 3_000, gapless = false, shuffle = true, repeatMode = 1))

        assertEquals(listOf(SettingsPlayback.SetCrossfade(Millis(3_000)), SettingsPlayback.SetGapless(false)), settings.sent)
        // The crossfade and gapless are Settings' alone: Playback follows them, it is not written to twice.
        assertEquals(
            listOf(
                PlaybackCommand.SetShuffle(true),
                PlaybackCommand.SetRepeat(RepeatMode.ONE),
            ),
            playback.sent,
        )
    }

    @Test
    fun `the equalizer panel reads and changes Settings' equalizer`() {
        settings.state.value = SettingsState.Ready(SettingsValues())

        old.setEqualizerEnabled(true)
        old.setEqualizerBand(2, 300)
        old.useEqualizerPreset(1)
        old.setLoudness(150)

        val fx = old.audioFx.value
        assertEquals(presets, fx.presets)
        assertEquals("band 0", fx.bands[0].label)
        assertEquals(
            listOf(
                EqualizerCommand.SetEnabled(true),
                EqualizerCommand.SetBand(2, Millibels(300)),
                EqualizerCommand.UsePreset(dev.geode.feature.settings.api.EqualizerPreset.BASS),
                EqualizerCommand.SetLoudness(Millibels(150)),
            ),
            settings.sent,
        )
    }

    @Test
    fun `export defaults come from Settings and are saved there`() {
        settings.state.value = SettingsState.Ready(SettingsValues())
        val defaults = old.exportDefaults()

        old.saveExportDefaults(defaults.copy(loopSafe = true))

        assertEquals(
            listOf(
                dev.geode.feature.settings.api.ExportCommand
                    .SetLoopSafe(true),
            ),
            settings.sent,
        )
    }
}
