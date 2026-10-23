package dev.geode.ui

import androidx.media3.common.Player
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.SleepTimer
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.api.AbLoop as PlaybackLoop

/**
 * Playback's state as the old screens draw it, and their old calls as Playback Commands (#17).
 *
 * Playback is the one source of truth: these are plain functions of its state and of what the old
 * screens ask for, so nothing here holds a player or a queue. Each goes when the last old screen that
 * uses it is replaced by its feature's route.
 */
internal object OldScreenModels {
    /** The queue the old screens show, or null when Playback has nothing to play. */
    fun queueOf(player: PlayerState): Queue? =
        when (player) {
            PlayerState.Idle -> null
            is PlayerState.Loading -> player.queue
            is PlayerState.Buffering -> player.queue
            is PlayerState.Playing -> player.queue
            is PlayerState.Paused -> player.queue
            is PlayerState.Ended -> player.queue
            is PlayerState.Error -> player.queue
        }

    /** Where the current track is at [now], on the clock Playback's marks use (ADR-0004: no polling). */
    fun positionAt(
        player: PlayerState,
        now: Millis,
    ): Millis =
        when (player) {
            PlayerState.Idle, is PlayerState.Error -> Millis.ZERO
            is PlayerState.Loading -> player.from.position
            is PlayerState.Buffering -> player.mark.positionAt(now)
            is PlayerState.Playing -> player.mark.positionAt(now)
            is PlayerState.Paused -> player.mark.positionAt(now)
            is PlayerState.Ended -> player.queue.current.duration
        }.let { position ->
            val duration = queueOf(player)?.current?.duration ?: Millis.ZERO
            // A song whose length is not known yet is not held at zero.
            if (duration > Millis.ZERO) position.coerceIn(Millis.ZERO, duration) else position.coerceAtLeast(Millis.ZERO)
        }

    /** Whether the play button shows pause: Playback means to play, or is playing. */
    fun meantToPlay(player: PlayerState): Boolean =
        when (player) {
            is PlayerState.Loading -> player.playWhenReady
            is PlayerState.Buffering, is PlayerState.Playing -> true
            PlayerState.Idle, is PlayerState.Paused, is PlayerState.Ended, is PlayerState.Error -> false
        }

    fun playerUiState(
        state: PlaybackState,
        now: Millis,
    ): PlayerUiState {
        val queue =
            queueOf(state.player) ?: return PlayerUiState(shuffle = state.options.shuffle, repeatMode = repeatModeOf(state.options.repeat))
        val track = queue.current
        return PlayerUiState(
            isPlaying = meantToPlay(state.player),
            position = positionAt(state.player, now),
            duration = track.duration,
            title = track.title.ifBlank { fileNameOf(track.id) },
            artist = track.artist.ifBlank { null },
            hasMedia = true,
            queueSize = queue.tracks.size,
            queueIndex = queue.index,
            shuffle = state.options.shuffle,
            repeatMode = repeatModeOf(state.options.repeat),
        )
    }

    fun queueUiState(state: PlaybackState): QueueUiState {
        val queue = queueOf(state.player) ?: return QueueUiState()
        return QueueUiState(queue.tracks.map(::queueTrackOf), queue.index)
    }

    /** The A-B loop as the old player's loop button shows it. */
    fun abLoopOf(loop: PlaybackLoop): AbLoop? =
        when (loop) {
            PlaybackLoop.Off -> null
            is PlaybackLoop.Starting -> AbLoop(loop.start)
            is PlaybackLoop.On -> AbLoop(loop.start, loop.end)
        }

    /** How long the sleep timer has left, as the old player shows it; null when it is off or waits for the track's end. */
    fun sleepRemainingOf(sleep: SleepTimer): Millis? =
        when (sleep) {
            SleepTimer.Off, SleepTimer.AfterTrack -> null
            is SleepTimer.Counting -> sleep.remaining
        }

    fun repeatModeOf(mode: RepeatMode): Int =
        when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }

    /** The old repeat button's cycle: off, all, one, off. */
    fun nextRepeat(mode: RepeatMode): RepeatMode =
        when (mode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }

    /**
     * The old "play this list from that song": the list (at most [PlaybackQueue.MAX_QUEUE] songs around
     * [startUri]) becomes Playback's queue, starting at [startUri]. Null when there is nothing to play.
     */
    fun playFrom(
        tracks: List<Track>,
        startUri: TrackId,
    ): PlaybackCommand.Play? {
        val at = tracks.indexOfFirst { it.id == startUri }
        if (at < 0) return null
        if (tracks.size <= PlaybackQueue.MAX_QUEUE) return PlaybackCommand.Play(tracks, at)
        val half = PlaybackQueue.MAX_QUEUE / 2
        val from = (at - half).coerceIn(0, tracks.size - PlaybackQueue.MAX_QUEUE)
        return PlaybackCommand.Play(tracks.subList(from, from + PlaybackQueue.MAX_QUEUE), at - from)
    }

    /** The old play/pause button: pause what plays or opens to play, otherwise play. */
    fun playPause(player: PlayerState): PlaybackCommand = if (meantToPlay(player)) PlaybackCommand.Pause else PlaybackCommand.Resume

    /** The old seek bar's fraction of the current song, or null while its length is unknown. */
    fun seekToFraction(
        player: PlayerState,
        fraction: Float,
    ): PlaybackCommand.SeekTo? {
        val duration = queueOf(player)?.current?.duration ?: return null
        if (duration <= Millis.ZERO) return null
        return PlaybackCommand.SeekTo(Millis((duration.value * fraction.coerceIn(0f, 1f)).toLong()))
    }

    /** The plays in Playback's history, each song once, the most recent first. */
    fun recentlyPlayed(state: PlaybackState): List<QueueTrack> =
        state.history
            .map { it.track }
            .distinctBy { it.id }
            .map(::queueTrackOf)

    fun queueTrackOf(track: Track): QueueTrack =
        QueueTrack(
            track.id,
            track.title.ifBlank {
                fileNameOf(track.id)
            },
            track.artist,
            track.duration,
        )

    /** [track] as Playback plays it. */
    fun trackOf(track: QueueTrack): Track = Track(track.uri, track.title, track.artist, track.duration)

    /** What a song is called when nothing better is known: its file name without the extension. */
    fun fileNameOf(id: TrackId): String =
        java.net.URLDecoder
            .decode(id.value.substringAfterLast('/'), "UTF-8")
            .substringAfterLast('/')
            .substringAfterLast(':')
            .substringBeforeLast('.')
            .ifBlank { id.value }
}
