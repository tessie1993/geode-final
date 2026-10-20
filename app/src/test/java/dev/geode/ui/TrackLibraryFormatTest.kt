package dev.geode.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * library.json predates TrackId, so the id must go to disk as the bare uri string and come back
 * from one: a file written by an older build has to read the same, and a newer build must not
 * write anything an older one cannot.
 */
class TrackLibraryFormatTest {
    private val uri = "content://media/external/audio/media/42"

    @Test
    fun `a track's id and duration are written as plain values`() {
        val track = LibraryTrack(uri = TrackId(uri), title = "Kyanite", duration = Millis(205_400L))
        val text = TrackLibrary.serialize(listOf(track))

        val written = JSONObject(text).getJSONArray("tracks").getJSONObject(0)
        assertEquals(uri, written.get("uri"))
        assertEquals("Kyanite", written.get("title"))
        assertEquals(205_400L, written.getLong("durationMs"))
    }

    @Test
    fun `a file written before the type existed reads back the same id`() {
        val old = """{"version":3,"tracks":[{"uri":"$uri","title":"Kyanite","durationMs":205400}]}"""

        val track = TrackLibrary.parse(old).single()
        assertEquals(TrackId(uri), track.uri)
        assertEquals(Millis(205_400L), track.duration)
    }

    @Test
    fun `a track with no file identity is keyed by its uri`() {
        assertEquals("uri:$uri", TrackLibrary.identityKey(LibraryTrack(uri = TrackId(uri), title = "Kyanite")))
    }
}
