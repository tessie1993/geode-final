package dev.geode.engine.audioandroid

// The two `fun interface` hooks below stay on raw frame counts: Android lint crashes analysing SAM
// conversions whose method signature carries a value class. Callers wrap into `Frames` at once.
fun interface SkippedFrameSource {
    fun skippedInputFramesSinceFlush(): Long
}

interface SinkClockHooks {
    fun onSpeedApplied(speed: Float)

    fun onSkipSilenceApplied(enabled: Boolean)

    fun attachSkippedFrames(source: SkippedFrameSource)

    data object None : SinkClockHooks {
        override fun onSpeedApplied(speed: Float) = Unit

        override fun onSkipSilenceApplied(enabled: Boolean) = Unit

        override fun attachSkippedFrames(source: SkippedFrameSource) = Unit
    }
}

fun interface TapBoundaryListener {
    fun onTapBoundary(
        ended: PcmTapFormat?,
        endedFrames: Long,
        begun: PcmTapFormat,
    )
}

data class SinkClockDiagnostics(
    val boundaries: Long = 0,
    val segmentsAppended: Long = 0,
    val hookedBoundaries: Long = 0,
    val refusedSpeedNotAuthoritative: Long = 0,
    val refusedUnreadableFormat: Long = 0,
    val refusedUntrustedAnchor: Long = 0,
    val discardedSkipExceedingFrames: Long = 0,
    val refusedByClockInvariant: Long = 0,
    val unmeasuredBoundaries: Long = 0,
    val skippedFramesAttached: Boolean = false,
    val anchorTrusted: Boolean = true,
)
