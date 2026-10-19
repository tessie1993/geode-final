package dev.geode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbLoopReducerTest {
    @Test fun firstPressStartsAtNonnegativePosition() {
        assertEquals(AbLoop(0L), AbLoopReducer.advance(null, -50L))
        assertEquals(AbLoop(500L), AbLoopReducer.advance(null, 500L))
    }

    @Test fun secondPressRequiresMoreThanOneSecond() {
        val a = AbLoop(1_000L)
        assertEquals(AbLoop(2_000L), AbLoopReducer.advance(a, 2_000L))
        assertEquals(AbLoop(2_001L), AbLoopReducer.advance(a, 2_001L))
    }

    @Test fun validSecondPressSetsEndAndThirdClears() {
        val complete = AbLoopReducer.advance(AbLoop(1_000L), 3_000L)
        assertEquals(AbLoop(1_000L, 3_000L), complete)
        assertNull(AbLoopReducer.advance(complete, 4_000L))
    }

    @Test fun backwardsPressRestartsStart() {
        assertEquals(AbLoop(100L), AbLoopReducer.advance(AbLoop(2_000L), 100L))
    }
}
