package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFact
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.PlayerState.Buffering
import dev.geode.feature.playback.api.PlayerState.Ended
import dev.geode.feature.playback.api.PlayerState.Error
import dev.geode.feature.playback.api.PlayerState.Idle
import dev.geode.feature.playback.api.PlayerState.Loading
import dev.geode.feature.playback.api.PlayerState.Paused
import dev.geode.feature.playback.api.PlayerState.Playing
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.core.EngineStep.Load
import dev.geode.feature.playback.core.EngineStep.Seek
import dev.geode.feature.playback.core.PlaybackWork.Drive
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer

private typealias Step = Next<PlayerState, PlaybackEffect>

/**
 * Playback's state machine (#34): Idle, Loading, Buffering, Playing, Paused, Ended and Error, driven by
 * Commands and by what the audio adapter announces. The media session is the truth (ADR-0004): the
 * state follows the announcements, and the Commands only ask.
 *
 * The three bugs it was built to end:
 * - Buffering never lasts forever: it gives way to Paused after [BUFFERING_GRACE] without a start.
 * - A pause while a track opens wins: the track opens paused, and a start it announces is paused again.
 * - A failure under a track that was already playing does not stop it: it is loaded again where it was.
 *   A track that fails while it opens is skipped silently (owner decision, #34).
 */
object PlaybackReducer : Reducer<PlaybackState, PlaybackEvent, PlaybackEffect> {
    override fun reduce(
        state: PlaybackState,
        event: PlaybackEvent,
    ): Next<PlaybackState, PlaybackEffect> =
        when (event) {
            is PlaybackEvent.Command -> command(state, event.command)
            is PlaybackEvent.PlaylistsChanged -> Next(state.copy(playlists = event.playlists))
            is PlaybackEvent.HistoryChanged -> Next(state.copy(history = event.history))
            is PlaybackEvent.PlaylistSaved -> Next(state, listOf(Emit(PlaybackFact.PlaylistSaved(event.name))))
            is PlaybackEvent.StorageFailed -> Next(state, listOf(ReportFailure(describe(event.failure))))
            is PlaybackEvent.PlaylistOpened -> player(state, play(state.player, event.tracks, startAt = 0))
            is PlaybackEvent.Started -> player(state, started(state.player, event.mark))
            is PlaybackEvent.Paused -> player(state, paused(state.player, event.mark))
            is PlaybackEvent.Seeked -> player(state, seeked(state.player, event.mark))
            is PlaybackEvent.TrackChanged -> player(state, trackChanged(state.player, event))
            PlaybackEvent.Ended -> player(state, ended(state.player))
            PlaybackEvent.Failed -> player(state, failed(state.player))
            PlaybackEvent.LoadTimedOut -> player(state, loadTimedOut(state.player))
            PlaybackEvent.BufferingGraceOver -> player(state, bufferingGraceOver(state.player))
            is PlaybackEvent.Restored -> player(state, restored(state.player, event))
        }

    private fun player(
        state: PlaybackState,
        step: Step,
    ): Next<PlaybackState, PlaybackEffect> = Next(state.copy(player = step.state), step.effects)

    private fun command(
        state: PlaybackState,
        command: PlaybackCommand,
    ): Next<PlaybackState, PlaybackEffect> =
        when (command) {
            is PlaybackCommand.Play -> player(state, play(state.player, command.tracks, command.startAt))
            is PlaybackCommand.PlayNext -> player(state, enqueue(state.player, command.tracks, next = true))
            is PlaybackCommand.AddToQueue -> player(state, enqueue(state.player, command.tracks, next = false))
            PlaybackCommand.Resume -> player(state, resume(state.player))
            PlaybackCommand.Pause -> player(state, pause(state.player))
            PlaybackCommand.Next -> player(state, next(state.player))
            PlaybackCommand.Previous -> player(state, previous(state.player))
            is PlaybackCommand.SeekTo -> player(state, seekTo(state.player, command.position))
            is PlaybackCommand.PlayPlaylist -> Next(state, listOf(Perform(PlaybackWork.OpenPlaylist(command.id))))
            is PlaybackCommand.CreatePlaylist -> createPlaylist(state, command.name, command.tracks)
            is PlaybackCommand.SaveHistoryAsPlaylist ->
                createPlaylist(state, command.name, state.history.map { it.track }.distinctBy { it.id })
            is PlaybackCommand.DeletePlaylist -> Next(state, listOf(Perform(PlaybackWork.DeletePlaylist(command.id))))
        }

