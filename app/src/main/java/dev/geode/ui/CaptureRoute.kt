package dev.geode.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.feature.liveinput.api.Capture
import dev.geode.feature.liveinput.api.CaptureCommand
import dev.geode.feature.liveinput.ui.CaptureAction
import dev.geode.feature.liveinput.ui.CaptureScreen
import dev.geode.feature.liveinput.ui.CaptureScreenModel

/**
 * Capture's place as a sheet (ADR-0003): the only part that touches the Capture store and the
 * system's microphone permission. The answer to the permission request comes back to Capture as a
 * command, so the reducer decides what it means.
 */
@Composable
fun CaptureRoute(capture: Capture) {
    val state by capture.state.collectAsStateWithLifecycle()
    val askPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            capture.send(CaptureCommand.PermissionAnswered(granted))
        }
    CaptureScreen(
        CaptureScreenModel(state),
        onAction = { action ->
            when (action) {
                is CaptureAction.Send -> capture.send(action.command)
                CaptureAction.AskForPermission -> askPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    )
}
