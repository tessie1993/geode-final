package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.AbLoop
import dev.geode.feature.playback.api.BookmarkId
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.PlayMark
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
import dev.geode.feature.playback.core.EngineStep.Seek
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.StartTimer
import kotlin.random.Random

internal typealias Change = Next<PlaybackState, PlaybackEffect>

/**
 * Playback's listening tools (#35), as the rest of [PlaybackReducer]: the queue, shuffle and repeat,
 * crossfade and gapless, the sleep timer, the A-B loop, bookmarks and the resume points of long tracks.
 *
 * [settle] runs after every event and keeps the rules that hold whatever the event was:
 * - with gapless or a crossfade, the audio adapter always has the track that follows ready;
 * - a long track that opens from its start goes on from where it was left off, and leaving it keeps
 *   where it was;
 * - the A-B loop belongs to one track, and its timer runs only while that track plays;
 * - the sleep timer's "after this track" ends when nothing is meant to play;
 * - when the queue runs out while playing, repeat off, the [QueueEnd.SIMILAR] setting asks for more.
 */
internal object Listening {
    private val core = PlaybackReducer

    // The queue.

    /** Plays [tracks] from [startAt], shuffled with the start first when shuffle is on. */
    fun play(
        state: PlaybackState,
        tracks: List<Track>,
        startAt: Int,
    ): Change {
        if (!state.options.shuffle || tracks.isEmpty()) return core.player(state, core.play(state.player, tracks, startAt))
        val (order, seed) = shuffled(tracks, startAt.coerceIn(tracks.indices), state.shuffle.seed)
        return core.player(state.copy(shuffle = ShuffleOrder(seed, tracks)), core.play(state.player, order, startAt = 0))
    }

    /** Puts [tracks] after the current track ([next]) or at the end of the queue. */
    fun insert(
        state: PlaybackState,
        tracks: List<Track>,
        next: Boolean,
    ): Change {
        if (tracks.isEmpty()) return Next(state)
        val queue = core.queueOf(state.player) ?: return play(state, tracks, startAt = 0)
        val at = if (next) queue.index + 1 else queue.tracks.size
        return requeue(state, queue.copy(tracks = queue.tracks.take(at) + tracks + queue.tracks.drop(at)))
    }

    fun playQueueItem(
        state: PlaybackState,
        index: Int,
    ): Change {
        val queue = core.queueOf(state.player) ?: return Next(state)
        if (index !in queue.tracks.indices) return Next(state)
        val chosen = queue.copy(index = index)
        return core.player(state, core.load(state.player, chosen, core.START, playWhenReady = true, LoadReason.PLAY))
    }

    fun remove(
        state: PlaybackState,
        index: Int,
    ): Change {
        val queue = core.queueOf(state.player) ?: return Next(state)
        if (index !in queue.tracks.indices || index == queue.index) return Next(state)
        val tracks = queue.tracks.filterIndexed { at, _ -> at != index }
        return requeue(state, Queue(tracks, if (index < queue.index) queue.index - 1 else queue.index))
    }

    fun move(
        state: PlaybackState,
        from: Int,
        to: Int,
    ): Change {
        val queue = core.queueOf(state.player) ?: return Next(state)
        if (from !in queue.tracks.indices || to !in queue.tracks.indices || from == to) return Next(state)
        val tracks = queue.tracks.toMutableList().apply { add(to, removeAt(from)) }
        val index =
            when {
                from == queue.index -> to
                from < queue.index && to >= queue.index -> queue.index - 1
                from > queue.index && to <= queue.index -> queue.index + 1
                else -> queue.index
            }
        return requeue(state, Queue(tracks, index))
    }

    /** Next, which with repeat all goes round from the last track to the first. */
    fun next(state: PlaybackState): Change {
        val queue = core.queueOf(state.player)
        if (queue == null || queue.hasNext || state.options.repeat != RepeatMode.ALL) {
            return core.player(state, core.next(state.player))
        }
        val round = core.load(state.player, queue.first(), core.START, core.wantsToPlay(state.player), LoadReason.PLAY)
        return core.player(state, round)
    }

