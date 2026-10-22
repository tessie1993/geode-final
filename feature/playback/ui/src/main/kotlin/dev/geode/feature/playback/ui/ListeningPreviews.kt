package dev.geode.feature.playback.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.AbLoop
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.SleepTimer

@Composable
internal fun QueuePreview(
    model: QueueModel,
    dark: Boolean = false,
) {
    PreviewTheme(dark) { QueueScreen(model, onAction = {}) }
}

@Composable
internal fun ListeningPreview(
    model: ListeningModel,
    dark: Boolean = false,
) {
    PreviewTheme(dark) { ListeningScreen(model, onAction = {}) }
}

@Preview(name = "Queue, shuffled, repeating")
@Composable
internal fun QueueShuffledPreview() = QueuePreview(ListeningPreviewModels.queue)

@Preview(name = "Queue, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun QueueDarkPreview() = QueuePreview(ListeningPreviewModels.queue.copy(shuffle = false, repeat = RepeatMode.ONE), dark = true)

@Preview(name = "Queue, empty")
@Composable
internal fun QueueEmptyPreview() = QueuePreview(ListeningPreviewModels.queueEmpty)

@Preview(name = "Listening tools")
@Composable
internal fun ListeningToolsPreview() = ListeningPreview(ListeningPreviewModels.tools)

@Preview(name = "Listening tools, in use")
@Composable
internal fun ListeningToolsInUsePreview() = ListeningPreview(ListeningPreviewModels.toolsInUse)

@Preview(name = "Listening tools, after this song, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ListeningToolsDarkPreview() =
    ListeningPreview(
        ListeningPreviewModels.toolsInUse.copy(
            sleep = SleepTimer.AfterTrack,
            loop = AbLoop.Starting(TrackId("content://preview/2"), Millis(50_000)),
        ),
        dark = true,
    )
