package dev.geode.feature.liveinput.ui

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.geode.feature.liveinput.api.CaptureCommand
import dev.geode.feature.liveinput.api.CaptureProblem
import dev.geode.feature.liveinput.api.CaptureSource
import dev.geode.feature.liveinput.api.CaptureState
import dev.geode.feature.liveinput.api.MusicChoice

/** What [CaptureScreen] shows: Capture's state, as its route reads it from Capture's `:api`. */
@Immutable
data class CaptureScreenModel(
    val state: CaptureState,
)

/** Everything [CaptureScreen] can ask for. Its route sends the commands and handles the rest. */
sealed interface CaptureAction {
    /** A command for Capture. */
    data class Send(
        val command: CaptureCommand,
    ) : CaptureAction

    /** Ask the system for the microphone permission; the route sends the answer to Capture. */
    data object AskForPermission : CaptureAction
}

/** Sizes of the Capture screen, in one place. */
private object CaptureDimens {
    val GUTTER = 16.dp
    val GAP = 12.dp
}

/**
 * The Capture screen (ADR-0003): stateless, it only shows [model] and reports taps to [onAction].
 * Starting a service, asking for a permission or talking to the audio is its route's business.
 */
@Composable
fun CaptureScreen(
    model: CaptureScreenModel,
    onAction: (CaptureAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val send = { command: CaptureCommand -> onAction(CaptureAction.Send(command)) }
    Column(
        modifier.padding(CaptureDimens.GUTTER),
        verticalArrangement = Arrangement.spacedBy(CaptureDimens.GAP),
    ) {
        Text(stringResource(R.string.capture_title), style = MaterialTheme.typography.headlineSmall)
        when (val state = model.state) {
            CaptureState.Off -> {
                Text(stringResource(R.string.capture_off_body))
                SourceButtons(selected = null) { send(CaptureCommand.Start(it)) }
            }
            is CaptureState.Preparing -> Waiting(stringResource(R.string.capture_preparing), send)
            is CaptureState.AskingAboutMusic -> {
                Text(stringResource(R.string.capture_music_question))
                Row(horizontalArrangement = Arrangement.spacedBy(CaptureDimens.GAP)) {
                    Button(onClick = { send(CaptureCommand.ChooseMusic(MusicChoice.PAUSE)) }) {
                        Text(stringResource(R.string.capture_music_pause))
                    }
                    OutlinedButton(onClick = { send(CaptureCommand.ChooseMusic(MusicChoice.KEEP_PLAYING)) }) {
                        Text(stringResource(R.string.capture_music_keep))
                    }
                }
                CancelButton(send)
            }
            is CaptureState.AwaitingPermission -> {
                Text(stringResource(R.string.capture_permission_body, inlineName(state.source)))
                Row(horizontalArrangement = Arrangement.spacedBy(CaptureDimens.GAP)) {
                    Button(onClick = { onAction(CaptureAction.AskForPermission) }) {
                        Text(stringResource(R.string.capture_permission_allow))
                    }
                    CancelButton(send)
                }
            }
            is CaptureState.Starting -> Waiting(stringResource(R.string.capture_starting, inlineName(state.source)), send)
            is CaptureState.Capturing -> {
                Text(stringResource(R.string.capture_capturing, inlineName(state.source)))
                if (state.quiet) Text(stringResource(R.string.capture_quiet), style = MaterialTheme.typography.bodySmall)
                SourceButtons(selected = state.source) { send(CaptureCommand.Start(it)) }
                Button(onClick = { send(CaptureCommand.Stop) }) { Text(stringResource(R.string.capture_stop)) }
            }
            is CaptureState.Failed -> {
                Text(stringResource(problemText(state.problem), inlineName(state.source)))
                Row(horizontalArrangement = Arrangement.spacedBy(CaptureDimens.GAP)) {
                    Button(onClick = { send(CaptureCommand.Stop) }) { Text(stringResource(R.string.capture_dismiss)) }
                    OutlinedButton(onClick = { send(CaptureCommand.Start(state.source)) }) {
                        Text(stringResource(R.string.capture_retry))
                    }
                }
            }
        }
    }
}

/** One button per source; the [selected] one is filled. */
@Composable
private fun SourceButtons(
    selected: CaptureSource?,
    onPick: (CaptureSource) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CaptureDimens.GAP)) {
        for (source in CaptureSource.entries) {
            val label = stringResource(sourceName(source))
            if (source == selected) {
                Button(onClick = { onPick(source) }) { Text(label) }
            } else {
                OutlinedButton(onClick = { onPick(source) }) { Text(label) }
            }
        }
    }
}

@Composable
private fun Waiting(
    text: String,
    send: (CaptureCommand) -> Unit,
) {
    Text(text)
    CancelButton(send)
}

@Composable
private fun CancelButton(send: (CaptureCommand) -> Unit) {
    TextButton(onClick = { send(CaptureCommand.Stop) }) { Text(stringResource(R.string.capture_cancel)) }
}

@StringRes
private fun sourceName(source: CaptureSource): Int =
    when (source) {
        CaptureSource.MICROPHONE -> R.string.capture_source_microphone
    }

@Composable
private fun inlineName(source: CaptureSource): String =
    stringResource(
        when (source) {
            CaptureSource.MICROPHONE -> R.string.capture_source_microphone_inline
        },
    )

@StringRes
private fun problemText(problem: CaptureProblem): Int =
    when (problem) {
        CaptureProblem.PERMISSION_DENIED -> R.string.capture_problem_permission
        CaptureProblem.SOURCE_UNAVAILABLE -> R.string.capture_problem_unavailable
        CaptureProblem.SOURCE_LOST -> R.string.capture_problem_lost
        CaptureProblem.DID_NOT_START -> R.string.capture_problem_did_not_start
    }

/** A preview of [state] in the app's light or dark scheme (ADR-0003). */
@Composable
internal fun CapturePreviewFrame(
    state: CaptureState,
    dark: Boolean = false,
) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface { CaptureScreen(CaptureScreenModel(state), onAction = {}) }
    }
}

@Preview(showBackground = true)
@Composable
internal fun CaptureOffPreview() = CapturePreviewFrame(CaptureState.Off)

@Preview(showBackground = true)
@Composable
internal fun CaptureAskingAboutMusicPreview() = CapturePreviewFrame(CaptureState.AskingAboutMusic(CaptureSource.MICROPHONE))

@Preview(showBackground = true)
@Composable
internal fun CaptureAwaitingPermissionPreview() =
    CapturePreviewFrame(CaptureState.AwaitingPermission(CaptureSource.MICROPHONE, MusicChoice.PAUSE))

@Preview(showBackground = true)
@Composable
internal fun CaptureStartingPreview() = CapturePreviewFrame(CaptureState.Starting(CaptureSource.MICROPHONE, MusicChoice.KEEP_PLAYING))

@Preview(showBackground = true)
@Composable
internal fun CaptureCapturingPreview() = CapturePreviewFrame(CaptureState.Capturing(CaptureSource.MICROPHONE, quiet = true))

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun CaptureCapturingDarkPreview() =
    CapturePreviewFrame(
        CaptureState.Capturing(CaptureSource.MICROPHONE, quiet = false),
        dark = true,
    )

@Preview(showBackground = true)
@Composable
internal fun CaptureFailedPreview() = CapturePreviewFrame(CaptureState.Failed(CaptureSource.MICROPHONE, CaptureProblem.SOURCE_LOST))
