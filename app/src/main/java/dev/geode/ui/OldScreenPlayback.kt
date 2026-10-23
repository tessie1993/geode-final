package dev.geode.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Track
import dev.geode.geodeContainer
import dev.geode.playback.TrackLength
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playback as the old screens still use it, until each is replaced by Playback's own routes (#17).
 *
 * Every call becomes a Command to the one app-scoped [Playback] store, and what the old screens show
 * is read back from its state (ADR-0003: one source of truth). Nothing here holds the player or a
 * queue, and nothing polls the player: the position shown is worked out from the clock between
 * Playback's announcements (ADR-0004).
 *
 * The old screens hand over songs as [QueueTrack]s, which may lack a title or a length. Those are
 * looked up in the phone's media index first, off the main thread, so Playback always gets a [Track]
 * it can show.
 */
@Singleton
// Feature's Command and Fact are in/out, so Kotlin gives constructor parameters of Playback and
// Settings Java wildcards the provided stores lack; without this Dagger finds no binding for them.
@JvmSuppressWildcards
class OldScreenPlayback internal constructor(
    private val playback: Playback,
    private val application: Application,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(playback: Playback, application: Application) : this(playback, application, application.geodeContainer.appScope)

    // Every send goes through this, in order (see [send]).
    private val sends = Mutex()

    /** What the old player, the mini-player and full-screen visual mode draw. */
    val uiState: StateFlow<PlayerUiState> =
        channelFlow {
            playback.state.collectLatest { state ->
                send(OldScreenModels.playerUiState(state, now()))
                // Reading the clock, never the player, while the position moves.
                while (state.player is PlayerState.Playing) {
                    delay(POSITION_REFRESH_MS)
                    send(OldScreenModels.playerUiState(state, now()))
                }
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OldScreenModels.playerUiState(playback.state.value, now()))

    val queue: StateFlow<QueueUiState> = follow(OldScreenModels::queueUiState)

    val abLoop: StateFlow<AbLoop?> = follow { OldScreenModels.abLoopOf(it.loop) }

    val sleepTimerRemaining: StateFlow<Millis?> = follow { OldScreenModels.sleepRemainingOf(it.sleep) }

    /** Bumped whenever the play history changes, for screens that re-read [recentlyPlayed]. */
    val historyTick: StateFlow<Int> = follow { it.history.hashCode() }

    val state: StateFlow<PlaybackState> get() = playback.state

    fun currentTrack(): TrackId? = OldScreenModels.queueOf(playback.state.value.player)?.current?.id

    fun recentlyPlayed(): List<QueueTrack> = OldScreenModels.recentlyPlayed(playback.state.value)

    fun togglePlayPause() = send(OldScreenModels.playPause(playback.state.value.player))

    fun pause() = send(PlaybackCommand.Pause)

    fun next() = send(PlaybackCommand.Next)

    fun previous() = send(PlaybackCommand.Previous)

    fun seekToMs(position: Millis) = send(PlaybackCommand.SeekTo(position.coerceAtLeast(Millis.ZERO)))

    fun seekTo(fraction: Float) {
        OldScreenModels.seekToFraction(playback.state.value.player, fraction)?.let(::send)
    }

    fun playQueueIndex(index: Int) = send(PlaybackCommand.PlayQueueItem(index))

    fun removeQueueItem(index: Int) = send(PlaybackCommand.RemoveFromQueue(index))

    fun moveQueueItem(
        from: Int,
        to: Int,
    ) = send(PlaybackCommand.MoveInQueue(from, to))

    fun toggleShuffle() = send(PlaybackCommand.SetShuffle(!playback.state.value.options.shuffle))

    fun cycleRepeatMode() = send(PlaybackCommand.SetRepeat(OldScreenModels.nextRepeat(playback.state.value.options.repeat)))

    /** The old A-B button: its start, then its end, then off, at where the song is now. */
    fun cycleAbLoop() = send(PlaybackCommand.MarkLoop(OldScreenModels.positionAt(playback.state.value.player, now())))

    fun clearAbLoop() = send(PlaybackCommand.ClearLoop)

    fun startSleepTimer(
        minutes: Int,
        finishTrack: Boolean,
    ) {
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        send(PlaybackCommand.StartSleepTimer(Millis(minutes * MILLIS_PER_MINUTE), finishTrack))
    }

    fun cancelSleepTimer() = send(PlaybackCommand.CancelSleepTimer)

    /** Plays what is loaded, or the song played last when nothing is. */
    fun resumeLastPlayed() {
        if (OldScreenModels.queueOf(playback.state.value.player) != null) {
            send(PlaybackCommand.Resume)
            return
        }
        val last = recentlyPlayed().firstOrNull() ?: return
        playFrom(listOf(last), last.uri)
    }

    fun shuffleAllHistory() = playAll(recentlyPlayed().take(HISTORY_SHUFFLE), shuffled = true)

    /** Plays [tracks] from [startUri]; a song that is not in the list plays on its own, as the old player did. */
    fun playFrom(
        tracks: List<QueueTrack>,
        startUri: TrackId,
    ) {
        val list = if (tracks.any { it.uri == startUri }) tracks else listOf(QueueTrack(startUri))
        withDetails(list) { known -> listOfNotNull(OldScreenModels.playFrom(known, startUri)) }
    }

    fun playAll(
        tracks: List<QueueTrack>,
        shuffled: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        withDetails(tracks) { known ->
            // "Shuffle all" turns shuffle on; "Play all" leaves the shuffle setting as it is.
            listOfNotNull(
                PlaybackCommand.SetShuffle(true).takeIf { shuffled },
                PlaybackCommand.Play(known.take(PlaybackQueue.MAX_QUEUE), 0),
            )
        }
    }

    /** Songs opened from outside the app, played in the order given. */
    fun open(uris: List<Uri>) {
        if (uris.isEmpty()) return
        withDetails(uris.map { QueueTrack(TrackId(it.toString())) }) { known -> listOf(PlaybackCommand.Play(known, 0)) }
    }

    fun playNext(uri: TrackId) = withDetails(listOf(QueueTrack(uri))) { listOf(PlaybackCommand.PlayNext(it)) }

    fun enqueue(uri: TrackId) = withDetails(listOf(QueueTrack(uri))) { listOf(PlaybackCommand.AddToQueue(it)) }

    /**
     * Sends [command] after every send asked for before it, including those still waiting for songs'
     * details: one Mutex, which hands itself over in the order it was asked for, keeps them in order.
     */
    private fun send(command: PlaybackCommand) {
        scope.launch { sends.withLock { playback.send(command) } }
    }

    private fun <T> follow(read: (PlaybackState) -> T): StateFlow<T> =
        playback.state.map(read).stateIn(scope, SharingStarted.Eagerly, read(playback.state.value))

    /**
     * Fills in what [tracks] lack from the phone's media index, then sends Playback the Commands [then]
     * makes of the songs. A look-up holds back the sends asked for after it, so Playback gets them in
     * the order they were asked for.
     */
    private fun withDetails(
        tracks: List<QueueTrack>,
        then: (List<Track>) -> List<PlaybackCommand>,
    ) {
        scope.launch {
            sends.withLock {
                val known =
                    if (tracks.none(::lacksDetails)) {
                        tracks
                    } else {
                        withContext(Dispatchers.IO) { tracks.map { if (lacksDetails(it)) withLength(lookUp(it)) else it } }
                    }
                then(known.map(OldScreenModels::trackOf)).forEach(playback::send)
            }
        }
    }

    private fun lacksDetails(track: QueueTrack): Boolean = track.title.isBlank() || track.duration <= Millis.ZERO

    /** A song the media index has no length for (one opened from a document provider) gets the one its file records. */
    private fun withLength(track: QueueTrack): QueueTrack =
        track.copy(duration = TrackLength.orRead(application, track.uri, track.duration))

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A song the index does not know plays with what is known.
    private fun lookUp(track: QueueTrack): QueueTrack =
        try {
            val uri = Uri.parse(track.uri.value)
            application.contentResolver
                .query(uri, COLUMNS, null, null, null)
                ?.use { c ->
                    if (!c.moveToFirst()) return@use track
                    val title =
                        c.stringOf(MediaStore.Audio.Media.TITLE) ?: c.stringOf(OpenableColumns.DISPLAY_NAME)?.substringBeforeLast('.')
                    val artist = c.stringOf(MediaStore.Audio.Media.ARTIST)
                    val duration = c.longOf(MediaStore.Audio.Media.DURATION)
                    track.copy(
                        title = track.title.ifBlank { title.orEmpty() },
                        artist = track.artist.ifBlank { artist.orEmpty() },
                        duration = if (track.duration > Millis.ZERO) track.duration else Millis(duration ?: 0L),
                    )
                } ?: track
        } catch (failed: Exception) {
            track
        }

    private fun android.database.Cursor.stringOf(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }?.takeIf { it.isNotBlank() && it != UNKNOWN }

    private fun android.database.Cursor.longOf(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 }?.let { getLong(it) }

    private fun now(): Millis = Millis(SystemClock.elapsedRealtime())

    private companion object {
        const val POSITION_REFRESH_MS = 250L
        const val STOP_TIMEOUT_MS = 5_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val HISTORY_SHUFFLE = 100
        const val UNKNOWN = "<unknown>"

        // Every column: a provider fails a query that names a column it lacks, and songs come
        // from the media index and from document providers alike. A missing one stays unknown.
        val COLUMNS: Array<String>? = null
    }
}
