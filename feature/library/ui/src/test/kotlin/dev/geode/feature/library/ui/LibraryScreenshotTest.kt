package dev.geode.feature.library.ui

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

/** App UI screenshots of the Library, taken from its previews (ADR-0003). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class LibraryScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun songs() = capture("Library_songs") { LibrarySongsPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun songsDark() = capture("Library_songs_dark") { LibrarySongsDarkPreview() }

    @Test
    fun filtered() = capture("Library_sorted_and_filtered") { LibraryFilteredPreview() }

    @Test
    fun albums() = capture("Library_albums") { LibraryAlbumsPreview() }

    @Test
    fun album() = capture("Library_album") { LibraryAlbumPreview() }

    @Test
    fun folders() = capture("Library_folders") { LibraryFoldersPreview() }

    @Test
    fun playlists() = capture("Library_playlists") { LibraryPlaylistsPreview() }

    @Test
    fun smartPlaylist() = capture("Library_smart_playlist") { LibrarySmartPlaylistPreview() }

    @Test
    fun emptySmartPlaylist() = capture("Library_smart_playlist_empty") { LibraryEmptySmartPlaylistPreview() }

    @Test
    fun search() = capture("Library_search") { LibrarySearchPreview() }

    @Test
    fun nothingFound() = capture("Library_nothing_found") { LibraryNothingFoundPreview() }

    @Test
    fun scanning() = capture("Library_scanning") { LibraryScanningPreview() }

    @Test
    fun needsPermission() = capture("Library_needs_permission") { LibraryNeedsPermissionPreview() }

    @Test
    fun songEditor() = capture("SongEditor") { SongEditorPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun songEditorDark() = capture("SongEditor_chosen_art_dark") { SongEditorDarkPreview() }

    @Test
    fun smartPlaylistEditor() = capture("SmartPlaylistEditor") { SmartPlaylistEditorPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun smartPlaylistEditorDark() = capture("SmartPlaylistEditor_new_name_taken_dark") { SmartPlaylistEditorDarkPreview() }

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent(content)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
