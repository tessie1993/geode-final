package dev.geode.feature.playback.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * App UI screenshots of the player, its lyrics and seek waveform, the mini-player and the app-wide
 * error banner, taken from their previews (ADR-0003).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class PlayerScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun playing() = capture("PlayerScreen_playing") { PlayerPlayingPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun playingDark() = capture("PlayerScreen_playing_dark") { PlayerPlayingDarkPreview() }

    @Test
    fun paused() = capture("PlayerScreen_paused") { PlayerPausedPreview() }

    @Test
    fun buffering() = capture("PlayerScreen_buffering") { PlayerBufferingPreview() }

    @Test
    fun loading() = capture("PlayerScreen_loading") { PlayerLoadingPreview() }

    @Test
    fun ended() = capture("PlayerScreen_ended") { PlayerEndedPreview() }

    @Test
    fun error() = capture("PlayerScreen_error") { PlayerErrorPreview() }

    @Test
    fun idle() = capture("PlayerScreen_idle") { PlayerIdlePreview() }

    @Test
    fun lyrics() = capture("PlayerScreen_lyrics") { PlayerLyricsPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun lyricsDark() = capture("PlayerScreen_lyrics_dark") { PlayerLyricsDarkPreview() }

    @Test
    fun plainLyrics() = capture("PlayerScreen_lyrics_untimed") { PlayerPlainLyricsPreview() }

    @Test
    fun noLyrics() = capture("PlayerScreen_no_lyrics") { PlayerNoLyricsPreview() }

    @Test
    fun looping() = capture("PlayerScreen_loop_no_waveform") { PlayerLoopingPreview() }

    @Test
    fun banner() = capture("ErrorBanner") { ErrorBannerShowingPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun bannerDark() = capture("ErrorBanner_dark") { ErrorBannerDarkPreview() }

    @Test
    fun miniPlaying() = capture("MiniPlayer_playing") { MiniPlayerPlayingPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun miniPausedDark() = capture("MiniPlayer_paused_dark") { MiniPlayerPausedDarkPreview() }

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent(content)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
