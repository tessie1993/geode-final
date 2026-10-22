package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.AbLoop
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.MAX_CROSSFADE
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

internal typealias Step = Next<PlayerState, PlaybackEffect>

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
 *
 * The listening tools (#35) — the queue, shuffle and repeat, crossfade and gapless, the sleep timer,
 * the A-B loop, bookmarks and the resume points of long tracks — are in [Listening].
 */
object PlaybackReducer : Reducer<PlaybackState, PlaybackEvent, PlaybackEffect> {
    override fun reduce(
        state: PlaybackState,
        event: PlaybackEvent,
    ): Next<PlaybackState, PlaybackEffect> = Listening.settle(state, event, decide(state, event))

    private fun decide(
        state: PlaybackState,
        event: PlaybackEvent,
    ): Next<PlaybackState, PlaybackEffect> =
        when (event) {
            is PlaybackEvent.Command -> command(state, event.command)
            is PlaybackEvent.PlaylistsChanged -> Next(state.copy(playlists = event.playlists))
            is PlaybackEvent.HistoryChanged -> Next(state.copy(history = event.history))
            is PlaybackEvent.PlaylistSaved -> Next(state, listOf(Emit(PlaybackFact.PlaylistSaved(event.name))))
            is PlaybackEvent.StorageFailed -> Next(state, listOf(ReportFailure(describe(event.failure))))
            is PlaybackEvent.PlaylistOpened -> Listening.play(state, event.tracks, startAt = 0)
            is PlaybackEvent.Started -> player(state, started(state.player, event.mark))
            is PlaybackEvent.Paused -> player(state, paused(state.player, event.mark))
            is PlaybackEvent.Seeked -> player(state, seeked(state.player, event.mark))
            is PlaybackEvent.TrackChanged -> Listening.trackChanged(state, event)
            PlaybackEvent.Ended -> Listening.ending(state)
            PlaybackEvent.Failed -> player(state, failed(state.player))
            PlaybackEvent.LoadTimedOut -> player(state, loadTimedOut(state.player))
            PlaybackEvent.BufferingGraceOver -> player(state, bufferingGraceOver(state.player))
            is PlaybackEvent.Restored -> player(state, restored(state.player, event))
            PlaybackEvent.SleepTicked -> Listening.sleepTicked(state)
            PlaybackEvent.LoopEndReached -> Listening.loopEndReached(state)
            is PlaybackEvent.ListeningRestored -> Listening.options(state, event.options, save = false)
            is PlaybackEvent.BookmarksChanged -> Next(state.copy(bookmarks = event.bookmarks))
            is PlaybackEvent.ResumePointsChanged -> Next(state.copy(resumePoints = event.points))
            is PlaybackEvent.SimilarPicked -> Listening.similarPicked(state, event.tracks)
        }

    internal fun player(
        state: PlaybackState,
        step: Step,
    ): Next<PlaybackState, PlaybackEffect> = Next(state.copy(player = step.state), step.effects)

    private fun command(
        state: PlaybackState,
        command: PlaybackCommand,
    ): Next<PlaybackState, PlaybackEffect> =
        when (command) {
            is PlaybackCommand.Play -> Listening.play(state, command.tracks, command.startAt, command.from)
            PlaybackCommand.Resume -> player(state, resume(state.player))
            PlaybackCommand.Pause -> player(state, pause(state.player))
            PlaybackCommand.Next -> Listening.next(state)
            PlaybackCommand.Previous -> player(state, previous(state.player))
            is PlaybackCommand.SeekTo -> player(state, seekTo(state.player, command.position))
            is PlaybackCommand.PlayPlaylist -> Next(state, listOf(Perform(PlaybackWork.OpenPlaylist(command.id))))
            is PlaybackCommand.CreatePlaylist -> createPlaylist(state, command.name, command.tracks)
            is PlaybackCommand.SaveHistoryAsPlaylist ->
                createPlaylist(state, command.name, state.history.map { it.track }.distinctBy { it.id })
            is PlaybackCommand.DeletePlaylist -> Next(state, listOf(Perform(PlaybackWork.DeletePlaylist(command.id))))
            is PlaybackCommand.PlayNext -> Listening.insert(state, command.tracks, next = true)
            is PlaybackCommand.AddToQueue -> Listening.insert(state, command.tracks, next = false)
            is PlaybackCommand.PlayQueueItem -> Listening.playQueueItem(state, command.index)
            is PlaybackCommand.RemoveFromQueue -> Listening.remove(state, command.index)
            is PlaybackCommand.MoveInQueue -> Listening.move(state, command.from, command.to)
            is PlaybackCommand.SetShuffle -> Listening.shuffle(state, command.on)
            is PlaybackCommand.SetRepeat -> Listening.options(state, state.options.copy(repeat = command.mode))
            is PlaybackCommand.SetCrossfade ->
                Listening.options(state, state.options.copy(crossfade = command.length.coerceIn(Millis.ZERO, MAX_CROSSFADE)))
            is PlaybackCommand.SetGapless -> Listening.options(state, state.options.copy(gapless = command.on))
            is PlaybackCommand.SetQueueEnd -> Listening.options(state, state.options.copy(queueEnd = command.end))
            is PlaybackCommand.StartSleepTimer -> Listening.startSleep(state, command.after, command.finishTrack)
            PlaybackCommand.SleepAfterTrack -> Listening.sleepAfterTrack(state)
            PlaybackCommand.CancelSleepTimer -> Listening.cancelSleep(state)
            is PlaybackCommand.MarkLoop -> Listening.markLoop(state, command.at)
            PlaybackCommand.ClearLoop -> Next(state.copy(loop = AbLoop.Off))
            PlaybackCommand.AddBookmark -> Listening.addBookmark(state)
            is PlaybackCommand.RemoveBookmark -> Next(state, listOf(Perform(PlaybackWork.DeleteBookmark(command.id))))
            is PlaybackCommand.GoToBookmark -> Listening.goToBookmark(state, command.id)
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

    internal fun play(
        from: PlayerState,
        tracks: List<Track>,
        startAt: Int,
        at: Millis = Millis.ZERO,
    ): Step =
        if (tracks.isEmpty()) {
            Next(from)
        } else {
            val queue = Queue(tracks, startAt.coerceIn(tracks.indices))
            load(from, queue, PlayMark.still(within(queue.current, at)), playWhenReady = true, LoadReason.PLAY)
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

    internal fun pause(from: PlayerState): Step =
        when (from) {
            Idle, is Paused, is Ended, is Error -> Next(from)
            is Loading ->
                if (from.playWhenReady) Next(from.copy(playWhenReady = false), listOf(drive(EngineStep.Pause))) else Next(from)
            is Buffering -> go(from, Paused(from.queue, from.mark), drive(EngineStep.Pause), save(from.queue, from.mark))
            is Playing -> go(from, Paused(from.queue, from.mark), drive(EngineStep.Pause), save(from.queue, from.mark))
        }

    internal fun next(from: PlayerState): Step {
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
    ): Step = Next(from, listOf(drive(Seek(within(queue.current, position)))))

    /** [position] kept inside [track]; a track whose length is not known yet only keeps it from going below zero. */
    fun within(
        track: Track,
        position: Millis,
    ): Millis = if (track.duration > Millis.ZERO) position.coerceIn(Millis.ZERO, track.duration) else position.coerceAtLeast(Millis.ZERO)

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

    internal fun trackChanged(
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

    internal fun ended(from: PlayerState): Step =
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
    internal fun advance(
        from: PlayerState,
        queue: Queue,
        playWhenReady: Boolean,
    ): Step =
        if (queue.hasNext) {
            load(from, queue.next(), START, playWhenReady, LoadReason.PLAY)
        } else {
            go(from, Ended(queue), save(queue, START))
        }

    internal fun load(
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
    internal fun go(
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

    internal fun queueOf(state: PlayerState): Queue? =
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
    internal fun wantsToPlay(state: PlayerState): Boolean =
        when (state) {
            is Loading -> state.playWhenReady
            is Buffering, is Playing, is Ended, is Error -> true
            Idle, is Paused -> false
        }

    internal fun drive(vararg steps: EngineStep): PlaybackEffect = Perform(Drive(steps.toList()))

    internal fun save(
        queue: Queue,
        mark: PlayMark,
    ): PlaybackEffect = Perform(PlaybackWork.SaveSession(queue, mark))

    internal val START = PlayMark.still(Millis.ZERO)
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
        StorageFailure.SAVE_LISTENING -> "Could not save the listening settings"
        StorageFailure.SAVE_BOOKMARK -> "Could not save the bookmark"
        StorageFailure.DELETE_BOOKMARK -> "Could not delete the bookmark"
        StorageFailure.SAVE_RESUME_POINT -> "Could not save where the long track was left off"
    }
