package dev.geode.feature.settings.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.feature.settings.api.FolderCommand
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.Settings

/**
 * The system folder picker. Every folder Settings keeps is read or written long after it is picked,
 * so the read and write grant is persisted before [onPicked] gets its tree uri.
 */
@Composable
fun rememberFolderPicker(onPicked: (FolderUri) -> Unit): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    resolver.takePersistableUriPermission(uri, readWrite)
                }
                onPicked(FolderUri(uri.toString()))
            }
        }
    return { picker.launch(null) }
}

/** Which folder the open picker is for. */
private enum class FolderPick {
    VIDEO,
    PRESET_MIRROR,
    PRESET_FOLDER,
    MUSIC,
}

/**
 * Settings' place (ADR-0003): the only part that touches the Settings store and the system folder
 * picker. Which section is open is App UI state kept here. [fontColors], [appVersion] and the looks Geode
 * can start on ([startLooks], #383) come from the app; [onStartTutorial] replays the intro tour and [onSetLiveWallpaper] opens the system's wallpaper
 * picker. [helpExtra] is drawn at the end of the Help section, for what other features add there (Diagnostics).
 */
@Composable
fun SettingsRoute(
    settings: Settings,
    fontColors: List<FontColorChoice>,
    appVersion: String,
    onStartTutorial: () -> Unit,
    onSetLiveWallpaper: () -> Unit,
    modifier: Modifier = Modifier,
    startLooks: List<StartLookChoice> = emptyList(),
    helpExtra: @Composable () -> Unit = {},
) {
    val state by settings.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(SettingsSection.THEME) }
    var picking by rememberSaveable { mutableStateOf(FolderPick.VIDEO) }
    val pick =
        rememberFolderPicker { folder ->
            settings.send(
                when (picking) {
                    FolderPick.VIDEO -> FolderCommand.SetVideoFolder(folder)
                    FolderPick.PRESET_MIRROR -> FolderCommand.SetPresetMirror(folder)
                    FolderPick.PRESET_FOLDER -> FolderCommand.SetPresetFolder(folder)
                    FolderPick.MUSIC -> FolderCommand.AddMusicFolder(folder)
                },
            )
        }
    val pickFor: (FolderPick) -> Unit = {
        picking = it
        pick()
    }
    SettingsScreen(
        SettingsScreenModel.of(state, section, fontColors, appVersion, startLooks),
        onAction = { action ->
            when (action) {
                is SettingsAction.Change -> settings.send(action.command)
                is SettingsAction.Show -> section = action.section
                SettingsAction.PickVideoFolder -> pickFor(FolderPick.VIDEO)
                SettingsAction.PickPresetMirror -> pickFor(FolderPick.PRESET_MIRROR)
                SettingsAction.PickPresetFolder -> pickFor(FolderPick.PRESET_FOLDER)
                SettingsAction.PickMusicFolder -> pickFor(FolderPick.MUSIC)
                is SettingsAction.RemoveMusicFolder -> settings.send(FolderCommand.RemoveMusicFolder(action.folder))
                SettingsAction.StartTutorial -> onStartTutorial()
                SettingsAction.SetLiveWallpaper -> onSetLiveWallpaper()
            }
        },
        modifier = modifier,
        helpExtra = helpExtra,
    )
}
