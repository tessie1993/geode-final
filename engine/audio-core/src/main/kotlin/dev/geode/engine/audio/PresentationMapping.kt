package dev.geode.engine.audio

import dev.geode.engine.types.Frames
import dev.geode.engine.types.Micros

sealed interface PresentationTime {
    data class At(
        val us: Micros,
    ) : PresentationTime

    data class Skipped(
        val fromInputSample: Frames,
        val toInputSample: Frames,
    ) : PresentationTime

    data class StaleEpoch(
        val asked: Int,
        val current: Int,
    ) : PresentationTime

    data object Unknown : PresentationTime
}

sealed interface InputPosition {
    data class At(
        val inputSample: Frames,
        val epoch: Int,
    ) : InputPosition

    data object Unknown : InputPosition
}
