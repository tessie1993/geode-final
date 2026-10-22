package dev.geode.engine.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineFailureTest {
    private fun failureFor(status: NativeStatus): EngineFailure? = EngineFailure.of(status.code, CALL)

    @Test
    fun `OK is no failure`() {
        assertNull(failureFor(NativeStatus.OK))
    }

    @Test
    fun `every other status maps to its named failure`() {
        assertEquals(EngineFailure.InvalidCall(CALL, NativeStatus.NULL_HANDLE), failureFor(NativeStatus.NULL_HANDLE))
        assertEquals(EngineFailure.InvalidCall(CALL, NativeStatus.INVALID_ARGUMENT), failureFor(NativeStatus.INVALID_ARGUMENT))
        assertEquals(EngineFailure.SnapshotRefused(CALL, NativeStatus.MALFORMED_SNAPSHOT), failureFor(NativeStatus.MALFORMED_SNAPSHOT))
        assertEquals(EngineFailure.SnapshotRefused(CALL, NativeStatus.UNSUPPORTED_VERSION), failureFor(NativeStatus.UNSUPPORTED_VERSION))
        assertEquals(EngineFailure.SnapshotRefused(CALL, NativeStatus.BUSY), failureFor(NativeStatus.BUSY))
        assertEquals(EngineFailure.OutOfMemory(CALL), failureFor(NativeStatus.OUT_OF_MEMORY))
        assertEquals(EngineFailure.EngineCrashed(CALL), failureFor(NativeStatus.ENGINE_FAILURE))
    }

    @Test
    fun `an unknown code is kept for the log`() {
        assertEquals(EngineFailure.UnknownStatus(CALL, UNKNOWN_CODE), EngineFailure.of(UNKNOWN_CODE, CALL))
    }

    @Test
    fun `only throws are caught at the bridge`() {
        assertTrue(EngineFailure.OutOfMemory(CALL).caughtAtTheBridge)
        assertTrue(EngineFailure.EngineCrashed(CALL).caughtAtTheBridge)
        assertFalse(EngineFailure.InvalidCall(CALL, NativeStatus.NULL_HANDLE).caughtAtTheBridge)
        assertFalse(EngineFailure.SnapshotRefused(CALL, NativeStatus.BUSY).caughtAtTheBridge)
        assertFalse(EngineFailure.UnknownStatus(CALL, UNKNOWN_CODE).caughtAtTheBridge)
    }

    @Test
    fun `the call's name says which subsystem failed`() {
        assertEquals(EngineSubsystem.AUDIO, EngineSubsystem.of("playerPlay"))
        assertEquals(EngineSubsystem.AUDIO, EngineSubsystem.of("dspProcess"))
        assertEquals(EngineSubsystem.ANALYSIS, EngineSubsystem.of("analysisPull"))
        assertEquals(EngineSubsystem.ANALYSIS, EngineSubsystem.of("drumsStep"))
        assertEquals(EngineSubsystem.ANALYSIS, EngineSubsystem.of("pulseReplay"))
        assertEquals(EngineSubsystem.VISUAL, EngineSubsystem.of("vizRender"))
        assertEquals(EngineSubsystem.LIBRARY, EngineSubsystem.of("tagsWrite"))
        assertEquals(EngineSubsystem.CUSTOMIZATION, EngineSubsystem.of("snapshotsPublish"))
        assertEquals(EngineSubsystem.ENGINE, EngineSubsystem.of("version"))
        assertEquals(EngineSubsystem.VISUAL, EngineFailure.EngineCrashed("vizSetParams").subsystem)
    }

    private companion object {
        const val CALL = "vizRender"
        const val UNKNOWN_CODE = 42
    }
}