    fun shuffle(
        state: PlaybackState,
        on: Boolean,
    ): Change {
        if (on == state.options.shuffle) return Next(state)
        val options = state.options.copy(shuffle = on)
        val saved = Perform(PlaybackWork.SaveListening(options))
        val queue = core.queueOf(state.player) ?: return Next(state.copy(options = options), listOf(saved))
        if (!on) return requeue(state.copy(options = options), unshuffled(queue, state.shuffle.unshuffled), saved)
        val (order, seed) = shuffled(queue.tracks, queue.index, state.shuffle.seed)
        return requeue(state.copy(options = options, shuffle = ShuffleOrder(seed, queue.tracks)), Queue(order, 0), saved)
    }

    /** Sets [options]; [save] is false when they were just read back from where they are saved. */
    fun options(
        state: PlaybackState,
        options: ListeningOptions,
        save: Boolean = true,
    ): Change {
        if (save && options == state.options) return Next(state)
        val transitions = options.crossfade != state.options.crossfade || options.gapless != state.options.gapless
        val effects =
            listOfNotNull(
                if (save) Perform(PlaybackWork.SaveListening(options)) else null,
                if (transitions || !save) core.drive(EngineStep.Transitions(options.crossfade, options.gapless)) else null,
            )
        return Next(state.copy(options = options), effects)
    }

    /** The track ended: the same one again, round to the first, on to the next, or the end of the queue. */
    fun ending(state: PlaybackState): Change {
        val player = state.player
        val queue = settled(player)?.first ?: return core.player(state, core.ended(player))
        val playOn = player !is Paused && state.sleep != SleepTimer.AfterTrack
        val step =
            when {
                state.options.repeat == RepeatMode.ONE -> core.load(player, queue, core.START, playOn, LoadReason.PLAY)
                !queue.hasNext && state.options.repeat == RepeatMode.ALL ->
                    core.load(player, queue.first(), core.START, playOn, LoadReason.PLAY)
                else -> core.advance(player, queue, playOn)
            }
        val heard = forget(state, queue.current)
        return Next(heard.state.copy(player = step.state), step.effects + heard.effects)
    }

    /** A track change the player made by itself onto the track that follows is a gapless join or a crossfade. */
    fun trackChanged(
        state: PlaybackState,
        event: PlaybackEvent.TrackChanged,
    ): Change {
        val player = state.player
        val queue = settled(player)?.first
        val following = queue?.let { following(it, state.options) }?.takeIf { state.options.joinsTracks }
        if (queue == null || following == null || event.track == queue.current.id || event.track != following.current.id) {
            return core.player(state, core.trackChanged(player, event))
        }
        val heard = forget(state, queue.current)
        val afterTrack = state.sleep == SleepTimer.AfterTrack
        val effects =
            listOfNotNull(
                Perform(PlaybackWork.RecordPlay(following.current)),
                core.save(following, event.mark),
                if (afterTrack) core.drive(EngineStep.Pause) else null,
            )
        val sleep = if (afterTrack) SleepTimer.Off else state.sleep
        return Next(heard.state.copy(player = moved(player, following, event.mark), sleep = sleep), effects + heard.effects)
    }

    /** Songs like the queue's arrived: the queue goes on with them, if it is still at its end. */
    fun similarPicked(
        state: PlaybackState,
        tracks: List<Track>,
    ): Change {
        val player = state.player
        if (player !is Ended || tracks.isEmpty()) return Next(state)
        val queue = Queue(player.queue.tracks + tracks, player.queue.index + 1)
        return core.player(state, core.load(player, queue, core.START, playWhenReady = true, LoadReason.PLAY))
    }

    // The sleep timer.

    fun startSleep(
        state: PlaybackState,
        after: Millis,
        finishTrack: Boolean,
    ): Change {
        if (after <= Millis.ZERO) return Next(state)
        return Next(state.copy(sleep = SleepTimer.Counting(after, finishTrack)), listOf(tick(after)))
    }

    fun sleepAfterTrack(state: PlaybackState): Change = Next(state.copy(sleep = SleepTimer.AfterTrack), stopCounting(state))

    fun cancelSleep(state: PlaybackState): Change = Next(state.copy(sleep = SleepTimer.Off), stopCounting(state))