    private fun createPlaylist(
        state: PlaybackState,
        name: String,
        tracks: List<Track>,
    ): Next<PlaybackState, PlaybackEffect> =
        if (name.isBlank() || tracks.isEmpty()) {
            Next(state)
        } else {
            Next(state, listOf(Perform(PlaybackWork.CreatePlaylist(name.trim(), tracks))))
        }

    // Commands.

    private fun play(
        from: PlayerState,
        tracks: List<Track>,
        startAt: Int,
    ): Step =
        if (tracks.isEmpty()) {
            Next(from)
        } else {
            load(from, Queue(tracks, startAt.coerceIn(tracks.indices)), START, playWhenReady = true, LoadReason.PLAY)
        }

    /**
     * Adds [tracks] after the current track ([next]) or at the end of the queue, leaving the player
     * where it is. With no queue yet, they are played.
     */
    private fun enqueue(
        from: PlayerState,
        tracks: List<Track>,
        next: Boolean,
    ): Step {
        val queue = queueOf(from) ?: return play(from, tracks, startAt = 0)
        val at = if (next) queue.index + 1 else queue.tracks.size
        return Next(withQueue(from, queue.copy(tracks = queue.tracks.take(at) + tracks + queue.tracks.drop(at))))
    }

    private fun withQueue(
        state: PlayerState,
        queue: Queue,
    ): PlayerState =
        when (state) {
            Idle -> state
            is Loading -> state.copy(queue = queue)
            is Buffering -> state.copy(queue = queue)
            is Playing -> state.copy(queue = queue)
            is Paused -> state.copy(queue = queue)
            is Ended -> state.copy(queue = queue)
            is Error -> state.copy(queue = queue)
        }

    private fun resume(from: PlayerState): Step =
        when (from) {
            Idle, is Buffering, is Playing -> Next(from)
            is Loading ->
                if (from.playWhenReady) Next(from) else Next(from.copy(playWhenReady = true), listOf(drive(EngineStep.Play)))
            is Paused -> buffer(from, from.queue, from.mark, drive(EngineStep.Play))
            is Ended -> load(from, from.queue.first(), START, playWhenReady = true, LoadReason.PLAY)
            is Error -> load(from, from.queue, START, playWhenReady = true, LoadReason.PLAY)
        }

    private fun pause(from: PlayerState): Step =
        when (from) {
            Idle, is Paused, is Ended, is Error -> Next(from)
            is Loading ->
                if (from.playWhenReady) Next(from.copy(playWhenReady = false), listOf(drive(EngineStep.Pause))) else Next(from)
            is Buffering -> go(from, Paused(from.queue, from.mark), drive(EngineStep.Pause), save(from.queue, from.mark))
            is Playing -> go(from, Paused(from.queue, from.mark), drive(EngineStep.Pause), save(from.queue, from.mark))
        }

    private fun next(from: PlayerState): Step {
        val queue = queueOf(from) ?: return Next(from)
        return if (queue.hasNext) load(from, queue.next(), START, wantsToPlay(from), LoadReason.PLAY) else Next(from)
    }

    private fun previous(from: PlayerState): Step =
        when (from) {
            Idle -> Next(from)
            is Loading ->
                if (from.queue.hasPrevious) {
                    load(
                        from,
                        from.queue.previous(),
                        START,
                        from.playWhenReady,
                        LoadReason.PLAY,
                    )
                } else {
                    Next(from)
                }
            is Buffering -> previousOrRestart(from, from.queue)
            is Playing -> previousOrRestart(from, from.queue)
            is Paused -> previousOrRestart(from, from.queue)
            is Ended -> load(from, from.queue, START, playWhenReady = true, LoadReason.PLAY)
            is Error ->
                load(
                    from,
                    if (from.queue.hasPrevious) from.queue.previous() else from.queue,
                    START,
                    playWhenReady = true,
                    LoadReason.PLAY,
                )
        }

    private fun previousOrRestart(
        from: PlayerState,
        queue: Queue,
    ): Step =
        if (queue.hasPrevious) {
            load(from, queue.previous(), START, wantsToPlay(from), LoadReason.PLAY)
        } else {
            Next(from, listOf(drive(Seek(Millis.ZERO))))
        }

