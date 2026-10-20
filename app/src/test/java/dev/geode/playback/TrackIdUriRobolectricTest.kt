package dev.geode.playback

import android.app.Application
import android.net.Uri
import dev.geode.engine.types.TrackId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A track id is stored as text and handed to Media3 and the ContentResolver as a Uri, so the
 * trip between the two must give back the same string, escapes and all.
 *
 * Robolectric, because the android.jar stub's Uri parses nothing. The plain Application keeps it
 * away from GeodeApp, which loads the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TrackIdUriRobolectricTest {
    @Test
    fun `a media store id becomes the uri it names`() {
        val uri = TrackId("content://media/external/audio/media/42").toUri()

        assertEquals("content", uri.scheme)
        assertEquals("media", uri.authority)
        assertEquals("42", uri.lastPathSegment)
    }

    @Test
    fun `a uri becomes the id of its string form`() {
        assertEquals(
            TrackId("content://media/external/audio/media/42"),
            Uri.parse("content://media/external/audio/media/42").toTrackId(),
        )
    }

    @Test
    fun `an escaped document uri survives the round trip unchanged`() {
        val raw = "content://com.android.externalstorage.documents/document/primary%3AMusic%2FA%20B.flac"

        assertEquals(TrackId(raw), TrackId(raw).toUri().toTrackId())
        assertEquals("primary:Music/A B.flac", TrackId(raw).toUri().lastPathSegment)
    }

    @Test
    fun `a file path survives the round trip unchanged`() {
        val raw = "file:///storage/emulated/0/Music/song.mp3"

        assertEquals(TrackId(raw), TrackId(raw).toUri().toTrackId())
    }
}
