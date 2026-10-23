package dev.geode.feature.playback.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

// The previews (ADR-0003). The theme is Material's own until the App UI's
// theme moves to :design (#61).

@Composable
internal fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}

@Composable
internal fun PlayerPreview(
    model: PlayerModel,
    dark: Boolean = false,
    lyricsOpen: Boolean = false,
) {
    PreviewTheme(dark) { PlayerScreen(model, onAction = {}, lyricsOpen = lyricsOpen) }
}

@Composable
internal fun MiniPlayerPreview(
    model: PlayerModel,
    dark: Boolean = false,
) {
    PreviewTheme(dark) { MiniPlayer(model, onAction = {}) }
}

@Preview(name = "Playing")
@Composable
internal fun PlayerPlayingPreview() = PlayerPreview(PreviewModels.playing)

@Preview(name = "Playing, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun PlayerPlayingDarkPreview() = PlayerPreview(PreviewModels.playing, dark = true)

@Preview(name = "Paused")
@Composable
internal fun PlayerPausedPreview() = PlayerPreview(PreviewModels.paused)

@Preview(name = "Buffering")
@Composable
internal fun PlayerBufferingPreview() = PlayerPreview(PreviewModels.buffering)

@Preview(name = "Loading")
@Composable
internal fun PlayerLoadingPreview() = PlayerPreview(PreviewModels.loading)

@Preview(name = "Ended")
@Composable
internal fun PlayerEndedPreview() = PlayerPreview(PreviewModels.ended)

@Preview(name = "Error")
@Composable
internal fun PlayerErrorPreview() = PlayerPreview(PreviewModels.error)

@Preview(name = "Idle")
@Composable
internal fun PlayerIdlePreview() = PlayerPreview(PreviewModels.idle)

// Lyrics and the A-B loop on the seek bar (#147).

@Preview(name = "Lyrics")
@Composable
internal fun PlayerLyricsPreview() = PlayerPreview(PreviewModels.playing, lyricsOpen = true)

@Preview(name = "Lyrics, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun PlayerLyricsDarkPreview() = PlayerPreview(PreviewModels.playing, dark = true, lyricsOpen = true)

@Preview(name = "Lyrics, untimed")
@Composable
internal fun PlayerPlainLyricsPreview() = PlayerPreview(PreviewModels.plainLyrics, lyricsOpen = true)

@Preview(name = "No lyrics")
@Composable
internal fun PlayerNoLyricsPreview() = PlayerPreview(PreviewModels.noLyrics, lyricsOpen = true)

@Preview(name = "A-B loop")
@Composable
internal fun PlayerLoopingPreview() = PlayerPreview(PreviewModels.looping)

@Preview(name = "Mini-player, playing")
@Composable
internal fun MiniPlayerPlayingPreview() = MiniPlayerPreview(PreviewModels.playing)

@Preview(name = "Mini-player, paused, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun MiniPlayerPausedDarkPreview() = MiniPlayerPreview(PreviewModels.paused, dark = true)
