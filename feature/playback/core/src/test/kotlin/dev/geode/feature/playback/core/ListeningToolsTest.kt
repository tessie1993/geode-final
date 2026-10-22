package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.AbLoop
import dev.geode.feature.playback.api.Bookmark
import dev.geode.feature.playback.api.BookmarkId
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.LoadReason.RECOVER
import dev.geode.feature.playback.api.LoadReason.RESTORE
import dev.geode.feature.playback.api.MAX_CROSSFADE
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFailure.ENGINE_NOT_RESPONDING
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
import dev.geode.feature.playback.api.QueueEnd
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.ShuffleOrder
import dev.geode.feature.playback.api.SleepTimer
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.core.EngineStep.Load
import dev.geode.feature.playback.core.EngineStep.PrepareNext
import dev.geode.feature.playback.core.EngineStep.Seek
import dev.geode.feature.playback.core.EngineStep.Transitions
import dev.geode.feature.playback.core.PlaybackWork.Drive
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.StartTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The listening tools (#35) as transition tables (ADR-0003): every tool's Command and event against
 * every state of the player, then the rows where the detail of the state changes the answer.
 *
 * Gapless is off in the tables, so that the track that follows is made ready only in the rows about it.
 */
class ListeningToolsTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val c = Track(TrackId("content://c"), "Gamma", "Cy", Millis(240_000))
    private val long = Track(TrackId("content://long"), "Lecture", "Lu", Millis(3_600_000))
    private val abc = listOf(a, b, c)
    private val atA = Queue(abc, 0)
    private val atB = Queue(abc, 1)
    private val atC = Queue(abc, 2)
    private val longFirst = Queue(listOf(long, a), 0)
    private val start = PlayMark.still(Millis.ZERO)

    private val mark = PlayMark(Millis(30_000), Millis(1_000_000), moving = true)
    private val stopped = PlayMark(Millis(40_000), Millis(1_010_000), moving = false)
    private val heard = PlayMark(Millis(31_000), Millis(1_001_000), moving = true)

    private val idle = Idle
    private val loading = Loading(atB, start, playWhenReady = true, LoadReason.PLAY)
    private val loadingPaused = Loading(atB, start, playWhenReady = false, LoadReason.PLAY)
    private val buffering = Buffering(atB, mark)
    private val playing = Playing(atB, mark)
    private val paused = Paused(atB, stopped)
    private val ended = Ended(atC)
    private val error = Error(ENGINE_NOT_RESPONDING, atB)
    private val states: List<PlayerState> = listOf(idle, loading, loadingPaused, buffering, playing, paused, ended, error)

    private val quiet = ListeningOptions(gapless = false)
    private val joined = ListeningOptions()
    private val onA = Bookmark(BookmarkId(1), a, Millis(42_000))
    private val onB = Bookmark(BookmarkId(2), b, Millis(90_000))
    private val base = PlaybackState(options = quiet, bookmarks = listOf(onA, onB))

    private val armLoad = StartTimer(LoadTimeout, LOAD_TIMEOUT, PlaybackEvent.LoadTimedOut)
    private val armGrace = StartTimer(BufferingGrace, BUFFERING_GRACE, PlaybackEvent.BufferingGraceOver)
    private val cancelLoad = CancelTimer(LoadTimeout)
    private val cancelLoop = CancelTimer(LoopEnd)
    private val cancelSleep = CancelTimer(SleepTick)

    private val restoredOptions = ListeningOptions(repeat = RepeatMode.ALL, gapless = false)
    private val points = mapOf(long.id to Millis(1_500_000))

    // One of each tool's Commands and events, as the tables send them.
    private val playNext = command(PlaybackCommand.PlayNext(listOf(long)))
    private val addToQueue = command(PlaybackCommand.AddToQueue(listOf(long)))
    private val playFirst = command(PlaybackCommand.PlayQueueItem(0))
    private val removeFirst = command(PlaybackCommand.RemoveFromQueue(0))
    private val moveFirstToLast = command(PlaybackCommand.MoveInQueue(0, 2))
    private val shuffleOn = command(PlaybackCommand.SetShuffle(true))
    private val repeatAll = command(PlaybackCommand.SetRepeat(RepeatMode.ALL))
    private val crossfade = command(PlaybackCommand.SetCrossfade(Millis(3_000)))
    private val gaplessOn = command(PlaybackCommand.SetGapless(true))
    private val keepGoing = command(PlaybackCommand.SetQueueEnd(QueueEnd.SIMILAR))
    private val sleepIn30 = command(PlaybackCommand.StartSleepTimer(Millis(1_800_000)))
    private val sleepAfterTrack = command(PlaybackCommand.SleepAfterTrack)
    private val cancelSleepTimer = command(PlaybackCommand.CancelSleepTimer)
    private val markLoop = command(PlaybackCommand.MarkLoop(Millis(50_000)))
    private val clearLoop = command(PlaybackCommand.ClearLoop)
    private val addBookmark = command(PlaybackCommand.AddBookmark)
    private val removeBookmark = command(PlaybackCommand.RemoveBookmark(BookmarkId(7)))
    private val goToA = command(PlaybackCommand.GoToBookmark(onA.id))
    private val similar = PlaybackEvent.SimilarPicked(listOf(long))
    private val restored = PlaybackEvent.ListeningRestored(restoredOptions)
    private val bookmarksChanged = PlaybackEvent.BookmarksChanged(emptyList())
    private val pointsChanged = PlaybackEvent.ResumePointsChanged(points)

    private val events: List<PlaybackEvent> =
        listOf(
            playNext,
            addToQueue,
            playFirst,
            removeFirst,
            moveFirstToLast,
            shuffleOn,
            repeatAll,
            crossfade,
            gaplessOn,
            keepGoing,
            sleepIn30,
            sleepAfterTrack,
            cancelSleepTimer,
            PlaybackEvent.SleepTicked,
            markLoop,
            clearLoop,
            PlaybackEvent.LoopEndReached,
            addBookmark,
            removeBookmark,
            goToA,
            similar,
            restored,
            bookmarksChanged,
            pointsChanged,
        )

    private val table: List<Row> =
        states.flatMap { state ->
            val queue = queueOf(state)
            val settled = state is Buffering || state is Playing || state is Paused
            val meantToPlay = state == loading || state is Buffering || state is Playing
            val readyC = if (settled) arrayOf(drive(PrepareNext(c.id))) else emptyArray<PlaybackEffect>()
            listOf(
                // The queue: with nothing playing, adding plays.
                if (queue == null) {
                    row(state, playNext, Loading(Queue(listOf(long), 0), start, true, LoadReason.PLAY), armLoad, load(long))
                } else {
                    requeued(
                        state,
                        playNext,
                        Queue(queue.tracks.take(queue.index + 1) + long + queue.tracks.drop(queue.index + 1), queue.index),
                    )
                },
                if (queue == null) {
                    row(state, addToQueue, Loading(Queue(listOf(long), 0), start, true, LoadReason.PLAY), armLoad, load(long))
                } else {
                    requeued(state, addToQueue, Queue(queue.tracks + long, queue.index))
                },
                if (queue == null) {
                    row(state, playFirst, state)
                } else {
                    row(state, playFirst, Loading(atA, start, true, LoadReason.PLAY), *leaving(state), armLoad, load(a))
                },
                if (queue == null) {
                    row(state, removeFirst, state)
                } else {
                    requeued(state, removeFirst, Queue(listOf(b, c), queue.index - 1))
                },
                if (queue == null) {
                    row(state, moveFirstToLast, state)
                } else {
                    requeued(state, moveFirstToLast, Queue(listOf(b, c, a), queue.index - 1))
                },
                shuffledRow(state),
                // The choices are saved, whatever plays.
                Row(
                    with(state),
                    repeatAll,
                    Next(with(state, quiet.copy(repeat = RepeatMode.ALL)), listOf(saveOptions(quiet.copy(repeat = RepeatMode.ALL)))),
                ),
                Row(
                    with(state),
                    crossfade,
                    Next(
                        with(state, quiet.copy(crossfade = Millis(3_000))),
                        listOf(saveOptions(quiet.copy(crossfade = Millis(3_000))), drive(Transitions(Millis(3_000), gapless = false))) +
                            readyC,
                    ),
                ),
                Row(
                    with(state),
                    gaplessOn,
                    Next(with(state, joined), listOf(saveOptions(joined), drive(Transitions(Millis.ZERO, gapless = true))) + readyC),
                ),
                Row(
                    with(state),
                    keepGoing,
                    Next(
                        with(state, quiet.copy(queueEnd = QueueEnd.SIMILAR)),
                        listOf(saveOptions(quiet.copy(queueEnd = QueueEnd.SIMILAR))),
                    ),
                ),
                // The sleep timer counts down whatever plays; after the track only while something is meant to play.
                Row(
                    with(state),
                    sleepIn30,
                    Next(with(state).copy(sleep = SleepTimer.Counting(Millis(1_800_000), false)), listOf(tick(SLEEP_TICK))),
                ),
                Row(
                    with(state),
                    sleepAfterTrack,
                    Next(with(state).copy(sleep = if (meantToPlay) SleepTimer.AfterTrack else SleepTimer.Off)),
                ),
                row(state, cancelSleepTimer, state),
                row(state, PlaybackEvent.SleepTicked, state),
                // The A-B loop is marked in an open track.
                if (settled) {
                    Row(with(state), markLoop, Next(with(state).copy(loop = AbLoop.Starting(b.id, Millis(50_000)))))
                } else {
                    row(state, markLoop, state)
                },
                row(state, clearLoop, state),
                row(state, PlaybackEvent.LoopEndReached, state),
                // Bookmarks.
                if (settled) {
                    row(state, addBookmark, state, Perform(PlaybackWork.AddBookmark(b, markOf(state))))
                } else {
                    row(state, addBookmark, state)
                },
                row(state, removeBookmark, state, Perform(PlaybackWork.DeleteBookmark(BookmarkId(7)))),
                if (queue == null) {
                    row(
                        state,
                        goToA,
                        Loading(Queue(listOf(a), 0), PlayMark.still(onA.position), true, LoadReason.PLAY),
                        armLoad,
                        load(a, PlayMark.still(onA.position)),
                    )
                } else {
                    val at = queue.index + 1
                    row(
                        state,
                        goToA,
                        Loading(
                            Queue(queue.tracks.take(at) + a + queue.tracks.drop(at), at),
                            PlayMark.still(onA.position),
                            true,
                            LoadReason.PLAY,
                        ),
                        *leaving(state),
                        armLoad,
                        load(a, PlayMark.still(onA.position)),
                    )
                },
                // Similar songs go on only from the end of the queue.
                if (state is Ended) {
                    row(state, similar, Loading(Queue(abc + long, 3), start, true, LoadReason.PLAY), armLoad, load(long))
                } else {
                    row(state, similar, state)
                },
                Row(with(state), restored, Next(with(state, restoredOptions), listOf(drive(Transitions(Millis.ZERO, gapless = false))))),
                Row(with(state), bookmarksChanged, Next(with(state).copy(bookmarks = emptyList()))),
                Row(with(state), pointsChanged, Next(with(state).copy(resumePoints = points))),
            )
        }

    private val details: List<Row> =
        listOf(
            // The queue: out of range, the current track, and empty lists change nothing.
            row(playing, command(PlaybackCommand.PlayQueueItem(9)), playing),
            row(playing, command(PlaybackCommand.PlayQueueItem(-1)), playing),
            row(playing, command(PlaybackCommand.RemoveFromQueue(1)), playing),
            row(playing, command(PlaybackCommand.RemoveFromQueue(9)), playing),
            row(playing, command(PlaybackCommand.RemoveFromQueue(-1)), playing),
            row(playing, command(PlaybackCommand.PlayNext(emptyList())), playing),
            row(playing, command(PlaybackCommand.AddToQueue(emptyList())), playing),
            row(playing, command(PlaybackCommand.MoveInQueue(0, 0)), playing),
            row(playing, command(PlaybackCommand.MoveInQueue(0, 9)), playing),
            row(playing, command(PlaybackCommand.MoveInQueue(9, 0)), playing),
            row(playing, command(PlaybackCommand.MoveInQueue(-1, 0)), playing),
            row(playing, command(PlaybackCommand.MoveInQueue(0, -1)), playing),
            // Removing after the current track, and moving tracks around it, keep the current track.
            requeued(playing, command(PlaybackCommand.RemoveFromQueue(2)), Queue(listOf(a, b), 1)),
            requeued(playing, command(PlaybackCommand.MoveInQueue(1, 0)), Queue(listOf(b, a, c), 0)),
            requeued(playing, command(PlaybackCommand.MoveInQueue(2, 0)), Queue(listOf(c, a, b), 2)),
            requeued(playing, command(PlaybackCommand.MoveInQueue(1, 2)), Queue(listOf(a, c, b), 2)),
            requeued(Playing(atA, mark), command(PlaybackCommand.MoveInQueue(1, 2)), Queue(listOf(a, c, b), 0)),
            requeued(Playing(atC, mark), command(PlaybackCommand.MoveInQueue(0, 1)), Queue(listOf(b, a, c), 2)),
            // With gapless, the track that follows stays ready as the queue changes.
            Row(
                with(playing, joined),
                command(PlaybackCommand.RemoveFromQueue(2)),
                Next(
                    with(Playing(Queue(listOf(a, b), 1), mark), joined),
                    listOf(save(Queue(listOf(a, b), 1), mark), drive(PrepareNext(null))),
                ),
            ),
            Row(
                with(playing, joined),
                command(PlaybackCommand.PlayNext(listOf(long))),
                Next(
                    with(Playing(Queue(listOf(a, b, long, c), 1), mark), joined),
                    listOf(save(Queue(listOf(a, b, long, c), 1), mark), drive(PrepareNext(long.id))),
                ),
            ),
            Row(
                with(playing, joined),
                command(PlaybackCommand.RemoveFromQueue(0)),
                Next(
                    with(Playing(Queue(listOf(b, c), 0), mark), joined),
                    listOf(save(Queue(listOf(b, c), 0), mark), drive(PrepareNext(c.id))),
                ),
            ),
            Row(
                with(playing, joined),
                command(PlaybackCommand.SetRepeat(RepeatMode.ONE)),
                Next(
                    with(playing, joined.copy(repeat = RepeatMode.ONE)),
                    listOf(saveOptions(joined.copy(repeat = RepeatMode.ONE)), drive(PrepareNext(null))),
                ),
            ),
            Row(
                with(Playing(atC, mark), joined),
                command(PlaybackCommand.SetRepeat(RepeatMode.ALL)),
                Next(
                    with(Playing(atC, mark), joined.copy(repeat = RepeatMode.ALL)),
                    listOf(saveOptions(joined.copy(repeat = RepeatMode.ALL)), drive(PrepareNext(a.id))),
                ),
            ),
            Row(
                with(Playing(Queue(listOf(a), 0), mark), joined),
                command(PlaybackCommand.SetRepeat(RepeatMode.ALL)),
                Next(
                    with(Playing(Queue(listOf(a), 0), mark), joined.copy(repeat = RepeatMode.ALL)),
                    listOf(saveOptions(joined.copy(repeat = RepeatMode.ALL))),
                ),
            ),
            // Choices that are already so change nothing; the crossfade stays within its range.
            row(playing, command(PlaybackCommand.SetGapless(false)), playing),
            row(playing, command(PlaybackCommand.SetShuffle(false)), playing),
            row(playing, command(PlaybackCommand.SetCrossfade(Millis(-5))), playing),
            Row(
                with(playing),
                command(PlaybackCommand.SetCrossfade(Millis(60_000))),
                Next(
                    with(playing, quiet.copy(crossfade = MAX_CROSSFADE)),
                    listOf(
                        saveOptions(quiet.copy(crossfade = MAX_CROSSFADE)),
                        drive(Transitions(MAX_CROSSFADE, false)),
                        drive(PrepareNext(c.id)),
                    ),
                ),
            ),
            Row(
                with(playing, quiet.copy(crossfade = Millis(2_000))),
                command(PlaybackCommand.SetQueueEnd(QueueEnd.SIMILAR)),
                Next(
                    with(playing, quiet.copy(crossfade = Millis(2_000), queueEnd = QueueEnd.SIMILAR)),
                    listOf(saveOptions(quiet.copy(crossfade = Millis(2_000), queueEnd = QueueEnd.SIMILAR))),
                ),
            ),
            // Options read back are applied as they are, and joining tracks makes the next one ready.
            Row(with(playing), PlaybackEvent.ListeningRestored(quiet), Next(with(playing), listOf(drive(Transitions(Millis.ZERO, false))))),
            Row(
                with(playing),
                PlaybackEvent.ListeningRestored(joined),
                Next(with(playing, joined), listOf(drive(Transitions(Millis.ZERO, true)), drive(PrepareNext(c.id)))),
            ),
            // Shuffle off puts the queue back in its order, tracks added since at the end.
            Row(
                with(Playing(Queue(listOf(c, a, b), 0), mark), quiet.copy(shuffle = true)).copy(shuffle = ShuffleOrder(5, abc)),
                command(PlaybackCommand.SetShuffle(false)),
                Next(with(Playing(atC, mark)).copy(shuffle = ShuffleOrder(5, abc)), listOf(saveOptions(quiet), save(atC, mark))),
            ),
            Row(
                with(Playing(Queue(listOf(long, c, a, b), 1), mark), quiet.copy(shuffle = true)).copy(shuffle = ShuffleOrder(5, abc)),
                command(PlaybackCommand.SetShuffle(false)),
                Next(
                    with(Playing(Queue(listOf(a, b, c, long), 2), mark)).copy(shuffle = ShuffleOrder(5, abc)),
                    listOf(saveOptions(quiet), save(Queue(listOf(a, b, c, long), 2), mark)),
                ),
            ),
            Row(
                with(idle, quiet.copy(shuffle = true)),
                command(PlaybackCommand.SetShuffle(true)),
                Next(with(idle, quiet.copy(shuffle = true))),
            ),
            // With shuffle on, what is played is shuffled with the chosen track first.
            shuffledPlay(PlaybackCommand.Play(abc, startAt = 1), 1),
            shuffledPlay(PlaybackCommand.Play(abc, startAt = 9), 2),
            shuffledPlay(PlaybackCommand.PlayNext(abc), 0),
            Row(
                with(idle, quiet.copy(shuffle = true)),
                command(PlaybackCommand.Play(emptyList())),
                Next(with(idle, quiet.copy(shuffle = true))),
            ),
            // Repeat all goes round from the last track; Next in the middle goes on as ever.
            row(Playing(atC, mark), PlaybackCommand.Next, Loading(atA, start, true, LoadReason.PLAY), armLoad, load(a), options = all()),
            row(
                Paused(atC, stopped),
                PlaybackCommand.Next,
                Loading(atA, start, false, LoadReason.PLAY),
                armLoad,
                load(a, play = false),
                options = all(),
            ),
            row(playing, PlaybackCommand.Next, Loading(atC, start, true, LoadReason.PLAY), armLoad, load(c), options = all()),
            row(idle, PlaybackCommand.Next, idle, options = all()),
            row(Playing(atC, mark), PlaybackCommand.Next, Playing(atC, mark)),
            // A track ending: repeat one plays it again, repeat all goes round, otherwise on.
            row(
                playing,
                PlaybackEvent.Ended,
                Loading(atB, start, true, LoadReason.PLAY),
                armLoad,
                load(b),
                options = quiet.copy(repeat = RepeatMode.ONE),
            ),
            row(
                paused,
                PlaybackEvent.Ended,
                Loading(atB, start, false, LoadReason.PLAY),
                armLoad,
                load(b, play = false),
                options = quiet.copy(repeat = RepeatMode.ONE),
            ),
            row(Playing(atC, mark), PlaybackEvent.Ended, Loading(atA, start, true, LoadReason.PLAY), armLoad, load(a), options = all()),
            row(playing, PlaybackEvent.Ended, Loading(atC, start, true, LoadReason.PLAY), armLoad, load(c), options = all()),
            row(idle, PlaybackEvent.Ended, idle, options = all()),
            row(loading, PlaybackEvent.Ended, loading, options = all()),
            // The queue running out while playing, repeat off, keeps going with similar songs when that is the setting.
            row(Playing(atC, mark), PlaybackEvent.Ended, Ended(atC), save(atC, start), pickSimilar(atC), options = similar()),
            row(
                Buffering(atC, mark),
                PlaybackEvent.Ended,
                Ended(atC),
                CancelTimer(BufferingGrace),
                save(atC, start),
                pickSimilar(atC),
                options = similar(),
            ),
            row(Paused(atC, stopped), PlaybackEvent.Ended, Ended(atC), save(atC, start), options = similar()),
            row(Playing(atC, mark), PlaybackEvent.Ended, Ended(atC), save(atC, start)),
            row(
                Playing(atC, mark),
                PlaybackEvent.Ended,
                Loading(atC, start, true, LoadReason.PLAY),
                armLoad,
                load(c),
                options = similar().copy(repeat = RepeatMode.ONE),
            ),
            row(
                Loading(atC, start, true, LoadReason.PLAY),
                PlaybackEvent.Failed,
                Ended(atC),
                cancelLoad,
                save(atC, start),
                pickSimilar(atC),
                options = similar(),
            ),
            row(
                Loading(atC, start, false, LoadReason.PLAY),
                PlaybackEvent.Failed,
                Ended(atC),
                cancelLoad,
                save(atC, start),
                options = similar(),
            ),
            // A track that fails at the end of the queue does not go round: a queue that cannot play stops.
            row(
                Loading(atC, start, true, LoadReason.PLAY),
                PlaybackEvent.Failed,
                Ended(atC),
                cancelLoad,
                save(atC, start),
                options = all(),
            ),
            // Pausing after the track: the next one opens paused, and the timer is done.
            Row(
                with(playing).copy(sleep = SleepTimer.AfterTrack),
                PlaybackEvent.Ended,
                Next(with(Loading(atC, start, false, LoadReason.PLAY)), listOf(armLoad, load(c, play = false))),
            ),
            Row(
                with(Playing(atC, mark), similar()).copy(sleep = SleepTimer.AfterTrack),
                PlaybackEvent.Ended,
                Next(with(ended, similar()), listOf(save(atC, start))),
            ),
            // A long track heard to its end starts from the top next time.
            Row(
                with(Playing(longFirst, mark)).copy(resumePoints = points),
                PlaybackEvent.Ended,
                Next(with(Loading(longFirst.next(), start, true, LoadReason.PLAY)), listOf(armLoad, load(a), forget(long))),
            ),
            // Similar songs that do not come, or come late, change nothing.
            row(ended, PlaybackEvent.SimilarPicked(emptyList()), ended),
            // The player moving on by itself to the track that follows: a gapless join or a crossfade.
            Row(
                with(playing, joined),
                PlaybackEvent.TrackChanged(c.id, heard),
                Next(with(Playing(atC, heard), joined), listOf(record(c), save(atC, heard), drive(PrepareNext(null)))),
            ),
            Row(
                with(buffering, joined),
                PlaybackEvent.TrackChanged(c.id, heard),
                Next(with(Buffering(atC, heard), joined), listOf(record(c), save(atC, heard), drive(PrepareNext(null)))),
            ),
            Row(
                with(paused, joined),
                PlaybackEvent.TrackChanged(c.id, heard),
                Next(with(Paused(atC, heard), joined), listOf(record(c), save(atC, heard), drive(PrepareNext(null)))),
            ),
            Row(
                with(Playing(atC, mark), joined.copy(repeat = RepeatMode.ALL)),
                PlaybackEvent.TrackChanged(a.id, heard),
                Next(
                    with(Playing(atA, heard), joined.copy(repeat = RepeatMode.ALL)),
                    listOf(record(a), save(atA, heard), drive(PrepareNext(b.id))),
                ),
            ),
            Row(
                with(playing, joined).copy(sleep = SleepTimer.AfterTrack),
                PlaybackEvent.TrackChanged(c.id, heard),
                Next(
                    with(Playing(atC, heard), joined),
                    listOf(record(c), save(atC, heard), drive(EngineStep.Pause), drive(PrepareNext(null))),
                ),
            ),
            Row(
                with(Playing(longFirst, mark), joined).copy(resumePoints = points),
                PlaybackEvent.TrackChanged(a.id, heard),
                Next(
                    with(Playing(longFirst.next(), heard), joined),
                    listOf(record(a), save(longFirst.next(), heard), forget(long), drive(PrepareNext(null))),
                ),
            ),
            // Not the track that follows, or not joining tracks: a late word, as before.
            row(playing, PlaybackEvent.TrackChanged(c.id, heard), playing),
            Row(with(playing, joined), PlaybackEvent.TrackChanged(a.id, heard), Next(with(playing, joined))),
            Row(
                with(playing, joined.copy(repeat = RepeatMode.ONE)),
                PlaybackEvent.TrackChanged(c.id, heard),
                Next(with(playing, joined.copy(repeat = RepeatMode.ONE))),
            ),
            Row(with(Playing(atC, mark), joined), PlaybackEvent.TrackChanged(a.id, heard), Next(with(Playing(atC, mark), joined))),
            Row(
                with(Playing(Queue(listOf(a), 0), mark), joined.copy(repeat = RepeatMode.ALL)),
                PlaybackEvent.TrackChanged(a.id, heard),
                Next(with(Playing(Queue(listOf(a), 0), heard), joined.copy(repeat = RepeatMode.ALL))),
            ),
            Row(with(loading, joined), PlaybackEvent.TrackChanged(c.id, heard), Next(with(loading, joined))),
            Row(
                with(Playing(Queue(listOf(a, b, a), 0), mark), joined),
                PlaybackEvent.TrackChanged(b.id, heard),
                Next(
                    with(Playing(Queue(listOf(a, b, a), 1), heard), joined),
                    listOf(record(b), save(Queue(listOf(a, b, a), 1), heard), drive(PrepareNext(a.id))),
                ),
            ),
            Row(
                with(Playing(Queue(listOf(a, b, b), 0), mark), joined),
                PlaybackEvent.TrackChanged(b.id, heard),
                Next(
                    with(Playing(Queue(listOf(a, b, b), 1), heard), joined),
                    listOf(record(b), save(Queue(listOf(a, b, b), 1), heard), drive(PrepareNext(b.id))),
                ),
            ),
            // The sleep timer: it only starts with time to count, and counts down a minute at a time.
            row(playing, command(PlaybackCommand.StartSleepTimer(Millis.ZERO)), playing),
            row(playing, command(PlaybackCommand.StartSleepTimer(Millis(-1))), playing),
            Row(
                with(playing),
                command(PlaybackCommand.StartSleepTimer(Millis(30_000), finishTrack = true)),
                Next(with(playing).copy(sleep = SleepTimer.Counting(Millis(30_000), true)), listOf(tick(Millis(30_000)))),
            ),
            sleeping(
                playing,
                SleepTimer.Counting(Millis(180_000), false),
                playing,
                SleepTimer.Counting(Millis(120_000), false),
                tick(SLEEP_TICK),
            ),
            sleeping(
                playing,
                SleepTimer.Counting(Millis(90_000), false),
                playing,
                SleepTimer.Counting(Millis(30_000), false),
                tick(Millis(30_000)),
            ),
            sleeping(
                playing,
                SleepTimer.Counting(Millis(60_000), false),
                Paused(atB, mark),
                SleepTimer.Off,
                drive(EngineStep.Pause),
                save(atB, mark),
            ),
            sleeping(
                playing,
                SleepTimer.Counting(Millis(30_000), false),
                Paused(atB, mark),
                SleepTimer.Off,
                drive(EngineStep.Pause),
                save(atB, mark),
            ),
            sleeping(paused, SleepTimer.Counting(Millis(30_000), false), paused, SleepTimer.Off),
            sleeping(playing, SleepTimer.Counting(Millis(30_000), true), playing, SleepTimer.AfterTrack),
            sleeping(paused, SleepTimer.Counting(Millis(30_000), true), paused, SleepTimer.Off),
            Row(
                with(playing).copy(sleep = SleepTimer.Counting(Millis(300_000), false)),
                sleepAfterTrack,
                Next(with(playing).copy(sleep = SleepTimer.AfterTrack), listOf(cancelSleep)),
            ),
            Row(
                with(playing).copy(sleep = SleepTimer.Counting(Millis(300_000), false)),
                cancelSleepTimer,
                Next(with(playing), listOf(cancelSleep)),
            ),
            Row(with(playing).copy(sleep = SleepTimer.AfterTrack), cancelSleepTimer, Next(with(playing))),
            Row(
                with(playing).copy(sleep = SleepTimer.AfterTrack),
                command(PlaybackCommand.Pause),
                Next(with(Paused(atB, mark)), listOf(drive(EngineStep.Pause), save(atB, mark))),
            ),
            // The A-B loop: A, then B, then off; its end timer runs only while the looped track plays.
            looping(
                playing,
                AbLoop.Starting(b.id, Millis(50_000)),
                PlaybackCommand.MarkLoop(Millis(80_000)),
                playing,
                on(50_000, 80_000),
                armLoop(50_000),
            ),
            looping(paused, AbLoop.Starting(b.id, Millis(50_000)), PlaybackCommand.MarkLoop(Millis(80_000)), paused, on(50_000, 80_000)),
            looping(
                playing,
                AbLoop.Starting(b.id, Millis(50_000)),
                PlaybackCommand.MarkLoop(Millis(50_500)),
                playing,
                AbLoop.Starting(b.id, Millis(50_500)),
            ),
            looping(
                playing,
                AbLoop.Starting(b.id, Millis(50_000)),
                PlaybackCommand.MarkLoop(Millis(51_000)),
                playing,
                on(50_000, 51_000),
                armLoop(21_000),
            ),
            looping(playing, on(50_000, 80_000), PlaybackCommand.MarkLoop(Millis(10_000)), playing, AbLoop.Off, cancelLoop),
            looping(
                playing,
                AbLoop.Starting(a.id, Millis(10_000)),
                PlaybackCommand.MarkLoop(Millis(50_000)),
                playing,
                AbLoop.Starting(b.id, Millis(50_000)),
            ),
            looping(
                playing,
                AbLoop.On(a.id, Millis(10_000), Millis(20_000)),
                PlaybackCommand.MarkLoop(Millis(50_000)),
                playing,
                AbLoop.Starting(b.id, Millis(50_000)),
            ),
            looping(playing, AbLoop.Off, PlaybackCommand.MarkLoop(Millis(999_999)), playing, AbLoop.Starting(b.id, b.duration)),
            looping(playing, AbLoop.Off, PlaybackCommand.MarkLoop(Millis(-5)), playing, AbLoop.Starting(b.id, Millis.ZERO)),
            looping(playing, on(50_000, 80_000), PlaybackCommand.ClearLoop, playing, AbLoop.Off, cancelLoop),
            looping(
                Playing(atB, PlayMark(Millis(70_000), Millis(9), true)),
                on(10_000, 60_000),
                PlaybackCommand.ClearLoop,
                Playing(atB, PlayMark(Millis(70_000), Millis(9), true)),
                AbLoop.Off,
            ),
            Row(
                with(playing).copy(loop = on(10_000, 60_000)),
                PlaybackEvent.LoopEndReached,
                Next(with(playing).copy(loop = on(10_000, 60_000)), listOf(drive(Seek(Millis(10_000))))),
            ),
            Row(
                with(paused).copy(loop = on(10_000, 60_000)),
                PlaybackEvent.LoopEndReached,
                Next(with(paused).copy(loop = on(10_000, 60_000))),
            ),
            Row(
                with(playing).copy(loop = AbLoop.Starting(b.id, Millis(10_000))),
                PlaybackEvent.LoopEndReached,
                Next(with(playing).copy(loop = AbLoop.Starting(b.id, Millis(10_000)))),
            ),
            Row(
                with(playing).copy(loop = AbLoop.On(a.id, Millis(10_000), Millis(60_000))),
                PlaybackEvent.LoopEndReached,
                Next(with(playing)),
            ),
            Row(
                with(paused).copy(loop = on(50_000, 80_000)),
                PlaybackEvent.Started(heard),
                Next(with(Playing(atB, heard)).copy(loop = on(50_000, 80_000)), listOf(armLoop(49_000))),
            ),
            Row(
                with(playing).copy(loop = on(50_000, 80_000)),
                PlaybackEvent.Paused(heard),
                Next(with(Buffering(atB, heard)).copy(loop = on(50_000, 80_000)), listOf(armGrace, cancelLoop)),
            ),
            Row(
                with(playing).copy(loop = on(50_000, 80_000)),
                PlaybackEvent.Seeked(heard),
                Next(with(Playing(atB, heard)).copy(loop = on(50_000, 80_000)), listOf(save(atB, heard), armLoop(49_000))),
            ),
            Row(
                with(playing).copy(loop = on(50_000, 80_000)),
                command(PlaybackCommand.Next),
                Next(with(Loading(atC, start, true, LoadReason.PLAY)), listOf(armLoad, load(c), cancelLoop)),
            ),
            Row(
                with(playing).copy(loop = on(5_000, 20_000)),
                PlaybackEvent.Seeked(heard),
                Next(with(Playing(atB, heard)).copy(loop = on(5_000, 20_000)), listOf(save(atB, heard))),
            ),
            Row(
                with(playing).copy(loop = on(50_000, 80_000)),
                PlaybackEvent.HistoryChanged(emptyList()),
                Next(with(playing).copy(loop = on(50_000, 80_000), history = emptyList())),
            ),
            Row(
                with(Loading(atB, mark, true, RECOVER)).copy(loop = on(50_000, 80_000)),
                PlaybackEvent.Failed,
                Next(with(Loading(atC, start, true, LoadReason.PLAY)), listOf(armLoad, load(c))),
            ),
            // Bookmarks: in the current track, a seek; an unknown one, nothing.
            row(playing, command(PlaybackCommand.GoToBookmark(onB.id)), playing, drive(Seek(onB.position))),
            row(playing, command(PlaybackCommand.GoToBookmark(BookmarkId(99))), playing),
            // A long track opened from its start goes on from where it was left off.
            Row(
                with(Loading(Queue(listOf(long), 0), start, true, LoadReason.PLAY)).copy(resumePoints = points),
                PlaybackEvent.TrackChanged(long.id, heard),
                Next(
                    with(Buffering(Queue(listOf(long), 0), heard)).copy(resumePoints = points),
                    listOf(
                        cancelLoad,
                        armGrace,
                        record(long),
                        save(Queue(listOf(long), 0), heard),
                        keep(long, heard),
                        drive(Seek(Millis(1_500_000))),
                    ),
                ),
            ),
            Row(
                with(Loading(Queue(listOf(long), 0), PlayMark.still(Millis(300_000)), true, LoadReason.PLAY)).copy(resumePoints = points),
                PlaybackEvent.TrackChanged(long.id, heard),
                Next(
                    with(Buffering(Queue(listOf(long), 0), heard)).copy(resumePoints = points),
                    listOf(cancelLoad, armGrace, record(long), save(Queue(listOf(long), 0), heard), keep(long, heard)),
                ),
            ),
            Row(
                with(Loading(Queue(listOf(long), 0), start, false, RESTORE)).copy(resumePoints = points),
                PlaybackEvent.TrackChanged(long.id, heard),
                Next(
                    with(Paused(Queue(listOf(long), 0), heard)).copy(resumePoints = points),
                    listOf(cancelLoad, save(Queue(listOf(long), 0), heard), keep(long, heard)),
                ),
            ),
            Row(
                with(Loading(Queue(listOf(long), 0), start, true, LoadReason.PLAY)),
                PlaybackEvent.TrackChanged(long.id, heard),
                Next(
                    with(Buffering(Queue(listOf(long), 0), heard)),
                    listOf(cancelLoad, armGrace, record(long), save(Queue(listOf(long), 0), heard), keep(long, heard)),
                ),
            ),
            // Leaving a long track, or pausing it, keeps where it was; a failure under it does not.
            Row(
                with(Playing(longFirst, mark)),
                command(PlaybackCommand.Next),
                Next(with(Loading(longFirst.next(), start, true, LoadReason.PLAY)), listOf(armLoad, load(a), keep(long, mark))),
            ),
            Row(
                with(Playing(longFirst, mark)),
                command(PlaybackCommand.Pause),
                Next(with(Paused(longFirst, mark)), listOf(drive(EngineStep.Pause), save(longFirst, mark), keep(long, mark))),
            ),
            Row(
                with(Playing(longFirst, mark)),
                PlaybackEvent.Failed,
                Next(with(Loading(longFirst, mark, true, RECOVER)), listOf(armLoad, load(long, mark))),
            ),
            Row(
                with(Playing(Queue(listOf(a, long), 0), mark)),
                command(PlaybackCommand.Next),
                Next(with(Loading(Queue(listOf(a, long), 1), start, true, LoadReason.PLAY)), listOf(armLoad, load(long))),
            ),
        )

    @Test
    fun `every tool works in every state as the tables say`() {
        for (row in table + details) {
            assertEquals("${row.before} + ${row.event}", row.next, PlaybackReducer.reduce(row.before, row.event))
        }
    }

    @Test
    fun `the table covers every tool against every state`() {
        val covered = table.map { kind(it.before.player) to it.event }.toSet()
        val missing = states.flatMap { state -> events.map { kind(state) to it } }.filterNot { it in covered }

        assertEquals(emptyList<Pair<String, PlaybackEvent>>(), missing)
    }

    @Test
    fun `a shuffle keeps the chosen track first and is the same for the same seed`() {
        val tracks = (1..10).map { Track(TrackId("content://$it"), "Song $it", "Artist", Millis(100_000)) }

        val (order, seed) = shuffled(tracks, keep = 4, seed = 11)
        val orders = (1L..5L).map { shuffled(tracks, keep = 4, seed = it).first }.toSet()

        assertEquals(tracks[4], order.first())
        assertEquals(tracks.toSet(), order.toSet())
        assertEquals(tracks.size, order.size)
        assertEquals(order to seed, shuffled(tracks, keep = 4, seed = 11))
        assertNotEquals(tracks, order)
        assertNotEquals(11L, seed)
        assertNotEquals(1, orders.size)
    }

    @Test
    fun `where a long track was is kept only away from its ends`() {
        val hour = Millis(3_600_000)

        assertEquals(ResumeChoice.SKIP, resumeChoice(Millis.ZERO, hour))
        assertEquals(ResumeChoice.SKIP, resumeChoice(Millis(9_999), hour))
        assertEquals(ResumeChoice.KEEP, resumeChoice(Millis(10_000), hour))
        assertEquals(ResumeChoice.KEEP, resumeChoice(Millis(3_589_999), hour))
        assertEquals(ResumeChoice.FORGET, resumeChoice(Millis(3_590_000), hour))
        assertEquals(ResumeChoice.FORGET, resumeChoice(hour, hour))
    }

    @Test
    fun `similar songs are the queue's artists first, then the rest, none already queued`() {
        val x = Track(TrackId("content://x"), "Ex", "Zed", Millis(100_000))
        val y = Track(TrackId("content://y"), "Why", "Ann", Millis(100_000))
        val z = Track(TrackId("content://z"), "Zee", "Bob", Millis(100_000))
        val w = Track(TrackId("content://w"), "Double", "Cy", Millis(100_000))
        val queue = Queue(listOf(a, b), 1)

        assertEquals(listOf(y, z, x), pickSimilar(queue, listOf(x, a, y, y, z, w), limit = 3))
        assertEquals(listOf(y, z, x, w), pickSimilar(queue, listOf(x, a, y, y, z, w), limit = SIMILAR_BATCH))
        assertEquals(emptyList<Track>(), pickSimilar(queue, listOf(a, b), limit = SIMILAR_BATCH))
    }

    private fun shuffledRow(state: PlayerState): Row {
        val on = quiet.copy(shuffle = true)
        val queue = queueOf(state) ?: return Row(with(state), shuffleOn, Next(with(state, on), listOf(saveOptions(on))))
        val (order, seed) = shuffled(queue.tracks, queue.index, 0)
        val (player, saved) = requeue(state, Queue(order, 0))
        return Row(with(state), shuffleOn, Next(with(player, on).copy(shuffle = ShuffleOrder(seed, abc)), listOf(saveOptions(on), saved)))
    }

    private fun shuffledPlay(
        play: PlaybackCommand,
        first: Int,
    ): Row {
        val on = quiet.copy(shuffle = true)
        val (order, seed) = shuffled(abc, first, 3)
        return Row(
            with(idle, on).copy(shuffle = ShuffleOrder(3, emptyList())),
            command(play),
            Next(
                with(Loading(Queue(order, 0), start, true, LoadReason.PLAY), on).copy(shuffle = ShuffleOrder(seed, abc)),
                listOf(armLoad, load(abc[first])),
            ),
        )
    }

    private fun sleeping(
        before: PlayerState,
        sleep: SleepTimer,
        after: PlayerState,
        left: SleepTimer,
        vararg effects: PlaybackEffect,
    ) = Row(with(before).copy(sleep = sleep), PlaybackEvent.SleepTicked, Next(with(after).copy(sleep = left), effects.toList()))

    private fun looping(
        before: PlayerState,
        loop: AbLoop,
        sent: PlaybackCommand,
        after: PlayerState,
        marked: AbLoop,
        vararg effects: PlaybackEffect,
    ) = Row(with(before).copy(loop = loop), command(sent), Next(with(after).copy(loop = marked), effects.toList()))

    private fun requeued(
        state: PlayerState,
        event: PlaybackEvent,
        queue: Queue,
    ): Row {
        val (player, saved) = requeue(state, queue)
        return Row(with(state), event, Next(with(player), listOf(saved)))
    }

    private fun requeue(
        state: PlayerState,
        queue: Queue,
    ): Pair<PlayerState, PlaybackEffect> =
        when (state) {
            Idle -> error("nothing to requeue")
            is Loading -> state.copy(queue = queue) to save(queue, state.from)
            is Buffering -> state.copy(queue = queue) to save(queue, state.mark)
            is Playing -> state.copy(queue = queue) to save(queue, state.mark)
            is Paused -> state.copy(queue = queue) to save(queue, state.mark)
            is Ended -> state.copy(queue = queue) to save(queue, start)
            is Error -> state.copy(queue = queue) to save(queue, start)
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

    private fun markOf(state: PlayerState): PlayMark =
        when (state) {
            is Buffering -> state.mark
            is Playing -> state.mark
            is Paused -> state.mark
            else -> error("no mark")
        }

    private fun kind(state: PlayerState): String =
        when (state) {
            is Loading -> "Loading(playWhenReady=${state.playWhenReady})"
            else -> state::class.simpleName.orEmpty()
        }

    private fun all() = quiet.copy(repeat = RepeatMode.ALL)

    private fun similar() = quiet.copy(queueEnd = QueueEnd.SIMILAR)

    private fun on(
        start: Long,
        end: Long,
    ) = AbLoop.On(b.id, Millis(start), Millis(end))

    private fun armLoop(after: Long) = StartTimer(LoopEnd, Millis(after), PlaybackEvent.LoopEndReached)

    private fun tick(after: Millis) = StartTimer(SleepTick, after, PlaybackEvent.SleepTicked)

    private fun with(
        player: PlayerState,
        options: ListeningOptions = quiet,
    ) = base.copy(player = player, options = options)

    private fun command(command: PlaybackCommand) = PlaybackEvent.Command(command)

    private fun leaving(state: PlayerState): Array<PlaybackEffect> =
        if (state is Buffering) arrayOf(CancelTimer(BufferingGrace)) else emptyArray()

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

    private fun saveOptions(options: ListeningOptions): PlaybackEffect = Perform(PlaybackWork.SaveListening(options))

    private fun record(track: Track): PlaybackEffect = Perform(PlaybackWork.RecordPlay(track))

    private fun keep(
        track: Track,
        mark: PlayMark,
    ): PlaybackEffect = Perform(PlaybackWork.SaveResumePoint(track, mark))

    private fun forget(track: Track): PlaybackEffect = Perform(PlaybackWork.ForgetResumePoint(track.id))

    private fun pickSimilar(queue: Queue): PlaybackEffect = Perform(PlaybackWork.PickSimilar(queue))

    private fun row(
        state: PlayerState,
        event: PlaybackEvent,
        next: PlayerState,
        vararg effects: PlaybackEffect,
        options: ListeningOptions = quiet,
    ) = Row(with(state, options), event, Next(with(next, options), effects.toList()))

    private fun row(
        state: PlayerState,
        sent: PlaybackCommand,
        next: PlayerState,
        vararg effects: PlaybackEffect,
        options: ListeningOptions = quiet,
    ) = row(state, command(sent), next, *effects, options = options)

    private class Row(
        val before: PlaybackState,
        val event: PlaybackEvent,
        val next: Next<PlaybackState, PlaybackEffect>,
    )
}
