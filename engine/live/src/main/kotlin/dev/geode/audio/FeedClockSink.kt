package dev.geode.audio

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import dev.geode.engine.audio.FeedSource
import dev.geode.engine.audio.PresentationLead
import dev.geode.engine.audio.VisualFeed
import java.nio.ByteBuffer

/**
 * ExoPlayer's audio sink, telling the Visual's feed what its tap cannot see: when playback pauses or
 * resumes, when a seek throws away the audio buffered ahead, and how far ahead of the speaker the tap
 * runs. The tap sits at the start of the sink's processor chain, so it sees each buffer as the sink
 * takes it in, about half a second before the AudioTrack plays it; the lead is the media time the tap
 * has reached minus the position the sink reports as heard. Everything here runs on ExoPlayer's
 * playback thread, so nothing reaches the main thread.
 */
@OptIn(UnstableApi::class)
internal class FeedClockSink(
    sink: AudioSink,
    private val feed: VisualFeed,
) : ForwardingAudioSink(sink) {
    private val lead = PresentationLead()
    private var bytesPerFrame = 0
    private var rateHz = 0
    private var bufferPtsUs = C.TIME_UNSET
    private var bufferBytes = 0
    private var tappedEndUs = C.TIME_UNSET
    private var speed = 1f

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        val pcm = Util.isEncodingLinearPcm(format.pcmEncoding) && format.channelCount > 0 && format.sampleRate > 0
        bytesPerFrame = if (pcm) Util.getPcmFrameSize(format.pcmEncoding, format.channelCount) else 0
        rateHz = if (pcm) format.sampleRate else 0
        super.configure(audioSinkConfig)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        // A buffer the sink could not take whole comes back with the same time; count it from its start.
        if (presentationTimeUs != bufferPtsUs) {
            bufferPtsUs = presentationTimeUs
            bufferBytes = buffer.remaining()
        }
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (bytesPerFrame > 0 && rateHz > 0) {
            val frames = (bufferBytes - buffer.remaining()) / bytesPerFrame
            tappedEndUs = presentationTimeUs + frames * C.MICROS_PER_SECOND / rateHz
        }
        return handled
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val heard = super.getCurrentPositionUs(sourceEnded)
        val tapped = tappedEndUs
        if (heard != AudioSink.CURRENT_POSITION_NOT_SET && tapped != C.TIME_UNSET) {
            lead.offer(tapped, heard, SystemClock.uptimeMillis())?.let { feed.setPresentation(FeedSource.PLAYER, it, speed) }
        }
        return heard
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        speed = playbackParameters.speed
        lead.reset()
        super.setPlaybackParameters(playbackParameters)
    }

    override fun play() {
        super.play()
        feed.setRunning(FeedSource.PLAYER, true)
    }

    override fun pause() {
        super.pause()
        feed.setRunning(FeedSource.PLAYER, false)
    }

    override fun flush() {
        super.flush()
        bufferPtsUs = C.TIME_UNSET
        tappedEndUs = C.TIME_UNSET
        lead.reset()
        feed.flush(FeedSource.PLAYER)
    }

    override fun reset() {
        super.reset()
        bufferPtsUs = C.TIME_UNSET
        tappedEndUs = C.TIME_UNSET
        lead.reset()
        feed.setRunning(FeedSource.PLAYER, false)
    }
}
