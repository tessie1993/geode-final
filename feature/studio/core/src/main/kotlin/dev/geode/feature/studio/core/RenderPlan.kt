package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderCodec
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderQuality
import dev.geode.feature.studio.api.RenderSpec

/** A video frame's size in pixels. */
data class FrameSize(
    val width: Int,
    val height: Int,
)

/** The part of a Track a Render covers: [length] from [start]. */
data class TrackSection(
    val start: Millis,
    val length: Millis,
)

/**
 * A long-form Render (#346): a seamless [loop] of the Visual from the Track's start, repeated to [total].
 * The loop is trimmed to whole bars once the Track's tempo is known, so [total] may run past it.
 */
data class LongForm(
    val loop: Millis,
    val total: Millis,
)

/**
 * The pure decisions behind a Render (#338): which Renders a setup queues, and the frame size, bit rate
 * and section each is made at. The size and section rules are the old export's (`ExportAspect`,
 * `ExportRange`), moved here with tests.
 */
object RenderPlan {
    /** The longest side the encoders of every phone Geode runs on accept. */
    const val MAX_SIDE: Int = 4096

    /** A section shorter than this is not worth a video: the whole Track is rendered instead. */
    val MIN_SECTION: Millis = Millis(1_000)

    /** Bits per pixel per frame at each quality, for H.264; HEVC gets the same picture for less. */
    private const val STANDARD_BITS = 0.06
    private const val HIGH_BITS = 0.1
    private const val BEST_BITS = 0.16
    private const val HEVC_SHARE = 0.65

    /**
     * The longest side a Render's supersampled scene may reach (#345): a frame this large is still one
     * phone GPUs draw to, where the scene targets of a 4K frame drawn twice over would not fit.
     */
    const val MAX_SCENE_SIDE: Int = 4096

    private const val STANDARD_SUPERSAMPLE = 1f
    private const val HIGH_SUPERSAMPLE = 1.5f
    private const val BEST_SUPERSAMPLE = 2f

    /** The bit rate never goes past what phone encoders take, nor under what keeps a small video clean. */
    const val MAX_BIT_RATE: Int = 120_000_000
    const val MIN_BIT_RATE: Int = 1_000_000

    /**
     * The Renders [spec] queues into [project] (#325): one per track, in order, when each track gets its
     * own video. One continuous video of several tracks (#326) is a single Render of every track, each
     * whole, with the look of each and how they join; a continuous video of one track is an ordinary
     * Render of it.
     */
    fun rendersOf(
        spec: RenderSpec,
        project: ProjectId,
    ): List<NewRender> {
        if (spec.continuous) {
            val joined =
                NewRender(
                    spec.tracks,
                    spec.look,
                    spec.format,
                    RenderLength.WholeTrack,
                    project,
                    spec.extras,
                    spec.milkdrop,
                    trackLooks = spec.looksOfTracks,
                    join = spec.join,
                )
            return listOf(joined)
        }
        return spec.tracks.map { track ->
            NewRender(listOf(track), spec.look, spec.format, spec.length, project, spec.extras, spec.milkdrop)
        }
    }

    /**
     * [format]'s frame: its size on the short side at its aspect. When the long side would pass
     * [MAX_SIDE] both shrink to keep the shape; both are even, as encoders need.
     */
    fun frameOf(format: RenderFormat): FrameSize {
        val aspect = format.aspect
        val wide = maxOf(aspect.wide, aspect.high)
        val narrow = minOf(aspect.wide, aspect.high)
        var short = format.size.shortSide
        var long = (short.toLong() * wide / narrow).toInt()
        if (long > MAX_SIDE) {
            short = (short.toLong() * MAX_SIDE / long).toInt()
            long = MAX_SIDE
        }
        val landscape = aspect.wide >= aspect.high
        return FrameSize(even(if (landscape) long else short), even(if (landscape) short else long))
    }

