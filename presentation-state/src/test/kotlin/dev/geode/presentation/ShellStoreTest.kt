package dev.geode.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShellStoreTest {
    private val preferences = ShellPreferences(false, false, false, true, true, GeodeDestination.PLAYER)
    private val safety = ShellReducer.reduce(ShellState(), ShellAction.Configure(preferences, false)).state

    @Test
    fun `queued duplicate commands execute persistence only once`() =
        runTest {
            val releaseWrite = CompletableDeferred<Unit>()
            var writes = 0
            val store =
                ShellStore(backgroundScope, safety, { preferences }) {
                    writes++
                    releaseWrite.await()
                }
            store.dispatch(ShellAction.AcknowledgeSafety)
            store.dispatch(ShellAction.AcknowledgeSafety)
            runCurrent()
            assertEquals(EntryStage.SAFETY, store.state.value.stage)
            releaseWrite.complete(Unit)
            runCurrent()
            assertEquals(1, writes)
            assertEquals(EntryStage.SETUP, store.state.value.stage)
        }

    @Test
    fun `failed effect preserves previous state and can be explicitly retried`() =
        runTest {
            var fail = true
            val store = ShellStore(backgroundScope, safety, { preferences }) { check(!fail) }
            store.dispatch(ShellAction.AcknowledgeSafety)
            runCurrent()
            assertTrue(store.effectFailed.value)
            assertEquals(safety, store.state.value)
            fail = false
            store.dismissFailure()
            store.dispatch(ShellAction.AcknowledgeSafety)
            runCurrent()
            assertFalse(store.effectFailed.value)
            assertEquals(EntryStage.SETUP, store.state.value.stage)
        }

    @Test
    fun `obsolete preference notifications are resolved when consumed`() =
        runTest {
            val current = preferences.copy(safetyAcknowledged = true, setupDone = true, tutorialSeen = true)
            val store = ShellStore(backgroundScope, ShellState(), { current }) { error("No write expected") }
            store.dispatch(ShellAction.Configure(preferences, false))
            runCurrent()
            assertEquals(EntryStage.READY, store.state.value.stage)
        }

    @Test
    fun `scope cancellation does not report a failed user action`() =
        runTest {
            val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
            val store = ShellStore(scope, safety, { preferences }) { awaitCancellation() }
            store.dispatch(ShellAction.AcknowledgeSafety)
            runCurrent()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            runCurrent()
            assertFalse(store.effectFailed.value)
            assertEquals(safety, store.state.value)
        }
}
