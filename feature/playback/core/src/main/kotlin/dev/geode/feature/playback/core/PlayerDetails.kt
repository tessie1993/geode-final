package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.Lyrics
import dev.geode.feature.playback.api.LyricsSource
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.SyncedLine
import dev.geode.feature.playback.api.TrackLookup
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer

/**
 * Playback's reducer as its store runs it: [PlaybackReducer]'s state machine, then the lookup of what
 * the Player shows beside the music (#147). Whenever the current track changes, its lyrics are looked
 * up again; with nothing playing there is nothing to look up.
 */
object PlaybackStoreReducer : Reducer<PlaybackState, PlaybackEvent, PlaybackEffect> {
    override fun reduce(
        state: PlaybackState,
        event: PlaybackEvent,
    ): Next<PlaybackState, PlaybackEffect> = PlayerDetails.lookUp(PlaybackReducer.reduce(state, event))
}

/**
 * What the Player shows beside the music (#147), as the rest of [PlaybackReducer]: the current
 * track's lyrics, from an `.lrc` file or the track's tags. Tracks are never analysed (owner,
 * 2026-10-22), so nothing here is built from track analysis.
 */
internal object PlayerDetails {
    private val core = PlaybackReducer

    /** Looks the current track's lyrics up when what is kept is about another track. */
    fun lookUp(next: Change): Change {
        val current = core.queueOf(next.state.player)?.current?.id
        return when {
            next.state.lyrics.track == current -> next
            current == null -> Next(next.state.copy(lyrics = TrackLookup.Idle), next.effects)
            else -> Next(next.state.copy(lyrics = TrackLookup.Looking(current)), next.effects + Perform(PlaybackWork.FindLyrics(current)))
        }
    }

    /** What came back for [PlaybackEvent.LyricsFound.track] is kept only while the lyrics are waiting for it. */
    fun lyricsFound(
        state: PlaybackState,
        event: PlaybackEvent.LyricsFound,
    ): Change =
        when {
            state.lyrics != TrackLookup.Looking(event.track) -> Next(state)
            event.lyrics == null -> Next(state.copy(lyrics = TrackLookup.Missing(event.track)))
            else -> Next(state.copy(lyrics = TrackLookup.Found(event.track, event.lyrics)))
        }
}

// Reading lyrics, as the old player did.

/**
 * The lyrics in [text], from [source]: its timed `.lrc` lines (`[mm:ss.xx]words`, a line with several
 * times sung at each) in time order, or, when it has none, its other lines as plain lyrics. Tag lines
 * such as `[ar:Artist]`, blank lines and times with no words are left out. Null when nothing is left.
 */
fun parseLyrics(
    text: String,
    source: LyricsSource,
): Lyrics? {
    val timed = ArrayList<SyncedLine>()
    val plain = ArrayList<String>()
    for (line in text.lines()) {
        val times = LRC_TIME.findAll(line).map(::timeOf).toList()
        val words = line.replace(LRC_TIME, "").trim()
        when {
            words.isBlank() -> Unit
            times.isNotEmpty() -> times.mapTo(timed) { SyncedLine(it, words) }
            !LRC_TAG.matches(line.trim()) -> plain += words
        }
    }
    return when {
        timed.isNotEmpty() -> Lyrics.Synced(timed.sortedBy { it.at }, source)
        plain.isNotEmpty() -> Lyrics.Plain(plain, source)
        else -> null
    }
}

private fun timeOf(stamp: MatchResult): Millis {
    val (minutes, seconds, fraction) = stamp.destructured
    val fractionMillis =
        when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * TENTH
            2 -> fraction.toLong() * HUNDREDTH
            else -> fraction.toLong()
        }
    return Millis(minutes.toLong() * MILLIS_PER_MINUTE + seconds.toLong() * MILLIS_PER_SECOND + fractionMillis)
}

/** One `.lrc` time: `[m:ss]`, `[mm:ss.x]`, `[mm:ss.xx]` or `[mm:ss.xxx]`, with `.` or `:` before the fraction. */
private val LRC_TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

/** An `.lrc` tag line, such as `[ar:Artist]`. */
private val LRC_TAG = Regex("""^\[[a-zA-Z]+:.*]$""")

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val TENTH = 100L
private const val HUNDREDTH = 10L
