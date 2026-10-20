package dev.geode.audio.adapter

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId

/**
 * An [AudioAdapter] for tests: no audio, just the pipeline's observable behaviour. A test sets what
 * the next start does ([refuseNextStart]), changes the world ([unplug], [hearSilence]) and reads
 * back what was asked of it ([calls]).
 */
class FakeAudioAdapter : AudioAdapter {
    var sources: Set<CaptureSource> = CaptureSource.entries.toSet()

    /** What the next [startCapture] fails with; null lets it succeed. Used once. */
    var refuseNextStart: CaptureFailure? = null

    private var capture: CaptureStatus = CaptureStatus.Idle
    private var playback = PlaybackStatus(track = null, position = Millis.ZERO, playing = false, ended = false)

    /** Every call made, in order, as text such as `startCapture(MICROPHONE)`. */
    val calls = mutableListOf<String>()

    /** The running capture ends by itself, as when a USB interface is pulled out. */
    fun unplug() {
        capture = CaptureStatus.Idle
    }

    /** The running capture has heard nothing for a while. */
    fun hearSilence() {
        val running = capture as? CaptureStatus.Running ?: return
        capture = running.copy(quiet = true)
    }

    override suspend fun captureSources(): Set<CaptureSource> = sources

    override suspend fun startCapture(source: CaptureSource): CaptureFailure? {
        calls += "startCapture($source)"
        val failure = refuseNextStart ?: if (source in sources) null else CaptureFailure.UNAVAILABLE
        refuseNextStart = null
        if (failure == null) capture = CaptureStatus.Running(source, quiet = false)
        return failure
    }

    override suspend fun stopCapture() {
        calls += "stopCapture"
        capture = CaptureStatus.Idle
    }

    override suspend fun captureStatus(): CaptureStatus = capture

    override suspend fun load(
        track: TrackId,
        from: Millis,
    ) {
        calls += "load($track, $from)"
        playback = playback.copy(track = track, position = from, ended = false)
    }

    override suspend fun setPlaying(playing: Boolean) {
        calls += "setPlaying($playing)"
        playback = playback.copy(playing = playing)
    }

    override suspend fun seekTo(position: Millis) {
        calls += "seekTo($position)"
        playback = playback.copy(position = position, ended = false)
    }

    override suspend fun playbackStatus(): PlaybackStatus = playback
}
