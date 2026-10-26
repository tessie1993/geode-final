package dev.geode.engine.audio

import dev.geode.engine.types.Millis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The feed's Kotlin side: who watches it, and what its sinks hand to the native ring. */
class VisualFeedTest {
    private class Recorder : VisualFeed.Backend {
        override val handle: Long = 42L
        val calls = mutableListOf<String>()
        val writes = mutableListOf<String>()

        override fun call(
            name: String,
            native: (Long) -> Int,
        ) {
            calls += name
        }

        override fun write(
            source: FeedSource,
            interleaved: FloatArray,
            frames: Int,
            channels: Int,
            rateHz: Int,
        ) {
            writes += "$source $frames x $channels @ $rateHz"
        }
    }

    @Test
    fun `the analysis runs from the first watcher to the last`() {
        val recorder = Recorder()
        val feed = VisualFeed(recorder)
        assertFalse(feed.watched)
        feed.acquire()
        feed.acquire()
        assertTrue(feed.watched)
        feed.release()
        assertTrue(feed.watched)
        feed.release()
        assertFalse(feed.watched)
        assertEquals(listOf("feedSetActive", "feedSetActive"), recorder.calls)
    }

    @Test
    fun `a release with nobody watching changes nothing`() {
        val recorder = Recorder()
        val feed = VisualFeed(recorder)
        feed.release()
        assertFalse(feed.watched)
        feed.acquire()
        assertTrue(feed.watched)
        assertEquals(listOf("feedSetActive"), recorder.calls)
    }

    @Test
    fun `a sink writes only once it knows its rate`() {
        val recorder = Recorder()
        val sink = VisualFeed(recorder).sink(FeedSource.LIVE)
        sink.write(FloatArray(8), 4, 2)
        sink.rateHz = 44_100
        sink.write(FloatArray(8), 4, 2)
        sink.write(FloatArray(8), 0, 2)
        sink.write(FloatArray(8), 4, 0)
        assertEquals(listOf("LIVE 4 x 2 @ 44100"), recorder.writes)
        assertEquals(FeedSource.LIVE, sink.source)
    }

    @Test
    fun `control calls reach the native feed`() {
        val recorder = Recorder()
        val feed = VisualFeed(recorder)
        feed.select(FeedSource.PLAYER)
        feed.flush(FeedSource.PLAYER)
        feed.setRunning(FeedSource.PLAYER, false)
        feed.setPresentation(FeedSource.PLAYER, 500_000L, 1f)
        feed.setRoute(OutputRoute.BLUETOOTH)
        feed.setSyncOffset(Millis(-40))
        feed.setSensitivity(6f)
        feed.setTuning(3f, 333f, 0.01f, 0.1f)
        feed.resetAnalysis()
        feed.attachSnapshots(null)
        assertEquals(
            listOf(
                "feedSelect",
                "feedFlush",
                "feedSetRunning",
                "feedSetPresentation",
                "feedSetRoute",
                "feedSetUserOffset",
                "feedSetGain",
                "feedSetTuning",
                "feedResetAnalysis",
                "feedAttachSnapshots",
            ),
            recorder.calls,
        )
        assertEquals(42L, feed.handle)
    }

    @Test
    fun `source codes match the native feed's`() {
        assertEquals(1, FeedSource.PLAYER.code)
        assertEquals(2, FeedSource.LIVE.code)
    }

    @Test
    fun `route codes match the native feed's`() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6),
            listOf(
                OutputRoute.UNKNOWN,
                OutputRoute.SPEAKER,
                OutputRoute.WIRED,
                OutputRoute.USB,
                OutputRoute.BLUETOOTH,
                OutputRoute.BLUETOOTH_LE,
                OutputRoute.HDMI,
            ).map { it.code },
        )
    }
}