    /** The bit rate [format] is encoded at: its pixels a second times its quality's bits per pixel. */
    fun bitRateOf(format: RenderFormat): Int {
        val frame = frameOf(format)
        val perPixel =
            when (format.quality) {
                RenderQuality.STANDARD -> STANDARD_BITS
                RenderQuality.HIGH -> HIGH_BITS
                RenderQuality.BEST -> BEST_BITS
            }
        val share = if (format.codec == RenderCodec.HEVC) HEVC_SHARE else 1.0
        val rate = frame.width.toDouble() * frame.height * format.fps * perPixel * share
        return rate.toLong().coerceIn(MIN_BIT_RATE.toLong(), MAX_BIT_RATE.toLong()).toInt()
    }

    /**
     * How many times over [format]'s scene is drawn before the composite brings it to the frame (#345): a
     * Render is not bound to real time, so High draws 1.5 times over and Best twice, while Standard draws
     * at the frame's own size. Never so much that the scene's long side passes [MAX_SCENE_SIDE]; no frame
     * is longer than [MAX_SIDE], which is no longer than that, so it is never under 1.
     */
    fun supersampleOf(format: RenderFormat): Float {
        val wanted =
            when (format.quality) {
                RenderQuality.STANDARD -> STANDARD_SUPERSAMPLE
                RenderQuality.HIGH -> HIGH_SUPERSAMPLE
                RenderQuality.BEST -> BEST_SUPERSAMPLE
            }
        val frame = frameOf(format)
        val room = MAX_SCENE_SIDE.toFloat() / maxOf(frame.width, frame.height)
        return minOf(wanted, room)
    }

    /** The shortest and longest a seamless loop is (the old loop render's limits). */
    val MIN_LOOP: Millis = Millis(30_000)
    val MAX_LOOP: Millis = Millis(300_000)

    private const val MILLIS_PER_MINUTE = 60_000L

    /**
     * The long form [length] asks for of a Track of [trackLength], or null when it is not a loop: a loop
     * as long as the Track, kept between [MIN_LOOP] and [MAX_LOOP] (the longest when the length is not
     * known); for [RenderLength.Loop] that is the whole video, and for [RenderLength.Minutes] it repeats
     * to that many minutes, kept within [RenderLength.Minutes.RANGE].
     */
    fun longFormOf(
        length: RenderLength,
        trackLength: Millis,
    ): LongForm? {
        val loop = Millis(if (trackLength.value > 0) trackLength.value.coerceIn(MIN_LOOP.value, MAX_LOOP.value) else MAX_LOOP.value)
        return when (length) {
            RenderLength.Loop -> LongForm(loop, loop)
            is RenderLength.Minutes -> LongForm(loop, Millis(length.minutes.coerceIn(RenderLength.Minutes.RANGE) * MILLIS_PER_MINUTE))
            RenderLength.WholeTrack, is RenderLength.Section -> null
        }
    }

    /** How many times a Track of [trackLength] plays back to back to last [total]: at least once. */
    fun playsFor(
        total: Millis,
        trackLength: Millis,
    ): Int {
        if (trackLength.value <= 0) return 1
        return ((total.value + trackLength.value - 1) / trackLength.value).toInt().coerceAtLeast(1)
    }

    /**
     * The part of a Track of [trackLength] that [length] covers, or null for the whole Track: when the
     * section is the whole Track, when it leaves less than [MIN_SECTION] of it, or when the Track's
     * length is not known.
     */
    fun sectionOf(
        length: RenderLength,
        trackLength: Millis,
    ): TrackSection? {
        val end = trackLength.value
        if (length !is RenderLength.Section || end <= 0) return null
        val from = length.start.value.coerceIn(0, end)
        val to = (length.start.value + length.length.value).coerceIn(0, end)
        if (to - from < MIN_SECTION.value || (from == 0L && to == end)) return null
        return TrackSection(Millis(from), Millis(to - from))
    }

    private fun even(value: Int): Int = value + value % 2
}
