package dev.geode.playback

import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.MAX_CROSSFADE
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.settings.api.PlaybackSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings own the crossfade and gapless; Playback follows them and keeps no copy (#17). */
class TransitionsFromSettingsTest {
    @Test
    fun `Playback is told only what differs from Settings`() {
        val settings = PlaybackSettings(crossfade = Millis(4_000), gapless = false)

        assertEquals(
            listOf(PlaybackCommand.SetCrossfade(Millis(4_000)), PlaybackCommand.SetGapless(false)),
            TransitionsFromSettings.commandsFor(settings, ListeningOptions()),
        )
        assertEquals(
            listOf(PlaybackCommand.SetGapless(false)),
            TransitionsFromSettings.commandsFor(settings, ListeningOptions(crossfade = Millis(4_000))),
        )
        assertEquals(
            emptyList<PlaybackCommand>(),
            TransitionsFromSettings.commandsFor(settings, ListeningOptions(crossfade = Millis(4_000), gapless = false)),
        )
    }

    @Test
    fun `a crossfade longer than Playback allows is compared as Playback holds it, so they agree`() {
        val settings = PlaybackSettings(crossfade = MAX_CROSSFADE + Millis(5_000))

        assertEquals(listOf(PlaybackCommand.SetCrossfade(MAX_CROSSFADE)), TransitionsFromSettings.commandsFor(settings, ListeningOptions()))
        assertEquals(
            emptyList<PlaybackCommand>(),
            TransitionsFromSettings.commandsFor(settings, ListeningOptions(crossfade = MAX_CROSSFADE)),
        )
    }

    @Test
    fun `Playback's storage keeps shuffle and repeat but not the transitions`() {
        val options = ListeningOptions(shuffle = true, repeat = RepeatMode.ONE, crossfade = Millis(3_000), gapless = false)

        assertEquals(ListeningOptions(shuffle = true, repeat = RepeatMode.ONE), TransitionsFromSettings.withoutTransitions(options))
    }
}
