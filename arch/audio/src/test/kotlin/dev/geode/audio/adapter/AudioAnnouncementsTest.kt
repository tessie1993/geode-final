package dev.geode.audio.adapter

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Playback's position between announcements comes from the clock, not from polling the player
 * (ADR-0004); and the fake adapter announces what a test asks for, and a successful start, exactly
 * as the real one would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioAnnouncementsTest {
    @Test
    fun `a moving mark advances with the clock`() {
        val mark = PlaybackMark(position = Millis(10_000), at = Millis(500_000), moving = true)

        assertEquals(Millis(10_000), mark.positionAt(Millis(500_000)))
        assertEquals(Millis(12_500), mark.positionAt(Millis(502_500)))
    }

    @Test
    fun `a stopped mark stays where it is`() {
        val mark = PlaybackMark(position = Millis(10_000), at = Millis(500_000), moving = false)

        assertEquals(Millis(10_000), mark.positionAt(Millis(560_000)))
    }

    @Test
    fun `a collector hears every announcement with what it carries`() =
        runTest {
            val audio = FakeAudioAdapter()
            val heard = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                audio.announcements.collect { heard += describe(it) }
            }
            val mark = PlaybackMark(Millis(1_000), Millis(2_000), moving = true)

            audio.announce(PlaybackAnnouncement.TrackChanged(TrackId("content://a"), mark))
            audio.announce(PlaybackAnnouncement.Started(mark))
            audio.announce(PlaybackAnnouncement.Seeked(mark.copy(position = Millis(30_000))))
            audio.announce(PlaybackAnnouncement.Paused(mark.copy(moving = false)))
            audio.announce(PlaybackAnnouncement.Ended)
            audio.announce(PlaybackAnnouncement.Failed("ERROR_CODE_IO_FILE_NOT_FOUND"))
            audio.announce(CaptureAnnouncement.QuietChanged(quiet = true))
            audio.announce(CaptureAnnouncement.Lost(CaptureSource.MICROPHONE))

            assertEquals(
                listOf(
                    "track content://a at 1000ms",
                    "started at 1000ms on 2000ms",
                    "seeked to 30000ms",
                    "paused at 1000ms, moving false",
                    "ended",
                    "failed: ERROR_CODE_IO_FILE_NOT_FOUND",
                    "quiet true",
                    "lost MICROPHONE",
                ),
                heard,
            )
        }

    @Test
    fun `a start that succeeds announces the running capture, and a refused one does not`() =
        runTest {
            val audio = FakeAudioAdapter()
            val heard = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                audio.announcements.collect { heard += describe(it) }
            }

            audio.refuseNextStart = CaptureFailure.PERMISSION
            assertEquals(CaptureFailure.PERMISSION, audio.startCapture(CaptureSource.MICROPHONE))
            assertEquals(null, audio.startCapture(CaptureSource.MICROPHONE))
            audio.sources = emptySet()
            assertEquals(CaptureFailure.UNAVAILABLE, audio.startCapture(CaptureSource.MICROPHONE))

            assertEquals(listOf("running MICROPHONE"), heard)
            assertEquals(emptySet<CaptureSource>(), audio.captureSources())
        }

    private fun describe(announcement: AudioAnnouncement): String =
        when (announcement) {
            is PlaybackAnnouncement.TrackChanged -> "track ${announcement.track} at ${announcement.mark.position}"
            is PlaybackAnnouncement.Started -> "started at ${announcement.mark.position} on ${announcement.mark.at}"
            is PlaybackAnnouncement.Seeked -> "seeked to ${announcement.mark.position}"
            is PlaybackAnnouncement.Paused -> "paused at ${announcement.mark.position}, moving ${announcement.mark.moving}"
            PlaybackAnnouncement.Ended -> "ended"
            is PlaybackAnnouncement.Failed -> "failed: ${announcement.reason}"
            is CaptureAnnouncement.Running -> "running ${announcement.source}"
            is CaptureAnnouncement.QuietChanged -> "quiet ${announcement.quiet}"
            is CaptureAnnouncement.Lost -> "lost ${announcement.source}"
        }
}
