package dev.geode.feature.studio.api

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId

/** One Render in the queue, by the id Studio's database gave it. */
@JvmInline
value class RenderId(
    val value: Long,
) {
    override fun toString(): String = "render $value"
}

/**
 * The look a Render draws with (#22): the Customization as it is when the Render is queued, or a saved
 * preset applied over it. Either way the full Customization, Automations included, is frozen when the
 * Render is queued, so changing the look afterwards never changes a Render already in the queue.
 */
sealed interface RenderLook {
    data object Current : RenderLook

    data class Preset(
        val preset: PresetId,
    ) : RenderLook
}

/** A Render's shape, [wide] to [high]. */
enum class RenderAspect(
    val wide: Int,
    val high: Int,
) {
    R16_9(16, 9),
    R9_16(9, 16),
    R1_1(1, 1),
    R4_5(4, 5),
    R4_3(4, 3),
    R21_9(21, 9),
}

/** A Render's size, by its short side (#22). */
enum class RenderSize(
    val shortSide: Int,
) {
    SD480(480),
    HD720(720),
    FHD1080(1080),
    QHD1440(1440),
    UHD4K(2160),
}

/** How much detail the encoder keeps: the bit rate it is given for the size and frame rate. */
enum class RenderQuality {
    STANDARD,
    HIGH,
    BEST,
}

enum class RenderCodec {
    H264,
    HEVC,
}

/**
 * What a Render's video is as a file. A Render is not bound to real time (#266), so any size and any
 * frame rate in [FRAME_RATES] may be asked for; the phone's encoder has the last word.
 */
data class RenderFormat(
    val aspect: RenderAspect = RenderAspect.R16_9,
    val size: RenderSize = RenderSize.FHD1080,
    val fps: Int = DEFAULT_FPS,
    val quality: RenderQuality = RenderQuality.HIGH,
    val codec: RenderCodec = RenderCodec.H264,
) {
    companion object {
        const val DEFAULT_FPS: Int = 60

        /** Every frame rate a Render may have. */
        val FRAME_RATES: IntRange = 12..120

        /** The frame rates the Render setup offers (#22). */
        val OFFERED_FPS: List<Int> = listOf(24, 30, 60)
    }
}

/** How much of each Track a Render covers. */
sealed interface RenderLength {
    data object WholeTrack : RenderLength

    /** [length] from [start]; a Track that ends sooner ends the Render with it. */
    data class Section(
        val start: Millis,
        val length: Millis,
    ) : RenderLength

    /**
     * One seamless loop (#346): the Visual of the Track's opening, as long as the Track up to five
     * minutes and trimmed to whole bars, whose last frame dissolves into its first, so it plays round
     * with no seam. The Track's sound runs under it for the loop's length.
     */
    data object Loop : RenderLength

    /**
     * [minutes] long (#346): a seamless loop of the Visual repeated until then, under the Track played
     * again and again. The loop is rendered once and repeated without drawing anything twice.
     */
    data class Minutes(
        val minutes: Int,
    ) : RenderLength {
        companion object {
            /** The lengths a Render may be: up to ten hours. */
            val RANGE: IntRange = 1..600
        }
    }
}

/**
 * How a playlist renders (#267). One video per track queues an ordinary Render for each. One
 * continuous video is a single Render of every track, each whole (#326, as the owner decided on #273):
 * the sound joins as the Playback settings' crossfade joins it, the Visual moves to the next track's own
 * Customization on the first downbeat of that track, and the video has a chapter per track. It is as long
 * as the playlist, limited only by the phone's free storage.
 */
enum class PlaylistMode {
    ONE_VIDEO_PER_TRACK,
    ONE_CONTINUOUS_VIDEO,
}

/** The shape of a crossfade, as the Playback settings offer it: how the two songs' levels cross. */
enum class FadeCurve {
    LINEAR,
    EQUAL_POWER,
    SMOOTH,
}

/**
 * How one continuous video's tracks join (#326): the Playback settings' crossfade, its [crossfade] length
 * (none joins them gaplessly) and its [curve]. Kept with the Render, so making it again joins them the same.
 */
data class TrackJoin(
    val crossfade: Millis = Millis.ZERO,
    val curve: FadeCurve = FadeCurve.EQUAL_POWER,
)

