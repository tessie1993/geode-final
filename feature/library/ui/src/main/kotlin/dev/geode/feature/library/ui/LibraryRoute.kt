package dev.geode.feature.library.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.feature.library.api.Library
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.Pick

/** The file types offered when importing a playlist. */
private val PLAYLIST_TYPES =
    arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "*/*")

/** The type of the playlist files the Library writes. */
private const val M3U_TYPE = "audio/x-mpegurl"

/** The file types offered as cover art. */
private val IMAGE_TYPES = arrayOf("image/*")

private const val M3U_EXTENSION = ".m3u"

/** The file name suggested for an export with nothing better to go by. */
private const val DEFAULT_FILE_NAME = "playlist"

/**
 * The Library's place (ADR-0003): the only part that touches the Library store, the permission to read
 * the phone's music, and the file pickers for playlists and cover art. It reads the Library's state
 * and turns the screen's actions into Commands. `:app` wires it at the switch-over (#17); until then
 * the old Library screen stays in use.
 *
 * [readPermission] is the permission the phone's music needs (the handlers' MediaStoreScanner names it).
 */
@Composable
fun LibraryRoute(
    library: Library,
    readPermission: String,
    modifier: Modifier = Modifier,
) {
    val state by library.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    // Where the next export goes, and who gets the next picked image: App UI state while a picker is open.
    val exporting = remember { mutableStateOf<Pick?>(null) }
    val artworkPicked = remember { mutableStateOf<((String) -> Unit)?>(null) }

    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) library.send(LibraryCommand.Rescan)
        }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { library.send(LibraryCommand.ImportPlaylist(it.toString(), name = "")) }
        }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(M3U_TYPE)) { uri: Uri? ->
            val pick = exporting.value
            exporting.value = null
            if (uri != null && pick != null) library.send(LibraryCommand.ExportPlaylist(pick, uri.toString()))
        }
    // The phone asks the person before a file is deleted; once it is, the Library looks again.
    val deleter =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) library.send(LibraryCommand.Rescan)
        }
    val artwork =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val onPicked = artworkPicked.value
            artworkPicked.value = null
            if (uri != null && onPicked != null) {
                // Cover art is shown long after it is picked, so the read grant must outlive this session.
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                onPicked(uri.toString())
            }
        }

    LibraryScreen(
        state.toLibraryModel(),
        onAction = { action ->
            when (action) {
                is LibraryAction.Send -> library.send(action.command)
                LibraryAction.AskPermission -> permission.launch(readPermission)
                LibraryAction.ImportPlaylist -> importer.launch(PLAYLIST_TYPES)
                is LibraryAction.ExportPlaylist -> {
                    exporting.value = action.pick
                    exporter.launch(action.name.ifBlank { DEFAULT_FILE_NAME } + M3U_EXTENSION)
                }
                is LibraryAction.DeleteFile -> {
                    val request = MediaStore.createDeleteRequest(resolver, listOf(Uri.parse(action.song.value)))
                    deleter.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
                is LibraryAction.PickArtwork -> {
                    artworkPicked.value = action.onPicked
                    artwork.launch(IMAGE_TYPES)
                }
            }
        },
        modifier = modifier,
    )
}
