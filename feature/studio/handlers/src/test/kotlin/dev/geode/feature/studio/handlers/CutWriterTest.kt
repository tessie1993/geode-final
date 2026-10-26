package dev.geode.feature.studio.handlers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Timeline's saves one at a time, the newest winning, whatever order they start in (#318). */
@OptIn(ExperimentalCoroutinesApi::class)
class CutWriterTest {
    private val written = mutableListOf<String>()

    @Test
    fun `saves in order are each written`() =
        runTest(UnconfinedTestDispatcher()) {
            val writer = CutWriter<String> { written += it }

            writer.offer(1, "a")
            writer.offer(2, "b")

            assertEquals(listOf("a", "b"), written)
        }

    @Test
    fun `an older save that starts late is never written over a newer one`() =
        runTest(UnconfinedTestDispatcher()) {
            val writer = CutWriter<String> { written += it }

            writer.offer(2, "newer")
            writer.offer(1, "older")

            assertEquals(listOf("newer"), written)
        }

    @Test
    fun `a save offered twice is written once`() =
        runTest(UnconfinedTestDispatcher()) {
            val writer = CutWriter<String> { written += it }

            writer.offer(3, "c")
            writer.offer(3, "c again")

            assertEquals(listOf("c"), written)
        }

    @Test
    fun `saves that pile up while one is written collapse into the newest`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val writer =
                CutWriter<String> {
                    if (it == "first") gate.await()
                    written += it
                }

            val first = launch { writer.offer(1, "first") }
            val newest = launch { writer.offer(3, "third") }
            val middle = launch { writer.offer(2, "second") }
            assertTrue(written.isEmpty())
            gate.complete(Unit)
            listOf(first, newest, middle).forEach { it.join() }

            assertEquals(listOf("first", "third"), written)
        }

    @Test
    fun `a failed write is its caller's to report, and the next save still lands`() =
        runTest(UnconfinedTestDispatcher()) {
            val writer =
                CutWriter<String> {
                    check(it != "broken") { "no room" }
                    written += it
                }

            val failed = runCatching { writer.offer(1, "broken") }
            writer.offer(2, "fine")

            assertTrue(failed.isFailure)
            assertEquals(listOf("fine"), written)
        }

    @Test
    fun `a read waits for the saves offered before it`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val writer =
                CutWriter<String> {
                    gate.await()
                    written += it
                }

            val saving = launch { writer.offer(1, "kept") }
            val read = async { writer.read { written.toList() } }
            assertTrue(read.isActive)
            gate.complete(Unit)
            saving.join()

            assertEquals(listOf("kept"), read.await())
        }
}
