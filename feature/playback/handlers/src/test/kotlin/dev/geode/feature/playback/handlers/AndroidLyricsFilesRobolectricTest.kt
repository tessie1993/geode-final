package dev.geode.feature.playback.handlers

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.LyricsSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Lyrics are found where the old player found them (#147): an `.lrc` file with the track's name beside it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AndroidLyricsFilesRobolectricTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val files = AndroidLyricsFiles(ApplicationProvider.getApplicationContext())

    @Test
    fun `an lrc file with the track's name beside it is found`() =
        runBlocking {
            val song = folder.newFile("Night Drive.mp3")
            File(folder.root, "Night Drive.lrc").writeText("[00:01.00]Hello")

            val found = files.find(TrackId(Uri.fromFile(song).toString()))

            assertEquals(LyricsText("[00:01.00]Hello", LyricsSource.LRC_FILE), found.first())
        }

    @Test
    fun `a track with nothing beside it and no readable tags has no lyrics`() =
        runBlocking {
            val song = folder.newFile("Quiet.mp3")
            File(folder.root, "Other.lrc").writeText("[00:01.00]Not this one")

            assertEquals(emptyList<LyricsText>(), files.find(TrackId(Uri.fromFile(song).toString())))
            assertEquals(emptyList<LyricsText>(), files.find(TrackId("unknown://track")))
        }
}
