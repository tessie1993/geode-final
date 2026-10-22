package dev.geode.engine.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureRelayTest {
    private val relay = FailureRelay()
    private val heard = mutableListOf<EngineFailure>()

    init {
        relay.listen { heard += it }
    }

    @Test
    fun `an OK status is no failure and reports nothing`() {
        assertNull(relay.check(NativeStatus.OK.code, "vizRender"))
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a refused call is reported and returned`() {
        val failure = relay.check(NativeStatus.BUSY.code, "snapshotsPublish")
        assertEquals(EngineFailure.SnapshotRefused("snapshotsPublish", NativeStatus.BUSY), failure)
        assertEquals(listOf(failure), heard)
    }

    @Test
    fun `a throw the bridge already reported is returned but not reported twice`() {
        assertEquals(EngineFailure.EngineCrashed("playerPlay"), relay.check(NativeStatus.ENGINE_FAILURE.code, "playerPlay"))
        assertEquals(EngineFailure.OutOfMemory("playerPlay"), relay.check(NativeStatus.OUT_OF_MEMORY.code, "playerPlay"))
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a caught throw is always reported`() {
        relay.caught(NativeStatus.ENGINE_FAILURE.code, "playerPlay")
        relay.caught(NativeStatus.INVALID_ARGUMENT.code, "vizSetLfo")
        assertEquals(
            listOf(
                EngineFailure.EngineCrashed("playerPlay"),
                EngineFailure.InvalidCall("vizSetLfo", NativeStatus.INVALID_ARGUMENT),
            ),
            heard,
        )
    }

    @Test
    fun `a caught throw that claims OK still counts as a crash`() {
        relay.caught(NativeStatus.OK.code, "dspProcess")
        assertEquals(listOf(EngineFailure.EngineCrashed("dspProcess")), heard)
    }

    @Test
    fun `every listener hears each failure until it stops listening`() {
        val second = mutableListOf<EngineFailure>()
        val subscription = relay.listen { second += it }
        relay.caught(NativeStatus.ENGINE_FAILURE.code, "vizRender")
        subscription.close()
        relay.caught(NativeStatus.ENGINE_FAILURE.code, "vizCut")
        assertEquals(listOf(EngineFailure.EngineCrashed("vizRender")), second)
        assertEquals(2, heard.size)
    }

    @Test
    fun `the native bridge reports through NativeFailures`() {
        val fromNative = mutableListOf<EngineFailure>()
        NativeFailures.listen { fromNative += it }.use {
            NativeFailures.onCaught(NativeStatus.OUT_OF_MEMORY.code, "playerOpen")
        }
        NativeFailures.onCaught(NativeStatus.ENGINE_FAILURE.code, "playerOpen")
        assertEquals(listOf(EngineFailure.OutOfMemory("playerOpen")), fromNative)
    }
}
