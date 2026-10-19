package dev.geode.presentation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestSearchTest {
    @Test
    fun `typing is debounced and previous results are immediately unselectable`() =
        runTest {
            val calls = mutableListOf<String>()
            val search =
                LatestSearch(backgroundScope) { query ->
                    calls.add(query)
                    query.uppercase()
                }
            search.request("first")
            runCurrent()
            advanceTimeBy(100)
            search.request("second")
            runCurrent()
            advanceTimeBy(250)
            runCurrent()
            assertEquals(listOf("second"), calls)
            assertEquals("SECOND", search.state.value.results)
            search.request("third")
            assertEquals(SearchPhase.LOADING, search.state.value.phase)
            assertNull(search.state.value.results)
        }

    @Test
    fun `new request cancels in-flight work and clear returns idle`() =
        runTest {
            var cancelled = false
            val search =
                LatestSearch(backgroundScope) { query ->
                    if (query == "slow") {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    }
                    query
                }
            search.request("slow")
            runCurrent()
            advanceTimeBy(250)
            runCurrent()
            search.request("fast")
            runCurrent()
            assertTrue(cancelled)
            advanceTimeBy(250)
            runCurrent()
            assertEquals("fast", search.state.value.results)
            search.request(" ")
            assertEquals(SearchPhase.IDLE, search.state.value.phase)
            assertNull(search.state.value.results)
        }

    @Test
    fun `late completion and failure cannot overwrite newer request`() {
        val first = SearchReducer.reduce(SearchState<String>(), SearchAction.Request("first"))
        val second = SearchReducer.reduce(first, SearchAction.Request("second"))
        assertEquals(second, SearchReducer.reduce(second, SearchAction.Complete(first.revision, "obsolete")))
        assertEquals(second, SearchReducer.reduce(second, SearchAction.Fail(first.revision)))
    }

    @Test
    fun `failed requests can retry and source refresh invalidates results`() =
        runTest {
            var fail = true
            val search =
                LatestSearch(backgroundScope) { query ->
                    delay(1)
                    check(!fail)
                    query
                }
            search.request("track")
            runCurrent()
            advanceTimeBy(252)
            runCurrent()
            assertEquals(SearchPhase.FAILED, search.state.value.phase)
            fail = false
            search.refresh()
            assertEquals(SearchPhase.LOADING, search.state.value.phase)
            runCurrent()
            advanceTimeBy(252)
            runCurrent()
            assertEquals(SearchPhase.READY, search.state.value.phase)
        }
}