    private fun seekTo(
        from: PlayerState,
        position: Millis,
    ): Step =
        when (from) {
            Idle, is Loading, is Ended, is Error -> Next(from)
            is Buffering -> seek(from, from.queue, position)
            is Playing -> seek(from, from.queue, position)
            is Paused -> seek(from, from.queue, position)
        }

    private fun seek(
        from: PlayerState,
        queue: Queue,
        position: Millis,
    ): Step = Next(from, listOf(drive(Seek(position.coerceIn(Millis.ZERO, queue.current.duration)))))

    // Announcements.

    private fun started(
        from: PlayerState,
        mark: PlayMark,
    ): Step =
        when (from) {
            Idle, is Ended, is Error -> Next(from)
            // A start while a track opens is the old play choice; a pause asked for since wins.
            is Loading -> if (from.playWhenReady) Next(from) else Next(from, listOf(drive(EngineStep.Pause)))
            is Buffering -> go(from, Playing(from.queue, mark))
            is Playing -> Next(Playing(from.queue, mark))
            is Paused -> go(from, Playing(from.queue, mark))
        }

    private fun paused(
        from: PlayerState,
        mark: PlayMark,
    ): Step =
        when (from) {
            Idle, is Loading, is Ended, is Error -> Next(from)
            is Buffering -> Next(from.copy(mark = mark))
            is Playing -> buffer(from, from.queue, mark)
            is Paused -> Next(from.copy(mark = mark), listOf(save(from.queue, mark)))
        }

    private fun seeked(
        from: PlayerState,
        mark: PlayMark,
    ): Step =
        when (from) {
            Idle, is Loading, is Ended, is Error -> Next(from)
            is Buffering -> Next(from.copy(mark = mark), listOf(save(from.queue, mark)))
            is Playing -> Next(from.copy(mark = mark), listOf(save(from.queue, mark)))
            is Paused -> Next(from.copy(mark = mark), listOf(save(from.queue, mark)))
        }

    private fun trackChanged(
        from: PlayerState,
        event: PlaybackEvent.TrackChanged,
    ): Step =
        when (from) {
            Idle, is Ended, is Error -> Next(from)
            // Only the track being opened ends the opening; any other is a late word about an older one.
            is Loading -> if (event.track == from.queue.current.id) opened(from, event.mark) else Next(from)
            is Buffering -> if (event.track == from.queue.current.id) Next(from.copy(mark = event.mark)) else Next(from)
            is Playing -> if (event.track == from.queue.current.id) Next(from.copy(mark = event.mark)) else Next(from)
            is Paused -> if (event.track == from.queue.current.id) Next(from.copy(mark = event.mark)) else Next(from)
        }

    private fun opened(
        from: Loading,
        mark: PlayMark,
    ): Step {
        val record = if (from.reason == LoadReason.PLAY) listOf(Perform(PlaybackWork.RecordPlay(from.queue.current))) else emptyList()
        val settled = if (from.playWhenReady) buffer(from, from.queue, mark) else go(from, Paused(from.queue, mark))
        return Next(settled.state, settled.effects + record + save(from.queue, mark))
    }

    private fun ended(from: PlayerState): Step =
        when (from) {
            Idle, is Loading, is Ended, is Error -> Next(from)
            is Buffering -> advance(from, from.queue, playWhenReady = true)
            is Playing -> advance(from, from.queue, playWhenReady = true)
            is Paused -> advance(from, from.queue, playWhenReady = false)
        }

    private fun failed(from: PlayerState): Step =
        when (from) {
            Idle, is Ended, is Error -> Next(from)
            // The track cannot be played: skip it, silently (owner decision, #34).
            is Loading -> advance(from, from.queue, from.playWhenReady)
            // The track was already playing: the failure is not its own, so it goes on where it was.
            is Buffering -> load(from, from.queue, from.mark, playWhenReady = true, LoadReason.RECOVER)
            is Playing -> load(from, from.queue, from.mark, playWhenReady = true, LoadReason.RECOVER)
            is Paused -> load(from, from.queue, from.mark, playWhenReady = false, LoadReason.RECOVER)
        }

    private fun loadTimedOut(from: PlayerState): Step =
        when (from) {
            Idle, is Buffering, is Playing, is Paused, is Ended, is Error -> Next(from)
            is Loading ->
                go(
                    from,
                    Error(PlaybackFailure.ENGINE_NOT_RESPONDING, from.queue),
                    ReportFailure(describe(PlaybackFailure.ENGINE_NOT_RESPONDING)),
                )
        }