/** The loudness a Render's sound is levelled to (#329): measured over the Track, then a gain applied. */
enum class RenderLoudness {
    LEAVE_AS_IS,

    /** About -14 LUFS, the level YouTube plays at. */
    MINUS_14_LUFS,

    /** About -11 LUFS, where short-form feeds sit. */
    MINUS_11_LUFS,
}

/**
 * What a Render adds to the sound and beside the Visual (#329): its [loudness] target, which the preset
 * sets; and, made as an Edit of the finished Render (they belong to Edits, never to the Visual), the
 * song's synced lyrics as [captions] when it has them in a local `.lrc` file or its own tags, and a
 * [titleCard] and [endCard] with the song and artist. Kept with the Render, so making it again applies
 * them again.
 */
data class RenderExtras(
    val loudness: RenderLoudness = RenderLoudness.LEAVE_AS_IS,
    val captions: Boolean = false,
    val titleCard: Boolean = false,
    val endCard: Boolean = false,
) {
    /** Whether the finished Render is made into an Edit with captions or cards. */
    val finishing: Boolean get() = captions || titleCard || endCard
}

/**
 * A Track as a Render needs it: its id, its [title] for the video's name, its [length], and its
 * [artist] for the title and end cards (#329), blank when it is not known.
 */
data class RenderTrack(
    val id: TrackId,
    val title: String,
    val length: Millis,
    val artist: String = "",
)

/**
 * Everything the person chose in the Render setup (#22): the Track, or the playlist's [tracks] in
 * order; the [look]; the [format]; the [length]; the [project] the videos land in (the default Project
 * when null); how a playlist renders; the [extras]; and the MilkDrop preset a MilkDrop look plays
 * ([milkdrop], its file name in the MilkDrop folder; none plays MilkDrop's own idle picture, #341).
 * One continuous video (#326) also has a look for each track ([trackLooks], in the tracks' order; a
 * track without one takes [look]) and how its tracks [join], the Playback settings' crossfade as it
 * was when it was set up. These choices belong to the Render, never to the song.
 */
data class RenderSpec(
    val tracks: List<RenderTrack>,
    val look: RenderLook = RenderLook.Current,
    val format: RenderFormat = RenderFormat(),
    val length: RenderLength = RenderLength.WholeTrack,
    val project: ProjectId? = null,
    val playlist: PlaylistMode = PlaylistMode.ONE_VIDEO_PER_TRACK,
    val extras: RenderExtras = RenderExtras(),
    val milkdrop: String? = null,
    val trackLooks: List<RenderLook> = emptyList(),
    val join: TrackJoin = TrackJoin(),
) {
    /** Whether it makes one continuous video of several tracks (#326). */
    val continuous: Boolean get() = playlist == PlaylistMode.ONE_CONTINUOUS_VIDEO && tracks.size > 1

    /** The look of each track, in order: its own, or [look] where it has none. */
    val looksOfTracks: List<RenderLook> get() = looksOf(tracks, trackLooks, look)

    /** [preset] applied: what it sets is filled in, and what it does not set stays as chosen. */
    fun withPreset(preset: RenderPreset): RenderSpec =
        copy(
            format =
                format.copy(
                    aspect = preset.aspect ?: format.aspect,
                    size = preset.size ?: format.size,
                    fps = preset.fps ?: format.fps,
                ),
            length = preset.length ?: length,
            extras = extras.copy(loudness = preset.loudness ?: extras.loudness),
        )
}

/**
 * The platform presets (owner's table on #22). Each sets only what it names; null leaves the person's
 * choice. Choosing a preset and then changing a value makes the format Custom.
 */
enum class RenderPreset(
    val aspect: RenderAspect?,
    val size: RenderSize?,
    val fps: Int?,
    val length: RenderLength?,
    val loudness: RenderLoudness?,
) {
    /** 9:16, 1080p, 30 fps, under a minute, -14 LUFS. */
    TIKTOK_AND_SHORTS(
        RenderAspect.R9_16,
        RenderSize.FHD1080,
        30,
        RenderLength.Section(Millis.ZERO, Millis(59_000)),
        RenderLoudness.MINUS_14_LUFS,
    ),

    /** 16:9, 4K, 60 fps, the whole Track. */
    YOUTUBE(RenderAspect.R16_9, RenderSize.UHD4K, 60, RenderLength.WholeTrack, null),

    FEED(RenderAspect.R4_5, null, null, null, null),

    SQUARE(RenderAspect.R1_1, null, null, null, null),

    /** The Render as chosen, the whole Track. */
    MASTER(null, null, null, RenderLength.WholeTrack, null),
}

