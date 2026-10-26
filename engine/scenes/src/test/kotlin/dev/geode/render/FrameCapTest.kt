package dev.geode.render

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCapTest {
    @Test
    fun noCapLeavesTheRequestedRate() {
        assertEquals(60f, FrameCap.effectiveFps(requestedFps = 60f, capFps = 0f), 0f)
        assertEquals(0f, FrameCap.effectiveFps(requestedFps = 0f, capFps = 0f), 0f)
        assertEquals(60f, FrameCap.effectiveFps(requestedFps = 60f, capFps = -1f), 0f)
    }

    @Test
    fun aCapHoldsAFreeRunningPacerToIt() {
        assertEquals(30f, FrameCap.effectiveFps(requestedFps = 0f, capFps = 30f), 0f)
        assertEquals(30f, FrameCap.effectiveFps(requestedFps = -1f, capFps = 30f), 0f)
    }

    @Test
    fun aCapOnlyEverLowersTheRate() {
        assertEquals(30f, FrameCap.effectiveFps(requestedFps = 60f, capFps = 30f), 0f)
        assertEquals(24f, FrameCap.effectiveFps(requestedFps = 24f, capFps = 30f), 0f)
        assertEquals(30f, FrameCap.effectiveFps(requestedFps = 30f, capFps = 30f), 0f)
    }

    @Test
    fun theFrameBudgetIsOneFrameAtThePacedRate() {
        assertEquals(16_666_666L, FrameCap.frameBudgetNanos(60f))
        assertEquals(33_333_333L, FrameCap.frameBudgetNanos(30f))
        assertEquals(8_333_333L, FrameCap.frameBudgetNanos(120f))
    }

    @Test
    fun aFreeRunningPacerIsBudgetedAtTheDefaultRate() {
        assertEquals(FrameCap.frameBudgetNanos(FramePacer.DEFAULT_TARGET_FPS), FrameCap.frameBudgetNanos(0f))
        assertEquals(FrameCap.frameBudgetNanos(FramePacer.DEFAULT_TARGET_FPS), FrameCap.frameBudgetNanos(-5f))
    }
}
