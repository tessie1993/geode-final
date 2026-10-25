package dev.geode.playback

import androidx.annotation.StringRes
import dev.geode.R
import dev.geode.engine.bridge.EngineFailure
import dev.geode.engine.bridge.EngineSubsystem
import dev.geode.engine.bridge.FailureRelay
import dev.geode.engine.bridge.NativeFailures
import dev.geode.kernel.ErrorChannel
import dev.geode.kernel.FeatureFailure

/**
 * Reports every failure the native bridge catches to the app's one [ErrorChannel] (ADR-0004), which
 * the App UI banner shows and the local log keeps. Audio and the Visual keep running. There is no
 * second banner for engine failures: [reportTo] is the only path, for the life of the process.
 */
object EngineFailureNotices {
    /** The feature every engine failure is filed under on the app-wide error channel. */
    const val FEATURE = "engine"

    /** [failure] as the app-wide error channel files it, in the caller's words. */
    fun featureFailure(
        failure: EngineFailure,
        describe: (EngineFailure) -> String = { it.toString() },
    ): FeatureFailure = FeatureFailure(FEATURE, describe(failure))

    /**
     * Reports every failure [failures] hears to [errors] until the result is closed. [describe] turns
     * a failure into the banner text; the app passes localized text, tests may pass their own.
     */
    fun reportTo(
        errors: ErrorChannel,
        failures: FailureRelay = NativeFailures,
        describe: (EngineFailure) -> String = { it.toString() },
    ): AutoCloseable = failures.listen { errors.report(featureFailure(it, describe)) }

    /** The banner text for [failure], by the part of the engine that failed. */
    @StringRes
    fun messageFor(failure: EngineFailure): Int =
        when (failure.subsystem) {
            EngineSubsystem.AUDIO -> R.string.engine_failure_audio
            EngineSubsystem.VISUAL -> R.string.engine_failure_visual
            else -> R.string.engine_failure_other
        }
}
