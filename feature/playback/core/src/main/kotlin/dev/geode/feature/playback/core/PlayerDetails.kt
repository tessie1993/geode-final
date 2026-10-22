package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.BANNER_VISIBLE
import dev.geode.feature.playback.api.Banner
import dev.geode.feature.playback.api.Lyrics
import dev.geode.feature.playback.api.LyricsSource
import dev.geode.feature.playback.api.Notice
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.SyncedLine
import dev.geode.feature.playback.api.TrackLookup
import dev.geode.feature.playback.api.Waveform
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.StartTimer

/**
 * Playback's reducer as its store runs it: [PlaybackReducer]'s state machine, then the lookups of what
 * the Player shows beside the music (#147). Whenever the current track changes, its lyrics and its
 * seek waveform are looked up again; with nothing playing there is nothing to look up.
 */
object PlaybackStoreReducer : Reducer<PlaybackState, PlaybackEvent, PlaybackEffect> {
    override fun reduce(
        state: PlaybackState,
        event: PlaybackEvent,
    ): Next<PlaybackState, PlaybackEffect> = PlayerDetails.lookUp(PlaybackReducer.reduce(state, event))
}

/**
 * What the Player shows beside the music (#147), as the rest of [PlaybackReducer]: the current
 * track's lyrics and seek waveform, and the one app-wide banner for the failures every feature
 * reports to the error channel (ADR-0004).
 */
internal object PlayerDetails {
    private val core = PlaybackReducer

    // The current track's lyrics and waveform.

    /** Looks up each of the current track's details that is about another track. */
    fun lookUp(next: Change): Change {
        val current = core.queueOf(next.state.player)?.current?.id
        val (lyrics, findLyrics) = follow(next.state.lyrics, current, PlaybackWork::FindLyrics)
        val (waveform, findWaveform) = follow(next.state.waveform, current, PlaybackWork::FindWaveform)
        return Next(next.state.copy(lyrics = lyrics, waveform = waveform), next.effects + listOfNotNull(findLyrics, findWaveform))
    }

    private fun <T> follow(
        lookup: TrackLookup<T>,
        current: TrackId?,
        find: (TrackId) -> PlaybackWork,
    ): Pair<TrackLookup<T>, PlaybackEffect?> =
        when {
            lookup.track == current -> lookup to null
            current == null -> TrackLookup.Idle to null
            else -> TrackLookup.Looking(current) to Perform(find(current))
        }

    fun lyricsFound(
        state: PlaybackState,
        event: PlaybackEvent.LyricsFound,
    ): Change = Next(state.copy(lyrics = found(state.lyrics, event.track, event.lyrics)))

    fun waveformFound(
        state: PlaybackState,
        event: PlaybackEvent.WaveformFound,
    ): Change = Next(state.copy(waveform = found(state.waveform, event.track, event.waveform)))

    /** What came back for [track] is kept only while [lookup] is waiting for it. */
    private fun <T> found(
        lookup: TrackLookup<T>,
        track: TrackId,
        value: T?,
    ): TrackLookup<T> =
        when {
            lookup != TrackLookup.Looking(track) -> lookup
            value == null -> TrackLookup.Missing(track)
            else -> TrackLookup.Found(track, value)
        }

    // The app-wide error banner.

    /** A new failure replaces the one shown: one at a time, never stacking. */
    fun reported(
        state: PlaybackState,
        notice: Notice,
    ): Change =
        when (state.banner) {
            Banner.Hidden, is Banner.Waiting -> Next(state.copy(banner = Banner.Waiting(notice)))
            is Banner.Showing -> Next(state.copy(banner = Banner.Showing(notice)), listOf(ARM_BANNER))
        }

    /** The banner is on screen: what waited to be seen starts its countdown. */
    fun shown(state: PlaybackState): Change =
        when (val banner = state.banner) {
            Banner.Hidden, is Banner.Showing -> Next(state)
            is Banner.Waiting -> Next(state.copy(banner = Banner.Showing(banner.notice)), listOf(ARM_BANNER))
        }

    fun dismissed(state: PlaybackState): Change =
        when (state.banner) {
            Banner.Hidden -> Next(state)
            is Banner.Waiting -> Next(state.copy(banner = Banner.Hidden))
            is Banner.Showing -> Next(state.copy(banner = Banner.Hidden), listOf(CancelTimer(BannerTimeout)))
        }

    fun timedOut(state: PlaybackState): Change =
        when (state.banner) {
            Banner.Hidden, is Banner.Waiting -> Next(state)
            is Banner.Showing -> Next(state.copy(banner = Banner.Hidden))
        }
}

/** Shows the banner for [BANNER_VISIBLE], counting again from now when it was already counting. */
private val ARM_BANNER: PlaybackEffect = StartTimer(BannerTimeout, BANNER_VISIBLE, PlaybackEvent.BannerTimedOut)

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

// The seek waveform, as the old player drew it.

/** How many bars the seek waveform has, as in the old player. */
const val WAVEFORM_BARS = 240

/**
 * The seek waveform of a track whose loudness, from its start to its end, is [levels]: [bars] bars,
 * each the loudest level of its slice of the track, scaled so that the loudest bar is 1. Null when
 * there are fewer levels than bars, or only silence.
 */
fun waveformOf(
    levels: FloatArray,
    bars: Int = WAVEFORM_BARS,
): Waveform? {
    if (levels.size < bars) return null
    val peaks =
        List(bars) { bar ->
            var loudest = 0f
            for (at in bar * levels.size / bars until (bar + 1) * levels.size / bars) loudest = maxOf(loudest, levels[at])
            loudest
        }
    val loudest = peaks.max()
    return if (loudest > 0f) Waveform(peaks.map { it / loudest }) else null
}
