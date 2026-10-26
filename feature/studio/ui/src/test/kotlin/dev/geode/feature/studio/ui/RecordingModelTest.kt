package dev.geode.feature.studio.ui

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.RecordingState
import dev.geode.feature.studio.api.VideoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Record control as the live Visual draws it (#317). */
class RecordingModelTest {
    @Test
    fun `idle offers Record, starting waits`() {
        assertEquals(
            RecordControlModel(recording = false, busy = false, elapsed = "0:00", lowStorage = false),
            recordControlModel(RecordingState.Idle),
        )
        assertEquals(
            RecordControlModel(recording = false, busy = true, elapsed = "0:00", lowStorage = false),
            recordControlModel(RecordingState.Starting),
        )
    }

    @Test
    fun `recording shows the time recorded, the storage warning, and waits while it finishes`() {
        assertEquals(
            RecordControlModel(recording = true, busy = false, elapsed = "1:05", lowStorage = true),
            recordControlModel(RecordingState.Recording(Millis(65_400), lowStorage = true)),
        )
        assertEquals(
            RecordControlModel(recording = true, busy = true, elapsed = "30:00", lowStorage = false),
            recordControlModel(RecordingState.Recording(RecordingState.LIMIT, stopping = true)),
        )
    }

    @Test
    fun `no control while a Recording waits for its Project`() {
        val video = NewVideo(VideoKind.RECORDING, "Take", "content://media/video/1", null, Millis(1_000), 1080, 2400, "")
        assertNull(recordControlModel(RecordingState.ChooseProject(video)))
    }

    @Test
    fun `the clock shows minutes and seconds, and hours from one`() {
        assertEquals("0:00", clockOf(Millis(999)))
        assertEquals("0:59", clockOf(Millis(59_999)))
        assertEquals("1:00", clockOf(Millis(60_000)))
        assertEquals("59:59", clockOf(Millis(3_599_000)))
        assertEquals("1:00:00", clockOf(Millis(3_600_000)))
        assertEquals("1:01:01", clockOf(Millis(3_661_000)))
        assertEquals("0:00", clockOf(Millis(-5_000)))
    }
}