    fun sleepTicked(state: PlaybackState): Change {
        val sleep = state.sleep as? SleepTimer.Counting ?: return Next(state)
        val left = sleep.remaining - minOf(sleep.remaining, SLEEP_TICK)
        if (left > Millis.ZERO) return Next(state.copy(sleep = sleep.copy(remaining = left)), listOf(tick(left)))
        if (sleep.finishTrack) return Next(state.copy(sleep = SleepTimer.AfterTrack))
        return core.player(state.copy(sleep = SleepTimer.Off), core.pause(state.player))
    }

    private fun tick(remaining: Millis): PlaybackEffect =
        StartTimer(SleepTick, minOf(remaining, SLEEP_TICK), PlaybackEvent.SleepTicked)

    private fun stopCounting(state: PlaybackState): List<PlaybackEffect> =
        if (state.sleep is SleepTimer.Counting) listOf(CancelTimer(SleepTick)) else emptyList()

    // The A-B loop.

    fun markLoop(
        state: PlaybackState,
        at: Millis,
    ): Change {
        val track = settled(state.player)?.first?.current ?: return Next(state)
        val position = at.coerceIn(Millis.ZERO, track.duration)
        val loop = state.loop
        val marked =
            when {
                loop is AbLoop.Starting && loop.track == track.id && position >= loop.start + MIN_LOOP ->
                    AbLoop.On(track.id, loop.start, position)
                loop is AbLoop.On && loop.track == track.id -> AbLoop.Off
                else -> AbLoop.Starting(track.id, position)
            }
        return Next(state.copy(loop = marked))
    }

    fun loopEndReached(state: PlaybackState): Change {
        val player = state.player
        val loop = state.loop
        if (player !is Playing || loop !is AbLoop.On || loop.track != player.queue.current.id) return Next(state)
        return Next(state, listOf(core.drive(Seek(loop.start))))
    }

    // Bookmarks.

    fun addBookmark(state: PlaybackState): Change {
        val (queue, mark) = settled(state.player) ?: return Next(state)
        return Next(state, listOf(Perform(PlaybackWork.AddBookmark(queue.current, mark))))
    }

    /** Seeks to the bookmark in the current track, or plays its track next, from the bookmark. */
    fun goToBookmark(
        state: PlaybackState,
        id: BookmarkId,
    ): Change {
        val bookmark = state.bookmarks.firstOrNull { it.id == id } ?: return Next(state)
        if (settled(state.player)?.first?.current?.id == bookmark.track.id) {
            return Next(state, listOf(core.drive(Seek(bookmark.position))))
        }
        val queue = core.queueOf(state.player)
        val target =
            if (queue == null) {
                Queue(listOf(bookmark.track), 0)
            } else {
                val at = queue.index + 1
                Queue(queue.tracks.take(at) + bookmark.track + queue.tracks.drop(at), at)
            }
        val from = PlayMark.still(bookmark.position)
        return core.player(state, core.load(state.player, target, from, playWhenReady = true, LoadReason.PLAY))
    }

    // What holds after every event.

    fun settle(
        before: PlaybackState,
        event: PlaybackEvent,
        next: Change,
    ): Change {
        val after = next.state
        val state = after.copy(loop = keptLoop(after), sleep = keptSleep(after))
        val effects =
            next.effects + resumeSaves(before, event, next) + resumeOnOpen(before.player, state) + prepare(before, state) +
                loopTimer(before, state) + similar(before, state)
        return Next(state, effects)
    }

    private fun keptLoop(state: PlaybackState): AbLoop {
        val track =
            when (val loop = state.loop) {
                AbLoop.Off -> return loop
                is AbLoop.Starting -> loop.track
                is AbLoop.On -> loop.track
            }
        return if (core.queueOf(state.player)?.current?.id == track) state.loop else AbLoop.Off
    }

    private fun keptSleep(state: PlaybackState): SleepTimer =
        if (state.sleep == SleepTimer.AfterTrack && !meantToPlay(state.player)) SleepTimer.Off else state.sleep

