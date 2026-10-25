package dev.geode.playback

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.currentQueue

/** Whether Playback means to play, or is playing: what the media session and the sleep fade follow. */
internal val PlayerState.meantToPlay: Boolean
    get() =
        when (this) {
            is PlayerState.Loading -> playWhenReady
            is PlayerState.Buffering, is PlayerState.Playing -> true
            PlayerState.Idle, is PlayerState.Paused, is PlayerState.Ended, is PlayerState.Error -> false
        }

/** Where the current track is at [now], on the clock Playback's marks use (ADR-0004: no polling). */
internal fun PlayerState.positionAt(now: Millis): Millis {
    val position =
        when (this) {
            PlayerState.Idle, is PlayerState.Error -> Millis.ZERO
            is PlayerState.Loading -> from.position
            is PlayerState.Buffering -> mark.positionAt(now)
            is PlayerState.Playing -> mark.positionAt(now)
            is PlayerState.Paused -> mark.positionAt(now)
            is PlayerState.Ended -> queue.current.duration
        }
    val duration = currentQueue?.current?.duration ?: Millis.ZERO
    // A song whose length is not known yet is not held at zero.
    return if (duration > Millis.ZERO) position.coerceIn(Millis.ZERO, duration) else position.coerceAtLeast(Millis.ZERO)
}

/** A song's file name without its folder or extension, for a song with no title. */
internal fun TrackId.fileName(): String =
    java.net.URLDecoder
        .decode(value.substringAfterLast('/'), "UTF-8")
        .substringAfterLast('/')
        .substringAfterLast(':')
        .substringBeforeLast('.')
        .ifBlank { value }
