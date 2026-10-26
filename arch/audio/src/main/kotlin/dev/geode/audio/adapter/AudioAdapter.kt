package dev.geode.audio.adapter

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import kotlinx.coroutines.flow.Flow

/**
 * The one way features reach the shared native audio pipeline: the player, live capture, and the
 * analysis that turns either into Sound signals for the Visual (ADR-0003). It sits beneath Playback
 * and Capture and is not a feature: it holds no feature state and makes no decisions a reducer should.
 *
 * It announces instead of being polled (ADR-0004). Calls ask for something; what actually happens
 * comes back on [announcements], which a feature's handlers turn into events. Android's media session
 * is the single truth for playback, and its changes are announced here.
 *
 * Every call may be made from any thread; the adapter moves to the thread the pipeline needs. Only a
 * feature's handlers call it: screens never touch the audio.
 */
interface AudioAdapter {
    /**
     * Every change in the pipeline, as it happens. A new collector first hears where things stand
     * (the track, whether it is playing, any running capture), then each change. Not replayed beyond
     * that, so collect for as long as the feature needs to know.
     */
    val announcements: Flow<AudioAnnouncement>

    /** The capture sources usable right now: the microphone, on a phone that has one. */
    suspend fun captureSources(): Set<CaptureSource>

    /**
     * Asks to make [source] what the Visual reacts to, in place of the player, replacing any capture
     * already running. Returns why it cannot, or null when it can, in which case
     * [CaptureAnnouncement.Running] follows. Does not pause the player: that is Playback's decision.
     */
    suspend fun startCapture(source: CaptureSource): CaptureFailure?

    /** Stops any capture and gives the Sound signals back to the player. Does nothing when idle. */
    suspend fun stopCapture()

    /** Loads [track] at [from], keeping the play/pause choice. [PlaybackAnnouncement.TrackChanged] follows. */
    suspend fun load(
        track: TrackId,
        from: Millis,
    )

    /** Plays or pauses what is loaded. [PlaybackAnnouncement.Started] or [PlaybackAnnouncement.Paused] follows. */
    suspend fun setPlaying(playing: Boolean)

    /** Moves within the loaded track. [PlaybackAnnouncement.Seeked] follows. */
    suspend fun seekTo(position: Millis)

    /**
     * Sets how one track gives way to the next (#35): fading over [crossfade] (zero for none), and with
     * no gap when [gapless]. A pipeline that cannot do either joins tracks as [load] leaves them.
     */
    suspend fun setTransitions(
        crossfade: Millis,
        gapless: Boolean,
    )

    /**
     * Opens [track] ahead, to follow the loaded one by itself (#35), in place of any prepared before;
     * null prepares nothing. When the player moves on to it, [PlaybackAnnouncement.TrackChanged] says
     * so. A pipeline that cannot prepare ahead does nothing, and the loaded track simply ends.
     */
    suspend fun prepareNext(track: TrackId?)
}

/** Where live audio is captured from. */
enum class CaptureSource {
    /** The device's own microphone, the only Live input (owner decision: line-in is removed). */
    MICROPHONE,
}

/** Why a capture could not start. */
enum class CaptureFailure {
    /** The microphone permission is not granted. */
    PERMISSION,

    /** The source is not there or the system would not open it. */
    UNAVAILABLE,
}

/**
 * Where playback stood at one moment: [position] in the track at [at] on the monotonic clock (Android's
 * elapsed realtime), advancing in real time while [moving]. Playback works out the position between
 * announcements from this, so nothing polls the player.
 */
data class PlaybackMark(
    val position: Millis,
    val at: Millis,
    val moving: Boolean,
) {
    /** The position at [now] on the same clock as [at]. */
    fun positionAt(now: Millis): Millis = if (moving) position + (now - at) else position
}

sealed interface AudioAnnouncement

/** What the media session reports about playback. */
sealed interface PlaybackAnnouncement : AudioAnnouncement {
    /** The position is advancing from [mark]. */
    data class Started(
        val mark: PlaybackMark,
    ) : PlaybackAnnouncement

    /** The position has stopped at [mark]: paused by someone, or waiting for data. */
    data class Paused(
        val mark: PlaybackMark,
    ) : PlaybackAnnouncement

    /** The position jumped to [mark]. */
    data class Seeked(
        val mark: PlaybackMark,
    ) : PlaybackAnnouncement

    /** Now on [track] (null when nothing is loaded), at [mark]. */
    data class TrackChanged(
        val track: TrackId?,
        val mark: PlaybackMark,
    ) : PlaybackAnnouncement

    /** The loaded track, and anything queued after it, played to the end. */
    data object Ended : PlaybackAnnouncement

    /** The player stopped because of an error; [reason] says which, for the log and the banner. */
    data class Failed(
        val reason: String,
    ) : PlaybackAnnouncement
}

/** What the capture reports. */
sealed interface CaptureAnnouncement : AudioAnnouncement {
    /** Capturing from [source]. */
    data class Running(
        val source: CaptureSource,
    ) : CaptureAnnouncement

    /** The capture has heard nothing for a few seconds ([quiet]), or hears something again. */
    data class QuietChanged(
        val quiet: Boolean,
    ) : CaptureAnnouncement

    /**
     * The capture from [source] is lost: another app or the system took it, the app went to the
     * background, or reading failed past its retries. Unless it ended for good, a [Running] follows when
     * the source is back.
     */
    data class Lost(
        val source: CaptureSource,
    ) : CaptureAnnouncement
}
