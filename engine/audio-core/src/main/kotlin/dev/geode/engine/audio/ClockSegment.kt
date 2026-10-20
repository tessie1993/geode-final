package dev.geode.engine.audio

import dev.geode.engine.types.Frames
import dev.geode.engine.types.Hz
import dev.geode.engine.types.Micros

data class ClockSegment(
    val epoch: Int,
    val discontinuityGeneration: Int,
    val inputSampleStart: Frames,
    val presentationUsStart: Micros,
    val inputSamplesPerPresentationUs: Double,
    val speed: Float,
    val skippedInputSamples: Frames,
) {
    init {
        require(inputSamplesPerPresentationUs > 0.0) {
            "slope must be positive, was $inputSamplesPerPresentationUs"
        }
        require(inputSampleStart.value >= 0) { "inputSampleStart must not be negative" }
        require(skippedInputSamples.value >= 0) { "skippedInputSamples must not be negative" }
    }

    val firstPresentedSample: Frames get() = inputSampleStart + skippedInputSamples

    companion object {
        private const val US_PER_SECOND = 1_000_000.0

        fun fromFormat(
            epoch: Int,
            discontinuityGeneration: Int,
            inputSampleStart: Frames,
            presentationUsStart: Micros,
            sampleRate: Hz,
            speed: Float,
            skippedInputSamples: Frames = Frames(0L),
        ): ClockSegment {
            val sampleRateHz = sampleRate.value
            require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
            require(speed > 0f) { "speed must be positive, was $speed" }
            return ClockSegment(
                epoch = epoch,
                discontinuityGeneration = discontinuityGeneration,
                inputSampleStart = inputSampleStart,
                presentationUsStart = presentationUsStart,
                inputSamplesPerPresentationUs = sampleRateHz * speed / US_PER_SECOND,
                speed = speed,
                skippedInputSamples = skippedInputSamples,
            )
        }
    }
}
