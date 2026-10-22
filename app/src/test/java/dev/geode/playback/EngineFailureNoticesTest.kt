package dev.geode.playback

import dev.geode.R
import dev.geode.engine.bridge.EngineFailure
import dev.geode.engine.bridge.FailureRelay
import dev.geode.engine.bridge.NativeStatus
import dev.geode.kernel.ErrorChannel
import dev.geode.kernel.FeatureFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineFailureNoticesTest {
    private val relay = FailureRelay()

    @Test
    fun `the banner names the part of the engine that failed`() {
        assertEquals(R.string.engine_failure_audio, EngineFailureNotices.messageFor(EngineFailure.EngineCrashed("playerPlay")))
        assertEquals(R.string.engine_failure_visual, EngineFailureNotices.messageFor(EngineFailure.EngineCrashed("vizRender")))
        assertEquals(R.string.engine_failure_other, EngineFailureNotices.messageFor(EngineFailure.EngineCrashed("tagsRead")))
    }

    @Test
    fun `each failure reaches the banner until the notices close`() {
        val shown = mutableListOf<Int>()
        val notices = EngineFailureNotices(show = { shown += it }, failures = relay)
        relay.caught(NativeStatus.ENGINE_FAILURE.code, "playerPlay")
        notices.close()
        relay.caught(NativeStatus.ENGINE_FAILURE.code, "vizRender")
        assertEquals(listOf(R.string.engine_failure_audio), shown)
    }

    @Test
    fun `failures reach the app-wide error channel as the engine feature`() {
        val logged = mutableListOf<FeatureFailure>()
        val errors = ErrorChannel { logged += it }
        val reporting = EngineFailureNotices.reportTo(errors, relay)
        relay.caught(NativeStatus.OUT_OF_MEMORY.code, "dspProcess")
        reporting.close()
        relay.caught(NativeStatus.OUT_OF_MEMORY.code, "dspProcess")
        val expected = EngineFailure.OutOfMemory("dspProcess")
        assertEquals(listOf(FeatureFailure("engine", expected.toString())), logged)
        assertEquals(FeatureFailure(EngineFailureNotices.FEATURE, expected.toString()), EngineFailureNotices.featureFailure(expected))
    }
}
