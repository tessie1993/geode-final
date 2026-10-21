package dev.geode.feature.playback.handlers

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.core.MediaControl
import dev.geode.feature.playback.core.MediaControls
import dev.geode.feature.playback.core.SessionStatus
import dev.geode.feature.playback.core.SessionView
import dev.geode.feature.playback.core.describe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The player the media session publishes (#36): Playback itself, as the notification, the lock
 * screen, Bluetooth and the widget see it (ADR-0004).
 *
 * It holds no audio. What it shows is [MediaControls.sessionOf] Playback's state, refreshed whenever
 * that changes; what it is asked to do (play, pause, next, a seek) becomes a [MediaControl] and goes
 * to Playback as a Command, never to the audio engine. Playback then drives the engine and the
 * change comes back here through its state.
 *
 * Built on the main looper, and [scope] must run on it too: it follows Playback's state for the life
 * of the player.
 */
@OptIn(UnstableApi::class)
class PlaybackSessionPlayer(
    private val playback: Playback,
    private val clocks: PlaybackClocks,
    scope: CoroutineScope,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val following: Job = scope.launch { playback.state.collect { invalidateState() } }

    override fun getState(): State = stateOf(MediaControls.sessionOf(playback.state.value.player))

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        control(if (playWhenReady) MediaControl.Play else MediaControl.Pause)

    override fun handleStop(): ListenableFuture<*> = control(MediaControl.Stop)

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> = control(controlFor(mediaItemIndex, positionMs, seekCommand))

    override fun handleRelease(): ListenableFuture<*> {
        following.cancel()
        return Futures.immediateVoidFuture()
    }

    private fun control(control: MediaControl): ListenableFuture<*> {
        MediaControls.commandFor(control, playback.state.value.player)?.let(playback::send)
        return Futures.immediateVoidFuture()
    }

    private fun stateOf(view: SessionView): State {
        val builder =
            State
                .Builder()
                .setAvailableCommands(commandsOf(view))
                .setPlayWhenReady(view.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(statusOf(view.status))
                .setPlayerError(view.failure?.let { PlaybackException(describe(it), null, PlaybackException.ERROR_CODE_TIMEOUT) })
        view.queue?.let { queue ->
            builder
                .setPlaylist(itemsOf(queue))
                .setCurrentMediaItemIndex(queue.index)
                .setContentPositionMs { view.mark.positionAt(clocks.elapsed()).value }
        }
        return builder.build()
    }

    private companion object {
        /** What every session offers, whatever the state: Playback answers or ignores each of these. */
        val ALWAYS: Player.Commands =
            Player.Commands
                .Builder()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_STOP)
                .add(Player.COMMAND_RELEASE)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_TIMELINE)
                .add(Player.COMMAND_GET_METADATA)
                .build()

        fun commandsOf(view: SessionView): Player.Commands =
            Player.Commands
                .Builder()
                .addAll(ALWAYS)
                .addIf(Player.COMMAND_SEEK_TO_MEDIA_ITEM, view.queue != null)
                .addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, view.canSeek)
                .addIf(Player.COMMAND_SEEK_BACK, view.canSeek)
                .addIf(Player.COMMAND_SEEK_FORWARD, view.canSeek)
                .addIf(Player.COMMAND_SEEK_TO_NEXT, view.canNext)
                .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, view.canNext)
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, view.canPrevious)
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, view.canPrevious)
                .build()

        fun statusOf(status: SessionStatus): Int =
            when (status) {
                SessionStatus.IDLE -> Player.STATE_IDLE
                SessionStatus.BUFFERING -> Player.STATE_BUFFERING
                SessionStatus.READY -> Player.STATE_READY
                SessionStatus.ENDED -> Player.STATE_ENDED
            }

        /** Each queued track, with a uid of its place too, since a queue may hold a track twice. */
        fun itemsOf(queue: Queue): List<MediaItemData> =
            queue.tracks.mapIndexed { index, track ->
                MediaItemData
                    .Builder("$index:${track.id.value}")
                    .setMediaItem(
                        MediaItem
                            .Builder()
                            .setMediaId(track.id.value)
                            .setMediaMetadata(
                                MediaMetadata
                                    .Builder()
                                    .setTitle(track.title)
                                    .setArtist(track.artist)
                                    .setDurationMs(track.duration.value)
                                    .build(),
                            ).build(),
                    ).setDurationUs(Util.msToUs(track.duration.value))
                    .setIsSeekable(true)
                    .build()
            }

        /**
         * What a seek on the session asks for. Next and previous go to Playback as they are: it
         * decides whether previous restarts the track. Any other seek names a place in the queue.
         */
        fun controlFor(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): MediaControl =
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> MediaControl.Next
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> MediaControl.Previous
                else -> MediaControl.SkipTo(mediaItemIndex, if (positionMs == C.TIME_UNSET) Millis.ZERO else Millis(positionMs))
            }
    }
}
