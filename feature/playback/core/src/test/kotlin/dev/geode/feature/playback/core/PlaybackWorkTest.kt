package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.MAX_CROSSFADE
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.kernel.Perform
import org.junit.Assert.assertEquals
import org.junit.Test

/** The work the reducer hands over carries exactly what the handlers read from it. */
class PlaybackWorkTest {
    private val track = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val mark = PlayMark(Millis(1_000), Millis(2_000), moving = true)

    @Test
    fun `engine steps carry the track, the mark and the position`() {
        val load = EngineStep.Load(track.id, mark)

        assertEquals(track.id, load.track)
        assertEquals(mark, load.from)
        assertEquals(Millis(4_000), EngineStep.Seek(Millis(4_000)).position)
        assertEquals(listOf(load, EngineStep.Play), PlaybackWork.Drive(listOf(load, EngineStep.Play)).steps)
    }

    @Test
    fun `storage work carries what is saved and read`() {
        val queue = Queue(listOf(track), 0)
        val session = PlaybackWork.SaveSession(queue, mark)
        val create = PlaybackWork.CreatePlaylist("Mix", listOf(track))

        assertEquals(track, PlaybackWork.RecordPlay(track).track)
        assertEquals(queue, session.queue)
        assertEquals(mark, session.mark)
        assertEquals(PlaylistId(3), PlaybackWork.OpenPlaylist(PlaylistId(3)).id)
        assertEquals("Mix", create.name)
        assertEquals(listOf(track), create.tracks)
        assertEquals(PlaylistId(4), PlaybackWork.DeletePlaylist(PlaylistId(4)).id)
    }

    @Test
    fun `the timers have the lengths the issue settled on`() {
        assertEquals(Millis(10_000), LOAD_TIMEOUT)
        assertEquals(Millis(1_500), BUFFERING_GRACE)
    }

    @Test
    fun `the listening tools have the limits the old player had`() {
        assertEquals(Millis(60_000), SLEEP_TICK)
        assertEquals(Millis(12_000), MAX_CROSSFADE)
        assertEquals(Millis(1_200_000), LONG_TRACK)
        assertEquals(Millis(10_000), RESUME_EDGE)
        assertEquals(Millis(1_000), MIN_LOOP)
        assertEquals(10, SIMILAR_BATCH)
    }

    @Test
    fun `a track exactly as long as a long track resumes, and one a moment shorter does not`() {
        val points = mapOf(TrackId("content://exact") to Millis(600_000), TrackId("content://short") to Millis(600_000))
        val exact = Track(TrackId("content://exact"), "Exact", "Ann", LONG_TRACK)
        val short = Track(TrackId("content://short"), "Short", "Ann", LONG_TRACK - Millis(1))
        val heard = PlayMark(Millis(700_000), Millis(1), moving = false)

        val kept =
            listOf(exact, short).map { track ->
                val before = PlaybackState(PlayerState.Playing(Queue(listOf(track), 0), heard), resumePoints = points)
                PlaybackReducer.reduce(before, PlaybackEvent.Command(PlaybackCommand.Pause)).effects
            }

        assertEquals(true, Perform(PlaybackWork.SaveResumePoint(exact, heard)) in kept[0])
        assertEquals(false, kept[1].any { (it as? Perform<*>)?.work is PlaybackWork.SaveResumePoint })
    }
}