    private fun bufferingGraceOver(from: PlayerState): Step =
        when (from) {
            Idle, is Loading, is Playing, is Paused, is Ended, is Error -> Next(from)
            is Buffering -> go(from, Paused(from.queue, from.mark), save(from.queue, from.mark))
        }

    private fun restored(
        from: PlayerState,
        event: PlaybackEvent.Restored,
    ): Step =
        when (from) {
            Idle -> load(from, event.queue, PlayMark.still(event.position), playWhenReady = false, LoadReason.RESTORE)
            // Something was played before the saved session was read: that wins.
            is Loading, is Buffering, is Playing, is Paused, is Ended, is Error -> Next(from)
        }

    // Moves.

    /** On to the queue's next track, or to Ended at the end of it. */
    private fun advance(
        from: PlayerState,
        queue: Queue,
        playWhenReady: Boolean,
    ): Step =
        if (queue.hasNext) {
            load(from, queue.next(), START, playWhenReady, LoadReason.PLAY)
        } else {
            go(from, Ended(queue), save(queue, START))
        }

    private fun load(
        from: PlayerState,
        queue: Queue,
        at: PlayMark,
        playWhenReady: Boolean,
        reason: LoadReason,
    ): Step =
        go(
            from,
            Loading(queue, at, playWhenReady, reason),
            StartTimer(LoadTimeout, LOAD_TIMEOUT, PlaybackEvent.LoadTimedOut),
            drive(Load(queue.current.id, at), if (playWhenReady) EngineStep.Play else EngineStep.Pause),
        )

    private fun buffer(
        from: PlayerState,
        queue: Queue,
        mark: PlayMark,
        vararg effects: PlaybackEffect,
    ): Step = go(from, Buffering(queue, mark), StartTimer(BufferingGrace, BUFFERING_GRACE, PlaybackEvent.BufferingGraceOver), *effects)

    /** Moves to [to], first cancelling the timer of the state being left when it is a different state. */
    private fun go(
        from: PlayerState,
        to: PlayerState,
        vararg effects: PlaybackEffect,
    ): Step = Next(to, leaving(from, to) + effects)

    private fun leaving(
        from: PlayerState,
        to: PlayerState,
    ): List<PlaybackEffect> =
        if (from::class == to::class) {
            emptyList()
        } else {
            when (from) {
                is Loading -> listOf(CancelTimer(LoadTimeout))
                is Buffering -> listOf(CancelTimer(BufferingGrace))
                Idle, is Playing, is Paused, is Ended, is Error -> emptyList()
            }
        }

    private fun queueOf(state: PlayerState): Queue? =
        when (state) {
            Idle -> null
            is Loading -> state.queue
            is Buffering -> state.queue
            is Playing -> state.queue
            is Paused -> state.queue
            is Ended -> state.queue
            is Error -> state.queue
        }

    /** Whether the player is meant to be playing: what moving to another track keeps. */
    private fun wantsToPlay(state: PlayerState): Boolean =
        when (state) {
            is Loading -> state.playWhenReady
            is Buffering, is Playing, is Ended, is Error -> true
            Idle, is Paused -> false
        }

    private fun drive(vararg steps: EngineStep): PlaybackEffect = Perform(Drive(steps.toList()))

    private fun save(
        queue: Queue,
        mark: PlayMark,
    ): PlaybackEffect = Perform(PlaybackWork.SaveSession(queue, mark))

    private val START = PlayMark.still(Millis.ZERO)
}

/** What the error channel's banner says about [failure]. */
fun describe(failure: PlaybackFailure): String =
    when (failure) {
        PlaybackFailure.ENGINE_NOT_RESPONDING -> "The player did not answer in time"
    }

/** What the error channel's banner says about [failure]. */
fun describe(failure: StorageFailure): String =
    when (failure) {
        StorageFailure.READ -> "Could not read the playlists or the history"
        StorageFailure.RECORD_PLAY -> "Could not add the song to the history"
        StorageFailure.SAVE_SESSION -> "Could not save where playback was left off"
        StorageFailure.SAVE_PLAYLIST -> "Could not save the playlist"
        StorageFailure.DELETE_PLAYLIST -> "Could not delete the playlist"
    }
