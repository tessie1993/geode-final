package dev.geode.engine.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotPublisherTest {
    private val sent = mutableListOf<ByteArray>()
    private var answer = NativeStatus.OK
    private val relay = FailureRelay()
    private val heard = mutableListOf<EngineFailure>()
    private val publisher =
        SnapshotPublisher(
            publish = { wire ->
                sent += wire
                answer.code
            },
            failures = relay,
        )

    init {
        relay.listen { heard += it }
    }

    private fun snapshot(revision: Int): CustomizationSnapshot =
        CustomizationSnapshot(
            revision = revision,
            reducedMotion = false,
            params = List(CustomizationSnapshot.PARAMS_COUNT) { 0f },
            lfo = List(CustomizationSnapshot.LFO_COUNT) { 0f },
            adsr = List(CustomizationSnapshot.ADSR_COUNT) { 0f },
            plan = List(CustomizationSnapshot.PLAN_COUNT) { 0f },
            soundSensitivity = 1f,
            soundRefractoryMs = 100f,
            soundAttackSeconds = 0.01f,
            soundReleaseSeconds = 0.2f,
        )

    @Test
    fun `the first snapshot crosses the bridge in the schema's wire format`() {
        assertNull(publisher.offer(snapshot(1)))
        assertEquals(1, sent.size)
        assertArrayEquals(snapshot(1).encode(), sent.single())
        assertEquals(snapshot(1), publisher.accepted())
    }

    @Test
    fun `an unchanged snapshot does not cross again`() {
        publisher.offer(snapshot(1))
        assertNull(publisher.offer(snapshot(1)))
        assertEquals(1, sent.size)
    }

    @Test
    fun `a changed snapshot crosses`() {
        publisher.offer(snapshot(1))
        publisher.offer(snapshot(2))
        assertEquals(2, sent.size)
        assertEquals(snapshot(2), publisher.accepted())
    }

    @Test
    fun `a refused snapshot is reported and offered again next time`() {
        answer = NativeStatus.BUSY
        val failure = publisher.offer(snapshot(1))
        assertEquals(EngineFailure.SnapshotRefused("snapshotsPublish", NativeStatus.BUSY), failure)
        assertEquals(listOf(failure), heard)
        assertNull(publisher.accepted())
        answer = NativeStatus.OK
        assertNull(publisher.offer(snapshot(1)))
        assertEquals(2, sent.size)
        assertEquals(snapshot(1), publisher.accepted())
    }

    @Test
    fun `a refusal keeps the last snapshot the engine took`() {
        publisher.offer(snapshot(1))
        answer = NativeStatus.UNSUPPORTED_VERSION
        publisher.offer(snapshot(2))
        assertEquals(snapshot(1), publisher.accepted())
    }
}
