package dev.geode.playback

import androidx.annotation.StringRes
import dev.geode.R
import dev.geode.RingLog
import dev.geode.engine.bridge.EngineFailure
import dev.geode.engine.bridge.EngineSubsystem
import dev.geode.engine.bridge.FailureRelay
import dev.geode.engine.bridge.NativeFailures

/**
 * Puts every failure the native bridge reports on the app-wide banner, and in the local log first
 * (ADR-0004). [show] receives the banner text and runs on the failing call's thread, so it hands the
 * text on to the main thread itself. Audio and the Visual keep running.
 */
class EngineFailureNotices(
    private val show: (Int) -> Unit,
    failures: FailureRelay = NativeFailures,
) : AutoCloseable {
    private val subscription =
        failures.listen { failure ->
            RingLog.note(TAG, "engine failure: $failure")
            show(messageFor(failure))
        }

    override fun close() {
        subscription.close()
    }

    companion object {
        private const val TAG = "EngineFailure"

        /** The banner text for [failure], by the part of the engine that failed. */
        @StringRes
        fun messageFor(failure: EngineFailure): Int =
            when (failure.subsystem) {
                EngineSubsystem.AUDIO -> R.string.engine_failure_audio
                EngineSubsystem.VISUAL -> R.string.engine_failure_visual
                else -> R.string.engine_failure_other
            }
    }
}
