package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.LoadReason.RECOVER
import dev.geode.feature.playback.api.LoadReason.RESTORE
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFact
import dev.geode.feature.playback.api.PlaybackFailure.ENGINE_NOT_RESPONDING
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.PlayerState.Buffering
import dev.geode.feature.playback.api.PlayerState.Ended
import dev.geode.feature.playback.api.PlayerState.Error
import dev.geode.feature.playback.api.PlayerState.Idle
import dev.geode.feature.playback.api.PlayerState.Loading
import dev.geode.feature.playback.api.PlayerState.Paused
import dev.geode.feature.playback.api.PlayerState.Playing
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.core.EngineStep.Load
import dev.geode.feature.playback.core.EngineStep.Seek
import dev.geode.feature.playback.core.PlaybackWork.Drive
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Playback's reducer as a transition table (ADR-0003): every state against every event, then the
 * rows where a state's detail changes the answer, the three bug paths among them.
 */
class PlaybackReducerTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val c = Track(TrackId("content://c"), "Gamma", "Cy", Millis(240_000))
    private val atA = Queue(listOf(a, b, c), 0)
    private val atB = Queue(listOf(a, b, c), 1)
    private val atC = Queue(listOf(a, b, c), 2)
    private val start = PlayMark.still(Millis.ZERO)

    // Where the player was, and what it announces next.
    private val mark = PlayMark(Millis(30_000), Millis(1_000_000), moving = true)
    private val stopped = PlayMark(Millis(40_000), Millis(1_010_000), moving = false)
    private val heard = PlayMark(Millis(31_000), Millis(1_001_000), moving = true)

    private val playlist = PlaylistId(7)
    private val playlists = listOf(PlaylistSummary(playlist, "Evening", trackCount = 2))
    private val history = listOf(PlayedTrack(b, Millis(3_000)), PlayedTrack(a, Millis(2_000)), PlayedTrack(b, Millis(1_000)))

    // One of each state, and Loading both ways, since whether it plays once open changes the answers.
    private val idle = Idle
    private val loading = Loading(atB, start, playWhenReady = true, LoadReason.PLAY)
    private val loadingPaused = Loading(atB, start, playWhenReady = false, LoadReason.PLAY)
    private val buffering = Buffering(atB, mark)
    private val playing = Playing(atB, mark)
    private val paused = Paused(atB, stopped)
    private val ended = Ended(atC)
    private val error = Error(ENGINE_NOT_RESPONDING, atB)
    private val states: List<PlayerState> = listOf(idle, loading, loadingPaused, buffering, playing, paused, ended, error)

    private val play = command(PlaybackCommand.Play(listOf(a, b), startAt = 1))
    private val resume = command(PlaybackCommand.Resume)
    private val pause = command(PlaybackCommand.Pause)
    private val next = command(PlaybackCommand.Next)
    private val previous = command(PlaybackCommand.Previous)
    private val seekTo = command(PlaybackCommand.SeekTo(Millis(60_000)))
    private val playPlaylist = command(PlaybackCommand.PlayPlaylist(playlist))
    private val createPlaylist = command(PlaybackCommand.CreatePlaylist("Mix", listOf(a)))
    private val saveHistory = command(PlaybackCommand.SaveHistoryAsPlaylist("Was"))
    private val deletePlaylist = command(PlaybackCommand.DeletePlaylist(playlist))
    private val started = PlaybackEvent.Started(heard)
    private val pausedHeard = PlaybackEvent.Paused(heard)
    private val seeked = PlaybackEvent.Seeked(heard)
    private val onB = PlaybackEvent.TrackChanged(b.id, heard)
    private val restored = PlaybackEvent.Restored(atA, Millis(5_000))
    private val opened = PlaybackEvent.PlaylistOpened(listOf(c))
    private val playlistsChanged = PlaybackEvent.PlaylistsChanged(emptyList())
    private val historyChanged = PlaybackEvent.HistoryChanged(emptyList())
    private val saved = PlaybackEvent.PlaylistSaved("Mix")
    private val storageFailed = PlaybackEvent.StorageFailed(StorageFailure.SAVE_PLAYLIST)

    private val events: List<PlaybackEvent> =
        listOf(
            play,
            resume,
            pause,
            next,
            previous,
            seekTo,
            playPlaylist,
            createPlaylist,
            saveHistory,
            deletePlaylist,
            started,
            pausedHeard,
            seeked,
            onB,
            PlaybackEvent.Ended,
            PlaybackEvent.Failed,
            PlaybackEvent.LoadTimedOut,
            PlaybackEvent.BufferingGraceOver,
            restored,
            opened,
            playlistsChanged,
            historyChanged,
            saved,
            storageFailed,
        )

    private val armLoad = StartTimer(LoadTimeout, LOAD_TIMEOUT, PlaybackEvent.LoadTimedOut)
    private val armGrace = StartTimer(BufferingGrace, BUFFERING_GRACE, PlaybackEvent.BufferingGraceOver)
    private val cancelLoad = CancelTimer(LoadTimeout)
    private val cancelGrace = CancelTimer(BufferingGrace)
    private val prepareC = drive(EngineStep.PrepareNext(c.id))

    private val table: List<Row> =
        // Play: replaces the queue from anywhere.
        states.map { row(it, play, Loading(Queue(listOf(a, b), 1), start, true, LoadReason.PLAY), *leaving(it), armLoad, load(b)) } +
            listOf(
                // Resume.
                row(idle, resume, idle),
                row(loading, resume, loading),
                row(loadingPaused, resume, loading, drive(EngineStep.Play)),
                row(buffering, resume, buffering),
                row(playing, resume, playing),
                row(paused, resume, Buffering(atB, stopped), armGrace, drive(EngineStep.Play)),
                row(ended, resume, Loading(atA, start, true, LoadReason.PLAY), armLoad, load(a)),
                row(error, resume, loading, armLoad, load(b)),
                // Pause: a pause while a track opens wins (bug 2).
                row(idle, pause, idle),
                row(loading, pause, loadingPaused, drive(EngineStep.Pause)),
                row(loadingPaused, pause, loadingPaused),
                row(buffering, pause, Paused(atB, mark), cancelGrace, drive(EngineStep.Pause), save(atB, mark)),
                row(playing, pause, Paused(atB, mark), drive(EngineStep.Pause), save(atB, mark)),
                row(paused, pause, paused),
                row(ended, pause, ended),
                row(error, pause, error),
                // Next keeps whether the player meant to play.
                row(idle, next, idle),
                row(loading, next, Loading(atC, start, true, LoadReason.PLAY), armLoad, load(c)),
                row(loadingPaused, next, Loading(atC, start, false, LoadReason.PLAY), armLoad, load(c, play = false)),
                row(buffering, next, Loading(atC, start, true, LoadReason.PLAY), cancelGrace, armLoad, load(c)),
                row(playing, next, Loading(atC, start, true, LoadReason.PLAY), armLoad, load(c)),
                row(paused, next, Loading(atC, start, false, LoadReason.PLAY), armLoad, load(c, play = false)),
                row(ended, next, ended),
                row(error, next, Loading(atC, start, true, LoadReason.PLAY), armLoad, load(c)),
                // Previous.
                row(idle, previous, idle),
                row(loading, previous, Loading(atA, start, true, LoadReason.PLAY), armLoad, load(a)),
                row(loadingPaused, previous, Loading(atA, start, false, LoadReason.PLAY), armLoad, load(a, play = false)),
                row(buffering, previous, Loading(atA, start, true, LoadReason.PLAY), cancelGrace, armLoad, load(a)),
                row(playing, previous, Loading(atA, start, true, LoadReason.PLAY), armLoad, load(a)),
                row(paused, previous, Loading(atA, start, false, LoadReason.PLAY), armLoad, load(a, play = false)),
                row(ended, previous, Loading(atC, start, true, LoadReason.PLAY), armLoad, load(c)),
                row(error, previous, Loading(atA, start, true, LoadReason.PLAY), armLoad, load(a)),
                // SeekTo asks; the Seeked announcement moves the mark.
                row(idle, seekTo, idle),
                row(loading, seekTo, loading),
                row(loadingPaused, seekTo, loadingPaused),
                row(buffering, seekTo, buffering, drive(Seek(Millis(60_000)))),
                row(playing, seekTo, playing, drive(Seek(Millis(60_000)))),
                row(paused, seekTo, paused, drive(Seek(Millis(60_000)))),
                row(ended, seekTo, ended),
                row(error, seekTo, error),
                // Started: the media session is the truth.
                row(idle, started, idle),
                row(loading, started, loading),
                row(loadingPaused, started, loadingPaused, drive(EngineStep.Pause)),
                row(buffering, started, Playing(atB, heard), cancelGrace),
                row(playing, started, Playing(atB, heard)),
                row(paused, started, Playing(atB, heard)),
                row(ended, started, ended),
                row(error, started, error),
                // Paused: from Playing it may be a wait for data, so it buffers, for a while only.
                row(idle, pausedHeard, idle),
                row(loading, pausedHeard, loading),
                row(loadingPaused, pausedHeard, loadingPaused),
                row(buffering, pausedHeard, Buffering(atB, heard)),
                row(playing, pausedHeard, Buffering(atB, heard), armGrace),
                row(paused, pausedHeard, Paused(atB, heard), save(atB, heard)),
                row(ended, pausedHeard, ended),
                row(error, pausedHeard, error),
                // Seeked.
                row(idle, seeked, idle),
                row(loading, seeked, loading),
                row(loadingPaused, seeked, loadingPaused),
                row(buffering, seeked, Buffering(atB, heard), save(atB, heard)),
                row(playing, seeked, Playing(atB, heard), save(atB, heard)),
                row(paused, seeked, Paused(atB, heard), save(atB, heard)),
                row(ended, seeked, ended),
                row(error, seeked, error),
                // TrackChanged to the track being opened ends the opening, and counts as a play.
                row(idle, onB, idle),
                // With gapless on (the default), the track after it is made ready (#35).
                row(
                    loading,
                    onB,
                    Buffering(atB, heard),
                    cancelLoad,
                    armGrace,
                    Perform(PlaybackWork.RecordPlay(b)),
                    save(atB, heard),
                    prepareC,
                ),
                row(loadingPaused, onB, Paused(atB, heard), cancelLoad, Perform(PlaybackWork.RecordPlay(b)), save(atB, heard), prepareC),
                row(buffering, onB, Buffering(atB, heard)),
                row(playing, onB, Playing(atB, heard)),
                row(paused, onB, Paused(atB, heard)),
                row(ended, onB, ended),
                row(error, onB, error),
                // Ended: on to the next track.
                row(idle, PlaybackEvent.Ended, idle),
                row(loading, PlaybackEvent.Ended, loading),
                row(loadingPaused, PlaybackEvent.Ended, loadingPaused),
                row(buffering, PlaybackEvent.Ended, Loading(atC, start, true, LoadReason.PLAY), cancelGrace, armLoad, load(c)),
                row(playing, PlaybackEvent.Ended, Loading(atC, start, true, LoadReason.PLAY), armLoad, load(c)),
                row(paused, PlaybackEvent.Ended, Loading(atC, start, false, LoadReason.PLAY), armLoad, load(c, play = false)),
                row(ended, PlaybackEvent.Ended, ended),
                row(error, PlaybackEvent.Ended, error),
                // Failed: a track that cannot be opened is skipped silently; one that was playing goes on (bug 3).
                row(idle, PlaybackEvent.Failed, idle),
                row(loading, PlaybackEvent.Failed, Loading(atC, start, true, LoadReason.PLAY), armLoad, load(c)),
                row(loadingPaused, PlaybackEvent.Failed, Loading(atC, start, false, LoadReason.PLAY), armLoad, load(c, play = false)),
                row(buffering, PlaybackEvent.Failed, Loading(atB, mark, true, RECOVER), cancelGrace, armLoad, load(b, mark)),
                row(playing, PlaybackEvent.Failed, Loading(atB, mark, true, RECOVER), armLoad, load(b, mark)),
                row(paused, PlaybackEvent.Failed, Loading(atB, stopped, false, RECOVER), armLoad, load(b, stopped, play = false)),
                row(ended, PlaybackEvent.Failed, ended),
                row(error, PlaybackEvent.Failed, error),
                // LoadTimedOut: the engine itself is stuck, which is shown.
                row(idle, PlaybackEvent.LoadTimedOut, idle),
                row(loading, PlaybackEvent.LoadTimedOut, error, cancelLoad, ReportFailure("The player did not answer in time")),
                row(loadingPaused, PlaybackEvent.LoadTimedOut, error, cancelLoad, ReportFailure("The player did not answer in time")),
                row(buffering, PlaybackEvent.LoadTimedOut, buffering),
                row(playing, PlaybackEvent.LoadTimedOut, playing),
                row(paused, PlaybackEvent.LoadTimedOut, paused),
                row(ended, PlaybackEvent.LoadTimedOut, ended),
                row(error, PlaybackEvent.LoadTimedOut, error),
                // BufferingGraceOver: buffering never lasts forever (bug 1).
                row(idle, PlaybackEvent.BufferingGraceOver, idle),
                row(loading, PlaybackEvent.BufferingGraceOver, loading),
                row(loadingPaused, PlaybackEvent.BufferingGraceOver, loadingPaused),
                row(buffering, PlaybackEvent.BufferingGraceOver, Paused(atB, mark), cancelGrace, save(atB, mark)),
                row(playing, PlaybackEvent.BufferingGraceOver, playing),
                row(paused, PlaybackEvent.BufferingGraceOver, paused),
                row(ended, PlaybackEvent.BufferingGraceOver, ended),
                row(error, PlaybackEvent.BufferingGraceOver, error),
                // Restored: the app opens where it was left off, paused; anything played since wins.
                row(
                    idle,
                    restored,
                    Loading(atA, PlayMark.still(Millis(5_000)), false, RESTORE),
                    armLoad,
                    load(a, PlayMark.still(Millis(5_000)), play = false),
                ),
                row(loading, restored, loading),
                row(loadingPaused, restored, loadingPaused),
                row(buffering, restored, buffering),
                row(playing, restored, playing),
                row(paused, restored, paused),
                row(ended, restored, ended),
                row(error, restored, error),
            ) +
            // An opened playlist replaces the queue, as Play does.
            states.map { row(it, opened, Loading(Queue(listOf(c), 0), start, true, LoadReason.PLAY), *leaving(it), armLoad, load(c)) } +
            // The playlists and the history are the same whatever the player does.
            states.flatMap { state ->
                listOf(
                    row(state, playPlaylist, state, Perform(PlaybackWork.OpenPlaylist(playlist))),
                    row(state, createPlaylist, state, Perform(PlaybackWork.CreatePlaylist("Mix", listOf(a)))),
                    // Each track once, the most recent first.
                    row(state, saveHistory, state, Perform(PlaybackWork.CreatePlaylist("Was", listOf(b, a)))),
                    row(state, deletePlaylist, state, Perform(PlaybackWork.DeletePlaylist(playlist))),
                    Row(state, playlistsChanged, Next(PlaybackState(state, emptyList(), history))),
                    Row(state, historyChanged, Next(PlaybackState(state, playlists, emptyList()))),
                    row(state, saved, state, Emit(PlaybackFact.PlaylistSaved("Mix"))),
                    row(state, storageFailed, state, ReportFailure("Could not save the playlist")),
                )
            }

    /** Rows where the detail of a state, not only its kind, changes the answer. */
    private val details: List<Row> =
        listOf(
            // Next and Previous at the ends of the queue.
            row(Playing(atC, mark), next, Playing(atC, mark)),
            row(Playing(atA, mark), previous, Playing(atA, mark), drive(Seek(Millis.ZERO))),
            row(Buffering(atA, mark), previous, Buffering(atA, mark), drive(Seek(Millis.ZERO))),
            row(Paused(atA, stopped), previous, Paused(atA, stopped), drive(Seek(Millis.ZERO))),
            row(Loading(atA, start, true, LoadReason.PLAY), previous, Loading(atA, start, true, LoadReason.PLAY)),
            row(Error(ENGINE_NOT_RESPONDING, atA), previous, Loading(atA, start, true, LoadReason.PLAY), armLoad, load(a)),
            // Seeks stay inside the track.
            row(playing, command(PlaybackCommand.SeekTo(Millis(-5))), playing, drive(Seek(Millis.ZERO))),
            row(playing, command(PlaybackCommand.SeekTo(Millis(999_999))), playing, drive(Seek(b.duration))),
            // Play starts inside the queue, and nothing happens without tracks.
            row(
                idle,
                command(PlaybackCommand.Play(listOf(a, b), startAt = 7)),
                Loading(Queue(listOf(a, b), 1), start, true, LoadReason.PLAY),
                armLoad,
                load(b),
            ),
            row(
                idle,
                command(PlaybackCommand.Play(listOf(a, b), startAt = -1)),
                Loading(Queue(listOf(a, b), 0), start, true, LoadReason.PLAY),
                armLoad,
                load(a),
            ),
            row(playing, command(PlaybackCommand.Play(emptyList())), playing),
            row(playing, PlaybackEvent.PlaylistOpened(emptyList()), playing),
            // A late word about another track changes nothing.
            row(loading, PlaybackEvent.TrackChanged(a.id, heard), loading),
            row(loading, PlaybackEvent.TrackChanged(null, heard), loading),
            row(buffering, PlaybackEvent.TrackChanged(a.id, heard), buffering),
            row(playing, PlaybackEvent.TrackChanged(null, heard), playing),
            row(paused, PlaybackEvent.TrackChanged(a.id, heard), paused),
            // Only a play counts in the history: not a recovery, not the app opening where it was left off.
            row(Loading(atB, start, false, RESTORE), onB, Paused(atB, heard), cancelLoad, save(atB, heard), prepareC),
            row(Loading(atB, mark, true, RECOVER), onB, Buffering(atB, heard), cancelLoad, armGrace, save(atB, heard), prepareC),
            // The queue's last track ending, or failing to open, ends the queue.
            row(Playing(atC, mark), PlaybackEvent.Ended, Ended(atC), save(atC, start)),
            row(Buffering(atC, mark), PlaybackEvent.Ended, Ended(atC), cancelGrace, save(atC, start)),
            row(Paused(atC, stopped), PlaybackEvent.Ended, Ended(atC), save(atC, start)),
            row(Loading(atC, start, true, LoadReason.PLAY), PlaybackEvent.Failed, Ended(atC), cancelLoad, save(atC, start)),
            // A track that fails again while it is reloaded is skipped: the music goes on.
            row(Loading(atB, mark, true, RECOVER), PlaybackEvent.Failed, Loading(atC, start, true, LoadReason.PLAY), armLoad, load(c)),
            // A name is needed, and something to put in the playlist.
            row(
                idle,
                command(PlaybackCommand.CreatePlaylist("  Mix ", listOf(a))),
                idle,
                Perform(PlaybackWork.CreatePlaylist("Mix", listOf(a))),
            ),
            row(idle, command(PlaybackCommand.CreatePlaylist("  ", listOf(a))), idle),
            row(idle, command(PlaybackCommand.CreatePlaylist("Mix", emptyList())), idle),
            row(idle, command(PlaybackCommand.SaveHistoryAsPlaylist(" ")), idle),
        )

    @Test
    fun `every state handles every event as the table says`() {
        for (row in table + details) {
            val before = PlaybackState(row.state, playlists, history)
            assertEquals("${row.state} + ${row.event}", row.next, PlaybackReducer.reduce(before, row.event))
        }
    }

    @Test
    fun `the table covers every state against every event`() {
        val covered = table.map { kind(it.state) to it.event }.toSet()
        val missing = states.flatMap { state -> events.map { kind(state) to it } }.filterNot { it in covered }

        assertEquals(emptyList<Pair<String, PlaybackEvent>>(), missing)
    }

    @Test
    fun `saving an empty history saves nothing`() {
        val next = PlaybackReducer.reduce(PlaybackState(playing, playlists, emptyList()), saveHistory)

        assertEquals(Next<PlaybackState, PlaybackEffect>(PlaybackState(playing, playlists, emptyList())), next)
    }

    @Test
    fun `the playlists and the history that changed are kept`() {
        val changed = listOf(PlaylistSummary(PlaylistId(9), "Night", trackCount = 4))

        assertEquals(changed, PlaybackReducer.reduce(PlaybackState(), PlaybackEvent.PlaylistsChanged(changed)).state.playlists)
        assertEquals(history, PlaybackReducer.reduce(PlaybackState(), PlaybackEvent.HistoryChanged(history)).state.history)
    }

    @Test
    fun `every storage failure is reported in words`() {
        val reported = StorageFailure.entries.map { PlaybackReducer.reduce(PlaybackState(), PlaybackEvent.StorageFailed(it)).effects }

        assertEquals(
            listOf(
                "Could not read the playlists or the history",
                "Could not add the song to the history",
                "Could not save where playback was left off",
                "Could not save the playlist",
                "Could not delete the playlist",
                "Could not save the listening settings",
                "Could not save the bookmark",
                "Could not delete the bookmark",
                "Could not save where the long track was left off",
            ).map { listOf(ReportFailure(it)) },
            reported,
        )
    }

    private fun kind(state: PlayerState): String =
        when (state) {
            is Loading -> "Loading(playWhenReady=${state.playWhenReady})"
            else -> state::class.simpleName.orEmpty()
        }

    private fun command(command: PlaybackCommand) = PlaybackEvent.Command(command)

    private fun leaving(state: PlayerState): Array<PlaybackEffect> = if (state is Buffering) arrayOf(cancelGrace) else emptyArray()

    private fun drive(vararg steps: EngineStep): PlaybackEffect = Perform(Drive(steps.toList()))

    private fun load(
        track: Track,
        from: PlayMark = start,
        play: Boolean = true,
    ): PlaybackEffect = drive(Load(track.id, from), if (play) EngineStep.Play else EngineStep.Pause)

    private fun save(
        queue: Queue,
        mark: PlayMark,
    ): PlaybackEffect = Perform(PlaybackWork.SaveSession(queue, mark))

    private fun row(
        state: PlayerState,
        event: PlaybackEvent,
        next: PlayerState,
        vararg effects: PlaybackEffect,
    ) = Row(state, event, Next(PlaybackState(next, playlists, history), effects.toList()))

    private class Row(
        val state: PlayerState,
        val event: PlaybackEvent,
        val next: Next<PlaybackState, PlaybackEffect>,
    )
}