    /** Where a long track was is kept whenever the session is saved, and when another track replaces it. */
    private fun resumeSaves(
        before: PlaybackState,
        event: PlaybackEvent,
        next: Change,
    ): List<PlaybackEffect> {
        val saves =
            next.effects
                .mapNotNull { (it as? Perform<*>)?.work as? PlaybackWork.SaveSession }
                .filter { it.queue.current.isLong }
                .map { Perform(PlaybackWork.SaveResumePoint(it.queue.current, it.mark)) }
        val (queue, mark) = settled(before.player) ?: return saves
        val replaced = (next.state.player as? Loading)?.reason == LoadReason.PLAY && event != PlaybackEvent.Ended
        if (!replaced || !queue.current.isLong) return saves
        return saves + Perform(PlaybackWork.SaveResumePoint(queue.current, mark))
    }

    /** A track played from its start goes on from where it was left off, if it was. */
    private fun resumeOnOpen(
        before: PlayerState,
        state: PlaybackState,
    ): List<PlaybackEffect> {
        if (before !is Loading || before.reason != LoadReason.PLAY || before.from.position != Millis.ZERO) return emptyList()
        val queue = settled(state.player)?.first ?: return emptyList()
        val point = state.resumePoints[queue.current.id] ?: return emptyList()
        return listOf(core.drive(Seek(point)))
    }

    /** Keeps the track that follows ready in the adapter, for a gapless join or a crossfade. */
    private fun prepare(
        before: PlaybackState,
        state: PlaybackState,
    ): List<PlaybackEffect> {
        val queue = settled(state.player)?.first ?: return emptyList()
        val target = upcoming(queue, state.options)
        val was = settled(before.player)?.first
        val changed =
            if (was == null) {
                target != null
            } else {
                target?.id != upcoming(was, before.options)?.id || (target != null && queue.index != was.index)
            }
        return if (changed) listOf(core.drive(EngineStep.PrepareNext(target?.id))) else emptyList()
    }

    private fun loopTimer(
        before: PlaybackState,
        state: PlaybackState,
    ): List<PlaybackEffect> {
        val armed = loopDelay(state.player, state.loop)
        val was = loopDelay(before.player, before.loop)
        return when {
            armed != null && (armed != was || state.player != before.player) ->
                listOf(StartTimer(LoopEnd, armed, PlaybackEvent.LoopEndReached))
            armed == null && was != null -> listOf(CancelTimer(LoopEnd))
            else -> emptyList()
        }
    }

    /** The queue ran out while playing, repeat off: keep going with similar songs, if that is the setting. */
    private fun similar(
        before: PlaybackState,
        state: PlaybackState,
    ): List<PlaybackEffect> {
        val ended = state.player as? Ended ?: return emptyList()
        val keepGoing =
            meantToPlay(before.player) && before.sleep != SleepTimer.AfterTrack &&
                state.options.repeat == RepeatMode.OFF && state.options.queueEnd == QueueEnd.SIMILAR
        return if (keepGoing) listOf(Perform(PlaybackWork.PickSimilar(ended.queue))) else emptyList()
    }

    // Helpers.

    /** How long until playing reaches the loop's end, while [player] plays the looped track before it. */
    private fun loopDelay(
        player: PlayerState,
        loop: AbLoop,
    ): Millis? {
        if (player !is Playing || loop !is AbLoop.On || loop.track != player.queue.current.id) return null
        return (loop.end - player.mark.position).takeIf { it > Millis.ZERO }
    }

    /** The queue the player moves on to when the current track ends by itself, or null when it stops or repeats. */
    private fun following(
        queue: Queue,
        options: ListeningOptions,
    ): Queue? =
        when {
            options.repeat == RepeatMode.ONE -> null
            queue.hasNext -> queue.next()
            options.repeat == RepeatMode.ALL && queue.tracks.size > 1 -> queue.first()
            else -> null
        }

    private fun upcoming(
        queue: Queue,
        options: ListeningOptions,
    ): Track? = if (options.joinsTracks) following(queue, options)?.current else null

    /** [track] was heard to its end: a long one starts from the top next time. */
    private fun forget(
        state: PlaybackState,
        track: Track,
    ): Change =
        if (track.isLong) {
            val forgotten = state.copy(resumePoints = state.resumePoints - track.id)
            Next(forgotten, listOf(Perform(PlaybackWork.ForgetResumePoint(track.id))))
        } else {
            Next(state)
        }

