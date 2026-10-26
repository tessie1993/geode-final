package dev.geode.analysis

/**
 * A whole track's map, read from its full analysis before a Render draws anything (#320, ADR-0001): the
 * beat grid at the track's tempo, its downbeats, where its sections and drops begin, and the builds
 * leading into each drop. The live Visual never has one: it hears the music as it comes and has no
 * lookahead. All times are in milliseconds from the track's start.
 *
 * [choreographed] is the analysis a Render then draws from: downbeats on the grid, section changes and
 * drops on the bar where they begin rather than where the live detector noticed them, a build that rises
 * through the bars before each drop, and every hit drawn [RISE_LEAD_MS] early so a luminous rise, which
 * the No-flash rule never lets take less than that, peaks on the hit instead of after it.
 */
class SongMap(
    /** One beat at the track's tempo, or 0 when it has none to speak of. */
    val beatMs: Double,
    val beats: List<Long>,
    val downbeats: List<Long>,
    val sections: List<Long>,
    val drops: List<Long>,
    /** Where the build before each drop starts, paired with that drop. */
    val builds: List<Pair<Long, Long>>,
) {
    /** [timeline] as a Render draws it with this map; the continuous values are left as they were heard. */
    fun choreographed(timeline: FeatureTimeline): FeatureTimeline {
        val frames = timeline.frames
        if (frames.isEmpty()) return timeline
        val times = LongArray(frames.size) { frames[it].timeMs }
        val out = Array(frames.size) { i -> built(frames[i]).withoutEvents() }
        // The hits the analysis heard, each drawn the rise time early; the map's own events replace its
        // downbeats, section changes and drops.
        for (i in frames.indices) {
            val heard = frames[i].features
            val at = nearest(times, frames[i].timeMs - RISE_LEAD_MS)
            out[at] = out[at].withHitsOf(heard)
        }
        mark(times, out, downbeats) { it.copy(downbeat = true) }
        mark(times, out, sections) { it.copy(sectionBoundary = true) }
        mark(times, out, drops) { it.copy(drop = true) }
        return FeatureTimeline(frames.indices.map { TimelineFrame(times[it], out[it]) }, timeline.hopMs, timeline.key, timeline.hopRateHz)
    }

    /** [frame]'s features with the build before the next drop rising through them, never lower than heard. */
    private fun built(frame: TimelineFrame): AudioFeatures {
        val t = frame.timeMs
        val build = builds.firstOrNull { (start, drop) -> t in start until drop }
        val ramp = build?.let { (start, drop) -> (t - start).toFloat() / (drop - start) }
        return if (ramp == null || ramp <= frame.features.buildup) frame.features else frame.features.copy(buildup = ramp)
    }

    private fun mark(
        times: LongArray,
        out: Array<AudioFeatures>,
        at: List<Long>,
        event: (AudioFeatures) -> AudioFeatures,
    ) {
        for (t in at) {
            val i = nearest(times, t - RISE_LEAD_MS)
            out[i] = event(out[i])
        }
    }

    companion object {
        /** How early a Render draws each hit: the shortest rise the No-flash rule allows a luminous Reaction. */
        const val RISE_LEAD_MS: Long = 150

        const val BEATS_PER_BAR: Int = 4

        /** How many bars before a drop its build starts. */
        const val BUILD_BARS: Int = 4

        private const val MIN_BPM = 50f
        private const val MAX_BPM = 220f
        private const val MILLIS_PER_MINUTE = 60_000.0

        /** The map of [timeline]: deterministic, the same analysis gives the same map. */
        fun of(timeline: FeatureTimeline): SongMap {
            val frames = timeline.frames
            val bpm = tempoOf(frames)
            if (frames.isEmpty() || bpm == null) return heardOnly(frames)
            val beatMs = MILLIS_PER_MINUTE / bpm
            val times = LongArray(frames.size) { frames[it].timeMs }
            val beats = gridOf(frames, times, beatMs)
            val downbeats = downbeatsOf(frames, times, beats, beatMs)
            val barMs = (beatMs * BEATS_PER_BAR).toLong()
            val sections = snapped(frames.filter { it.features.sectionBoundary }.map { it.timeMs }, downbeats, beatMs)
            val drops = snapped(frames.filter { it.features.drop }.map { it.timeMs }, downbeats, beatMs)
            val builds = drops.map { drop -> maxOf(0L, drop - BUILD_BARS * barMs) to drop }.filter { (start, drop) -> start < drop }
            return SongMap(beatMs, beats, downbeats, sections, drops, builds)
        }

        /** The track's tempo: the middle of what the analysis settled on over its second half, if any. */
        internal fun tempoOf(frames: List<TimelineFrame>): Float? {
            val settled =
                frames
                    .drop(frames.size / 2)
                    .map { it.features.bpm }
                    .filter { it in MIN_BPM..MAX_BPM }
                    .sorted()
            return settled.getOrNull(settled.size / 2)
        }

        /** With no tempo there is no grid: the map keeps what the analysis heard. */
        private fun heardOnly(frames: List<TimelineFrame>): SongMap =
            SongMap(
                beatMs = 0.0,
                beats = frames.filter { it.features.beat }.map { it.timeMs },
                downbeats = frames.filter { it.features.downbeat }.map { it.timeMs },
                sections = frames.filter { it.features.sectionBoundary }.map { it.timeMs },
                drops = frames.filter { it.features.drop }.map { it.timeMs },
                builds = emptyList(),
            )

        /** The beat grid at [beatMs] whose phase lines up with the most onset strength. */
        private fun gridOf(
            frames: List<TimelineFrame>,
            times: LongArray,
            beatMs: Double,
        ): List<Long> {
            val end = times.last()
            val step = maxOf(1.0, if (frames.size > 1) end.toDouble() / (frames.size - 1) else beatMs)
            var bestPhase = 0.0
            var bestScore = -1.0
            var phase = 0.0
            while (phase < beatMs) {
                val score = strengthAlong(frames, times, phase, beatMs)
                if (score > bestScore) {
                    bestScore = score
                    bestPhase = phase
                }
                phase += step
            }
            return generateSequence(bestPhase) { it + beatMs }.takeWhile { it <= end }.map { Math.round(it) }.toList()
        }

        /** Which of each bar's beats is its first: the one where the low end hits hardest. */
        private fun downbeatsOf(
            frames: List<TimelineFrame>,
            times: LongArray,
            beats: List<Long>,
            beatMs: Double,
        ): List<Long> {
            val first = beats.firstOrNull() ?: return emptyList()
            val best =
                (0 until BEATS_PER_BAR).maxByOrNull { offset ->
                    var score = 0.0
                    var i = offset
                    while (i < beats.size) {
                        val f = frames[nearest(times, beats[i])].features
                        score += f.bass + f.kick + if (f.downbeat) 1.0 else 0.0
                        i += BEATS_PER_BAR
                    }
                    score
                } ?: 0
            return generateSequence(first + best * beatMs) { it + beatMs * BEATS_PER_BAR }
                .takeWhile { it <= beats.last() }
                .map { Math.round(it) }
                .toList()
        }

        private fun strengthAlong(
            frames: List<TimelineFrame>,
            times: LongArray,
            phase: Double,
            beatMs: Double,
        ): Double {
            var score = 0.0
            var t = phase
            val end = times.last()
            while (t <= end) {
                score += frames[nearest(times, Math.round(t))].features.onset
                t += beatMs
            }
            return score
        }

        /**
         * Each of [heard] moved back to the downbeat it belongs to: the latest one no more than half a
         * beat after it, since a detector that only hears the music notices a change after it begins.
         * Two that land on the same bar are one.
         */
        internal fun snapped(
            heard: List<Long>,
            downbeats: List<Long>,
            beatMs: Double,
        ): List<Long> {
            if (downbeats.isEmpty()) return heard.distinct()
            val slack = beatMs / 2
            return heard
                .map { t -> downbeats.lastOrNull { it <= t + slack } ?: downbeats.first() }
                .distinct()
                .sorted()
        }

        /** The index of the frame timed nearest [timeMs] in the ascending [times]. */
        internal fun nearest(
            times: LongArray,
            timeMs: Long,
        ): Int {
            var lo = 0
            var hi = times.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (times[mid] < timeMs) lo = mid + 1 else hi = mid
            }
            return if (lo > 0 && timeMs - times[lo - 1] <= times[lo] - timeMs) lo - 1 else lo
        }
    }
}

/** These features with every hit of [heard] folded in: only the one-hop events, never its continuous values. */
private fun AudioFeatures.withHitsOf(heard: AudioFeatures): AudioFeatures =
    copy(
        beat = beat || heard.beat,
        beatStrength = maxOf(beatStrength, heard.beatStrength),
        transient = maxOf(transient, heard.transient),
        kick = maxOf(kick, heard.kick),
        snare = maxOf(snare, heard.snare),
        hat = maxOf(hat, heard.hat),
        arrival = arrival || heard.arrival,
    )
