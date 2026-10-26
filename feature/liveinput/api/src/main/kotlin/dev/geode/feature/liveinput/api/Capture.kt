package dev.geode.feature.liveinput.api

import dev.geode.kernel.Feature

/**
 * Capture's public interface (ADR-0003, ADR-0005): live sound from the microphone (the Live input;
 * line-in is removed, owner decision) becomes what the Visual reacts to, in place of the player. Other features, the screens and the
 * Quick Settings tile reach Capture only through this: its read-only [Feature.state], [Feature.send]
 * and its [Feature.events].
 *
 * Capturing another app's audio is not part of Capture (#9), so there is no screen-capture consent.
 */
typealias Capture = Feature<CaptureState, CaptureCommand, CaptureFact>

/** Where live sound is captured from. */
enum class CaptureSource {
    /** The phone's own microphone, the only Live input. */
    MICROPHONE,
}

/** Whether a song that is playing when capture starts keeps playing; the person is asked each time (ADR-0004). */
enum class MusicChoice {
    PAUSE,
    KEEP_PLAYING,
}

/** Why Capture stopped or could not start. It is explained on screen; there is no automatic fallback. */
enum class CaptureProblem {
    /** The microphone permission was refused. */
    PERMISSION_DENIED,

    /** The source is not there, for example the phone has no microphone, or the system would not open it. */
    SOURCE_UNAVAILABLE,

    /**
     * The source went away while capturing, for example another app took the microphone or Geode went
     * to the background. The capture comes back by itself when the source does, unless it is dismissed.
     */
    SOURCE_LOST,

    /** The source accepted the start but never began capturing. */
    DID_NOT_START,
}

/** Where Capture stands. Exactly one of these at a time. */
sealed interface CaptureState {
    /** Nothing is captured; the player feeds the Visual. */
    data object Off : CaptureState

    /** Finding out from Playback whether a song is playing, before asking about it. */
    data class Preparing(
        val source: CaptureSource,
    ) : CaptureState

    /** A song is playing: waiting for the person to choose whether it pauses. */
    data class AskingAboutMusic(
        val source: CaptureSource,
    ) : CaptureState

    /** The source needs the microphone permission: the screen asks for it, and the answer comes back as a command. */
    data class AwaitingPermission(
        val source: CaptureSource,
        val music: MusicChoice,
    ) : CaptureState

    /** The source was asked to start and has not yet announced that it runs. */
    data class Starting(
        val source: CaptureSource,
        val music: MusicChoice,
    ) : CaptureState

    /** Capturing from [source]; [quiet] while it has heard nothing for a few seconds. */
    data class Capturing(
        val source: CaptureSource,
        val quiet: Boolean,
    ) : CaptureState

    /** Capture from [source] stopped or never started because of [problem]; shown until dismissed. */
    data class Failed(
        val source: CaptureSource,
        val problem: CaptureProblem,
    ) : CaptureState
}

/** What the outside may ask of Capture. */
sealed interface CaptureCommand {
    /** Start capturing from [source], or switch to it while capturing from another one. */
    data class Start(
        val source: CaptureSource,
    ) : CaptureCommand

    /** Stop capturing, cancel a start in progress, or dismiss a failure. */
    data object Stop : CaptureCommand

    /** Start from [source] when idle, otherwise stop: the Quick Settings tile's one tap. */
    data class Toggle(
        val source: CaptureSource,
    ) : CaptureCommand

    /** The person's answer to [CaptureState.AskingAboutMusic]. */
    data class ChooseMusic(
        val choice: MusicChoice,
    ) : CaptureCommand

    /** The answer to the system's microphone permission request, made by the route. */
    data class PermissionAnswered(
        val granted: Boolean,
    ) : CaptureCommand
}

/** One-off facts Capture owns. */
sealed interface CaptureFact {
    /** The capture from [source] ended by itself, for example because the device was unplugged. */
    data class Lost(
        val source: CaptureSource,
    ) : CaptureFact
}