    /** Replaces the queue wherever the player is, and saves it. */
    private fun requeue(
        state: PlaybackState,
        queue: Queue,
        vararg effects: PlaybackEffect,
    ): Change {
        val player = withQueue(state.player, queue)
        return Next(state.copy(player = player), effects.toList() + listOfNotNull(saveOf(player)))
    }

    private fun withQueue(
        player: PlayerState,
        queue: Queue,
    ): PlayerState =
        when (player) {
            Idle -> Idle
            is Loading -> player.copy(queue = queue)
            is Buffering -> player.copy(queue = queue)
            is Playing -> player.copy(queue = queue)
            is Paused -> player.copy(queue = queue)
            is Ended -> player.copy(queue = queue)
            is Error -> player.copy(queue = queue)
        }

    private fun moved(
        player: PlayerState,
        queue: Queue,
        mark: PlayMark,
    ): PlayerState =
        when (player) {
            is Buffering -> player.copy(queue = queue, mark = mark)
            is Playing -> player.copy(queue = queue, mark = mark)
            is Paused -> player.copy(queue = queue, mark = mark)
            Idle, is Loading, is Ended, is Error -> player
        }

    private fun saveOf(player: PlayerState): PlaybackEffect? =
        when (player) {
            Idle -> null
            is Loading -> core.save(player.queue, player.from)
            is Buffering -> core.save(player.queue, player.mark)
            is Playing -> core.save(player.queue, player.mark)
            is Paused -> core.save(player.queue, player.mark)
            is Ended -> core.save(player.queue, core.START)
            is Error -> core.save(player.queue, core.START)
        }

    /** The queue and the mark of a player that has its track open. */
    private fun settled(player: PlayerState): Pair<Queue, PlayMark>? =
        when (player) {
            is Buffering -> player.queue to player.mark
            is Playing -> player.queue to player.mark
            is Paused -> player.queue to player.mark
            Idle, is Loading, is Ended, is Error -> null
        }

    private fun meantToPlay(player: PlayerState): Boolean =
        when (player) {
            is Buffering, is Playing -> true
            is Loading -> player.playWhenReady
            Idle, is Paused, is Ended, is Error -> false
        }

    private val Track.isLong: Boolean get() = duration >= LONG_TRACK
}

/**
 * [tracks] with the one at [keep] first and the rest shuffled by [seed], and the seed of the next
 * shuffle. The same seed always gives the same order.
 */
internal fun shuffled(
    tracks: List<Track>,
    keep: Int,
    seed: Long,
): Pair<List<Track>, Long> {
    val random = Random(seed)
    val rest = tracks.filterIndexed { at, _ -> at != keep }.shuffled(random)
    return listOf(tracks[keep]) + rest to random.nextLong()
}

/** [queue] back in its [unshuffled] order, tracks added since at the end, on the same current track. */
internal fun unshuffled(
    queue: Queue,
    unshuffled: List<Track>,
): Queue {
    val left = queue.tracks.toMutableList()
    val order = unshuffled.filter { left.remove(it) } + left
    return Queue(order, order.indexOf(queue.current))
}

/** What to do with where a long track was: keep it, leave what was kept, or forget it. */
enum class ResumeChoice {
    KEEP,
    SKIP,
    FORGET,
}

/** Near the end of a long track there is nothing left to resume; near its start, nothing worth keeping. */
fun resumeChoice(
    position: Millis,
    duration: Millis,
): ResumeChoice =
    when {
        position >= duration - RESUME_EDGE -> ResumeChoice.FORGET
        position < RESUME_EDGE -> ResumeChoice.SKIP
        else -> ResumeChoice.KEEP
    }

/**
 * Up to [limit] songs from [known] to keep going with after [queue], picked offline: the artists of the
 * queue first, then the rest in the order they are known (the most played first), none already queued.
 */
fun pickSimilar(
    queue: Queue,
    known: List<Track>,
    limit: Int,
): List<Track> {
    val queued = queue.tracks.map { it.id }.toSet()
    val artists = queue.tracks.map { it.artist }.toSet()
    val (same, other) = known.distinctBy { it.id }.filterNot { it.id in queued }.partition { it.artist in artists }
    return (same + other).take(limit)
}
