package dev.geode.ui.shortcuts

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.engine.types.TrackId
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.feature.settings.api.AnalysisCommand
import dev.geode.feature.settings.api.AnalysisSettings
import dev.geode.feature.settings.api.Decibels
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.StyleMark
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.ui.shell.AppUiCommand
import dev.geode.ui.shell.AppUiState
import dev.geode.ui.shell.Place
import dev.geode.ui.shell.Sheet
import dev.geode.ui.shortcuts.ShortcutEffect.ToAppUi
import dev.geode.ui.shortcuts.ShortcutEffect.ToCustomization
import dev.geode.ui.shortcuts.ShortcutEffect.ToPlayback
import dev.geode.ui.shortcuts.ShortcutEffect.ToSettings
import dev.geode.ui.shortcuts.ShortcutEffect.ToVisuals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** What each shortcut does (#410), and where and when it is taken, as tables over plain state. */
class ShortcutsTest {
    private val bars = StyleEntry(SceneId("bars"), "Bars", StyleGroup.entries.first())
    private val tunnel = StyleEntry(SceneId("tunnel"), "Tunnel", StyleGroup.entries.first())
    private val waves = StyleEntry(SceneId("waves"), "Waves", StyleGroup.entries.first())
    private val visuals = VisualsState(styles = listOf(bars, tunnel, waves), current = tunnel.id, library = StyleLibrary())
    private val fullScreen = AppUiState(place = Place.VISUALS, fullScreen = true)
    private val scene = ShortcutScene(appUi = fullScreen, visuals = visuals, sensitivity = Decibels(0f))

    private val track = Track(TrackId("song"), "Song", "Artist", Millis(60_000))
    private val queue = Queue(listOf(track), 0)
    private val mark = PlayMark(Millis(0), Millis(0), moving = false)

    private fun effect(
        shortcut: Shortcut,
        at: ShortcutScene = scene,
        random: Random = Random(1),
    ) = Shortcuts.effectOf(shortcut, at, random)

    @Test
    fun `left and right choose the Style before and after the one shown, round the ends`() {
        assertEquals(ToVisuals(VisualsCommand.Choose(bars.id)), effect(Shortcut.PREVIOUS_STYLE))
        assertEquals(ToVisuals(VisualsCommand.Choose(waves.id)), effect(Shortcut.NEXT_STYLE))
        val atEnd = scene.copy(visuals = visuals.copy(current = waves.id))
        assertEquals(ToVisuals(VisualsCommand.Choose(bars.id)), effect(Shortcut.NEXT_STYLE, atEnd))
        val atStart = scene.copy(visuals = visuals.copy(current = bars.id))
        assertEquals(ToVisuals(VisualsCommand.Choose(waves.id)), effect(Shortcut.PREVIOUS_STYLE, atStart))
    }

    @Test
    fun `with no Style shown the arrows do nothing`() {
        val none = scene.copy(visuals = visuals.copy(current = null))
        assertNull(effect(Shortcut.PREVIOUS_STYLE, none))
        assertNull(effect(Shortcut.NEXT_STYLE, none))
    }

    @Test
    fun `up and down step Sensitivity by one decibel within its range`() {
        assertEquals(ToSettings(AnalysisCommand.SetSensitivity(Decibels(1f))), effect(Shortcut.SENSITIVITY_UP))
        assertEquals(ToSettings(AnalysisCommand.SetSensitivity(Decibels(-1f))), effect(Shortcut.SENSITIVITY_DOWN))
        val top = AnalysisSettings.SENSITIVITY.endInclusive
        val bottom = AnalysisSettings.SENSITIVITY.start
        assertEquals(top, Shortcuts.steppedSensitivity(top, up = true))
        assertEquals(bottom, Shortcuts.steppedSensitivity(bottom, up = false))
        assertEquals(Decibels(top.value - 1f), Shortcuts.steppedSensitivity(top, up = false))
        assertEquals(Decibels(bottom.value + 1f), Shortcuts.steppedSensitivity(bottom, up = true))
        assertEquals(Decibels(3.5f), Shortcuts.steppedSensitivity(Decibels(2.5f), up = true))
    }

    @Test
    fun `Sensitivity waits for Settings to load`() {
        val loading = scene.copy(sensitivity = null)
        assertNull(effect(Shortcut.SENSITIVITY_UP, loading))
        assertNull(effect(Shortcut.SENSITIVITY_DOWN, loading))
    }

    @Test
    fun `space pauses what is meant to play and plays what is not`() {
        val table =
            mapOf(
                PlayerState.Idle to null,
                PlayerState.Playing(queue, mark) to PlaybackCommand.Pause,
                PlayerState.Buffering(queue, mark) to PlaybackCommand.Pause,
                PlayerState.Loading(queue, mark, playWhenReady = true, reason = LoadReason.PLAY) to PlaybackCommand.Pause,
                PlayerState.Loading(queue, mark, playWhenReady = false, reason = LoadReason.PLAY) to PlaybackCommand.Resume,
                PlayerState.Paused(queue, mark) to PlaybackCommand.Resume,
                PlayerState.Ended(queue) to PlaybackCommand.Resume,
                PlayerState.Error(PlaybackFailure.ENGINE_NOT_RESPONDING, queue) to PlaybackCommand.Resume,
            )
        table.forEach { (player, command) ->
            assertEquals("$player", command, Shortcuts.playPause(player))
            assertEquals("$player", command?.let(::ToPlayback), effect(Shortcut.PLAY_PAUSE, scene.copy(player = player)))
        }
    }

    @Test
    fun `H holds the Style, and releases it once held`() {
        assertEquals(ToCustomization(CustomizationCommand.HoldStyle(true)), effect(Shortcut.HOLD))
        assertEquals(ToCustomization(CustomizationCommand.HoldStyle(false)), effect(Shortcut.HOLD, scene.copy(styleHeld = true)))
    }

    @Test
    fun `L loves the Style shown, and a loved one goes back to Normal`() {
        assertEquals(ToVisuals(VisualsCommand.SetMark(tunnel.id, StyleMark.LOVE)), effect(Shortcut.LOVE))
        val never = scene.copy(visuals = visuals.copy(library = StyleLibrary(marks = mapOf(tunnel.id to StyleMark.NEVER))))
        assertEquals(ToVisuals(VisualsCommand.SetMark(tunnel.id, StyleMark.LOVE)), effect(Shortcut.LOVE, never))
        val loved = scene.copy(visuals = visuals.copy(library = StyleLibrary(marks = mapOf(tunnel.id to StyleMark.LOVE))))
        assertEquals(ToVisuals(VisualsCommand.SetMark(tunnel.id, StyleMark.NORMAL)), effect(Shortcut.LOVE, loved))
    }

    @Test
    fun `L does nothing with no Style shown or before the library has loaded`() {
        assertNull(effect(Shortcut.LOVE, scene.copy(visuals = visuals.copy(current = null))))
        assertNull(effect(Shortcut.LOVE, scene.copy(visuals = visuals.copy(library = null))))
    }

    @Test
    fun `R draws any Style but the one shown and those marked Never`() {
        val drawn = (0 until 64).map { (effect(Shortcut.RANDOM_STYLE, random = Random(it)) as ToVisuals).command }.toSet()
        assertEquals(setOf(VisualsCommand.Choose(bars.id), VisualsCommand.Choose(waves.id)), drawn)
        val neverBars = scene.copy(visuals = visuals.copy(library = StyleLibrary(marks = mapOf(bars.id to StyleMark.NEVER))))
        repeat(16) { seed ->
            assertEquals(ToVisuals(VisualsCommand.Choose(waves.id)), effect(Shortcut.RANDOM_STYLE, neverBars, Random(seed)))
        }
        val noLibrary = scene.copy(visuals = visuals.copy(library = null, current = null))
        val all = (0 until 64).map { (effect(Shortcut.RANDOM_STYLE, noLibrary, Random(it)) as ToVisuals).command }.toSet()
        assertEquals(listOf(bars, tunnel, waves).map { VisualsCommand.Choose(it.id) }.toSet(), all)
    }

    @Test
    fun `R does nothing with no other Style to draw`() {
        val alone = scene.copy(visuals = visuals.copy(styles = listOf(tunnel)))
        assertNull(effect(Shortcut.RANDOM_STYLE, alone))
        assertNull(effect(Shortcut.RANDOM_STYLE, scene.copy(visuals = VisualsState())))
    }

    @Test
    fun `S takes a Snapshot only once Snapshot is wired`() {
        assertNull(effect(Shortcut.SNAPSHOT))
        assertEquals(ShortcutEffect.Snapshot, effect(Shortcut.SNAPSHOT, scene.copy(canSnapshot = true)))
    }

    @Test
    fun `F enters full screen and leaves it, Esc leaves it`() {
        assertEquals(ToAppUi(AppUiCommand.ExitFullScreen), effect(Shortcut.FULL_SCREEN))
        val onPage = scene.copy(appUi = AppUiState(place = Place.MUSIC))
        assertEquals(ToAppUi(AppUiCommand.EnterFullScreen), effect(Shortcut.FULL_SCREEN, onPage))
        assertEquals(ToAppUi(AppUiCommand.ExitFullScreen), effect(Shortcut.EXIT_FULL_SCREEN))
    }

    @Test
    fun `the arrows are taken ahead of the screen, every other key after it`() {
        Shortcut.entries.forEach { shortcut ->
            val expected = if (shortcut.repeats) KeyStage.BEFORE_VIEWS else KeyStage.AFTER_VIEWS
            assertEquals("$shortcut", expected, Shortcuts.stageOf(shortcut))
        }
    }

    @Test
    fun `the Visual is in front only in full screen, with nothing over it and Visuals on`() {
        assertTrue(scene.visualInFront)
        assertFalse(scene.copy(appUi = AppUiState(place = Place.VISUALS)).visualInFront)
        assertFalse(scene.copy(appUi = fullScreen.copy(sheet = Sheet.CUSTOMIZE)).visualInFront)
        assertFalse(scene.copy(visualsOff = true).visualInFront)
    }

    @Test
    fun `over the Visual every shortcut is taken at its own stage and no other`() {
        Shortcut.entries.forEach { shortcut ->
            val own = Shortcuts.stageOf(shortcut)
            KeyStage.entries.forEach { stage ->
                assertEquals("$shortcut at $stage", stage == own, Shortcuts.applies(shortcut, stage, scene))
            }
        }
    }

    @Test
    fun `away from the Visual the arrows move focus and Esc is Back, while the letters and space still work`() {
        val pages =
            listOf(
                scene.copy(appUi = AppUiState(place = Place.SETTINGS)),
                scene.copy(appUi = fullScreen.copy(sheet = Sheet.QUEUE)),
                scene.copy(visualsOff = true),
            )
        pages.forEach { page ->
            Shortcut.entries.forEach { shortcut ->
                val taken = KeyStage.entries.any { Shortcuts.applies(shortcut, it, page) }
                assertEquals("$shortcut on $page", !shortcut.overVisualOnly, taken)
            }
        }
    }
}
