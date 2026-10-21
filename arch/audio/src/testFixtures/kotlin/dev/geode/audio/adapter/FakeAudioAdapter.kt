package dev.geode.audio.adapter

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An [AudioAdapter] for tests: no audio, just the pipeline's observable behaviour. A test makes it
 * [announce] anything the real one can, sets what the next start does ([refuseNextStart]), and reads
 * back what was asked of it ([calls]). A start that succeeds announces [CaptureAnnouncement.Running]
 * as the real one does; everything else is announced only when the test says so.
 */
class FakeAudioAdapter : AudioAdapter {
    var sources: Set<CaptureSource> = CaptureSource.entries.toSet()

    /** What the next [startCapture] fails with; null lets it succeed. Used once. */
    var refuseNextStart: CaptureFailure? = null

    /** Every call made, in order, as text such as `startCapture(MICROPHONE)`. */
    val calls = mutableListOf<String>()

    private val announced = MutableSharedFlow<AudioAnnouncement>(extraBufferCapacity = BUFFER)

    override val announcements: Flow<AudioAnnouncement> = announced.asSharedFlow()

    /** Announces [announcement] to everyone collecting [announcements]. */
    fun announce(announcement: AudioAnnouncement) {
        check(announced.tryEmit(announcement)) { "announcement dropped: $announcement" }
    }

    override suspend fun captureSources(): Set<CaptureSource> = sources

    override suspend fun startCapture(source: CaptureSource): CaptureFailure? {
        calls += "startCapture($source)"
        val failure = refuseNextStart ?: if (source in sources) null else CaptureFailure.UNAVAILABLE
        refuseNextStart = null
        if (failure == null) announce(CaptureAnnouncement.Running(source))
        return failure
    }

    override suspend fun stopCapture() {
        calls += "stopCapture"
    }

    override suspend fun load(
        track: TrackId,
        from: Millis,
    ) {
        calls += "load($track, $from)"
    }

    override suspend fun setPlaying(playing: Boolean) {
        calls += "setPlaying($playing)"
    }

    override suspend fun seekTo(position: Millis) {
        calls += "seekTo($position)"
    }

    private companion object {
        const val BUFFER = 64
    }
}
