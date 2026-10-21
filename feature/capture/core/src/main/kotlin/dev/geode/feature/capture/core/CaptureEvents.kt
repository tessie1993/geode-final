package dev.geode.feature.capture.core

import dev.geode.engine.types.Millis
import dev.geode.feature.capture.api.CaptureCommand
import dev.geode.feature.capture.api.CaptureFact
import dev.geode.feature.capture.api.CaptureSource
import dev.geode.kernel.Effect

/** The name Capture reports its failures under, on the app-wide error channel. */
const val CAPTURE_FEATURE = "capture"

/** How long a source may take to announce that it runs before the start counts as failed. */
val START_TIMEOUT = Millis(2_000)

/** The one timer: how long a start may take, armed only while [dev.geode.feature.capture.api.CaptureState.Starting]. */
data object StartTimeout

/** Everything the reducer handles: the commands, and what the handlers and the timer report back. */
sealed interface CaptureEvent {
    data class Command(
        val command: CaptureCommand,
    ) : CaptureEvent

    /** Playback answered whether a song is playing. */
    data class MusicChecked(
        val playing: Boolean,
    ) : CaptureEvent

    /** The audio adapter announced that capture from [source] runs. */
    data class Running(
        val source: CaptureSource,
    ) : CaptureEvent

    /** The audio adapter would not start the capture. */
    data class Refused(
        val refusal: StartRefusal,
    ) : CaptureEvent

    /** The capture went quiet, or hears something again. */
    data class QuietChanged(
        val quiet: Boolean,
    ) : CaptureEvent

    /** The capture from [source] ended by itself. */
    data class Lost(
        val source: CaptureSource,
    ) : CaptureEvent

    /** The start timeout fired. */
    data object StartTimedOut : CaptureEvent

    /** A piece of work failed at the handlers' edge; the reducer reports it. */
    data class WorkFailed(
        val failure: WorkFailure,
    ) : CaptureEvent
}

/** Why the audio adapter refused a start. */
enum class StartRefusal {
    /** The microphone permission is missing; the person can still grant it. */
    PERMISSION,

    /** The source is not there or would not open. */
    UNAVAILABLE,
}

/** Work the handlers could not carry out, named for the log and the app-wide banner. */
enum class WorkFailure(
    val message: String,
) {
    MUSIC_UNREACHABLE("could not ask Playback about the song"),
    MUSIC_NOT_PAUSED("could not pause the song"),
    NOT_STOPPED("the capture did not stop"),
}

/** The handlers' work, carried out by `:feature:capture:handlers`. */
sealed interface CaptureWork {
    /** Follow the audio adapter's capture announcements for the life of the store. */
    data object Listen : CaptureWork

    /** Ask Playback whether a song is playing; answered with [CaptureEvent.MusicChecked]. */
    data object CheckMusic : CaptureWork

    /** Ask the audio adapter to capture from [source]. */
    data class Begin(
        val source: CaptureSource,
    ) : CaptureWork

    /** Stop any capture. */
    data object End : CaptureWork

    /** Ask Playback to pause the song. */
    data object PauseMusic : CaptureWork
}

typealias CaptureEffect = Effect<CaptureWork, CaptureEvent, CaptureFact>

/**
 * What Capture needs from Playback, as a port (ADR-0005: features talk only through each other's
 * `:api`). Playback's `:api` does not exist yet; `:app` adapts it to this when it wires Capture.
 */
interface MusicControl {
    /** Whether a song is playing right now. */
    suspend fun isPlaying(): Boolean

    /** Pauses the song; does nothing when none plays. */
    suspend fun pause()
}