/** What a running Render is doing. */
enum class RenderPhase {
    /** Getting the look and the Track ready. */
    PREPARING,

    /** Analysing the whole Track first (ADR-0001), or reading back its analysis. */
    ANALYSING,

    RENDERING,

    /** Saving the finished video and adding it to its Project. */
    SAVING,
}

/** Why a Render could not be made. */
enum class RenderProblem {
    /** The look frozen when it was queued could not be read back. */
    LOOK_UNAVAILABLE,

    /** The Track could not be read or analysed. */
    TRACK_UNREADABLE,

    /** The phone's encoder would not make the video at this size or frame rate. */
    ENCODER_REFUSED,

    /** The phone's free storage would not hold the video (#326). */
    NO_SPACE,

    /** The finished video could not be saved. */
    NOT_SAVED,

    /** The engine stopped unexpectedly. */
    ENGINE_FAILED,
}

/** Where a Render in the queue has got. */
sealed interface RenderStatus {
    data object Queued : RenderStatus

    /**
     * Under way: [progress] (0..1) of the whole Render, and the [secondsLeft] once they can be told.
     * [cancelling] once it is cancelled, until the engine has stopped.
     */
    data class Running(
        val phase: RenderPhase,
        val progress: Float,
        val secondsLeft: Long? = null,
        val cancelling: Boolean = false,
    ) : RenderStatus

    /** Made, and added to its Project. */
    data object Done : RenderStatus

    data class Failed(
        val problem: RenderProblem,
    ) : RenderStatus

    data object Cancelled : RenderStatus
}

/**
 * What a video in the queue is made from (#328, #329): a Short, an Export as sibling or a Render's
 * finishing Edit is made from the video [file] and lands in the library as a [kind], linked to it.
 */
data class MadeFrom(
    val file: String,
    val kind: VideoKind,
)

/**
 * One video the queue makes (#338): its [tracks] (one Track, or a whole playlist for one continuous
 * video, #326, with each track's look in [trackLooks] and how they [join]) with the
 * choices of the [RenderSpec] it was queued from. [batch] is the first Render queued
 * with it, so every track of one playlist shares it; [project] is the Project its video lands in;
 * [milkdrop] the MilkDrop preset it plays when its look is MilkDrop (#341). An Edit's export (#318)
 * waits in the same queue: it has an [edit] and no tracks, and its video is cut, not drawn, so it runs
 * while Visuals off is on too. [madeFrom] is the video it is made from, for a Short, an Export as
 * sibling or a Render's finishing Edit (#328, #329).
 */
data class RenderJob(
    val id: RenderId,
    val batch: RenderId,
    val tracks: List<RenderTrack>,
    val look: RenderLook,
    val format: RenderFormat,
    val length: RenderLength,
    val project: ProjectId,
    val extras: RenderExtras,
    val status: RenderStatus = RenderStatus.Queued,
    val milkdrop: String? = null,
    val edit: EditJob? = null,
    val madeFrom: MadeFrom? = null,
    val trackLooks: List<RenderLook> = emptyList(),
    val join: TrackJoin = TrackJoin(),
) {
    /** The look of each track, in order: its own, or [look] where it has none. */
    val looksOfTracks: List<RenderLook> get() = looksOf(tracks, trackLooks, look)

    /** The video's name: its Track's title, every title of a continuous playlist, or the Edit's name. */
    val title: String get() = edit?.name ?: tracks.joinToString(TITLE_SEPARATOR) { it.title }

    /** Whether it still waits or runs. */
    val active: Boolean get() = status == RenderStatus.Queued || status is RenderStatus.Running

    private companion object {
        const val TITLE_SEPARATOR = " · "
    }
}

/** The look of each of [tracks]: its own in [own], in order, or [fallback] where it has none. */
private fun looksOf(
    tracks: List<RenderTrack>,
    own: List<RenderLook>,
    fallback: RenderLook,
): List<RenderLook> = tracks.indices.map { own.getOrNull(it) ?: fallback }
