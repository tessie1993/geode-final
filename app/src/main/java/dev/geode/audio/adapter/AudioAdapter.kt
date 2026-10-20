package dev.geode.audio.adapter

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId

/**
 * The one way features reach the shared native audio pipeline: the player, live capture, and the
 * analysis that turns either of them into Sound signals for the Visual (ADR-0003). It sits beneath
 * Playback and Capture and is not a feature itself: it holds no feature state and makes no
 * decisions a reducer should make.
 *
 * Deliberately small. It answers questions with snapshots ([captureStatus], [playbackStatus]) rather
 * than streams, so a feature that wants to watch something polls it with a timer effect.
 *
 * Every call may be made from any thread; the adapter moves to the thread the pipeline needs. Only
 * effect runners call it: screens never touch the audio.
 */
interface AudioAdapter {
    /** The capture sources usable right now, for example [CaptureSource.LINE_IN] only while one is plugged in. */
    suspend fun captureSources(): Set<CaptureSource>

    /**
     * Makes [source] what the Visual reacts to, in place of the player, replacing any capture already
     * running. Returns null once it is capturing, or why it could not. Does not pause the player:
     * whether the music stops is Playback's decision.
     */
    suspend fun startCapture(source: CaptureSource): CaptureFailure?

    /** Stops any capture and gives the Sound signals back to the player. Does nothing when idle. */
    suspend fun stopCapture()

    /**
     * What the capture is doing now. A capture that ended by itself (the device was unplugged,
     * another app took the microphone) reads as [CaptureStatus.Idle], and the Sound signals are
     * already back with the player.
     */
    suspend fun captureStatus(): CaptureStatus

    /** Loads [track] and positions it at [from], keeping the current play/pause choice. */
    suspend fun load(
        track: TrackId,
        from: Millis,
    )

    /** Plays or pauses whatever is loaded. */
    suspend fun setPlaying(playing: Boolean)

    suspend fun seekTo(position: Millis)

    suspend fun playbackStatus(): PlaybackStatus
}

/** Where live audio is captured from. */
enum class CaptureSource {
    /** The device's own microphone. */
    MICROPHONE,

    /** A USB audio interface or an analogue or digital line input. */
    LINE_IN,
}

/** Why a capture could not start. */
enum class CaptureFailure {
    /** The microphone permission is not granted. */
    PERMISSION,

    /** The source is not there or the system would not open it. */
    UNAVAILABLE,
}

sealed interface CaptureStatus {
    data object Idle : CaptureStatus

    /** Capturing from [source]. [quiet] is true once it has heard nothing for a few seconds. */
    data class Running(
        val source: CaptureSource,
        val quiet: Boolean,
    ) : CaptureStatus
}

/**
 * The player as it is now. [playing] is the play/pause choice, true while buffering too; [ended] is
 * true once the loaded track has played to its end.
 */
data class PlaybackStatus(
    val track: TrackId?,
    val position: Millis,
    val playing: Boolean,
    val ended: Boolean,
)
