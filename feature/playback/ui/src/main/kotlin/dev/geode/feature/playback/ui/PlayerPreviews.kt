package dev.geode.feature.playback.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.geode.design.element.ElementSkins
import dev.geode.design.element.ElementTheme

// The previews (ADR-0003), inside the :design theme: two Elements, so a preview shows the screen
// follows whichever is chosen.

@Composable
internal fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ElementTheme(if (dark) ElementSkins.Stone else ElementSkins.Water, content)
}

@Composable
internal fun PlayerPreview(
    model: PlayerModel,
    dark: Boolean = false,
) {
    PreviewTheme(dark) { PlayerScreen(model, onAction = {}) }
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

// The A-B loop on the seek bar (#147).

@Preview(name = "A-B loop")
@Composable
internal fun PlayerLoopingPreview() = PlayerPreview(PreviewModels.looping)

@Preview(name = "Mini-player, playing")
@Composable
internal fun MiniPlayerPlayingPreview() = MiniPlayerPreview(PreviewModels.playing)

@Preview(name = "Mini-player, paused, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun MiniPlayerPausedDarkPreview() = MiniPlayerPreview(PreviewModels.paused, dark = true)
