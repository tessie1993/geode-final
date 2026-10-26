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
}

/**
 * How a playlist renders (#267). One video per track queues an ordinary Render for each. One
 * continuous video is a single Render of every track; how it behaves between tracks is still being
 * decided (#273), so Studio does not make one yet (#326).
 */
enum class PlaylistMode {
    ONE_VIDEO_PER_TRACK,
    ONE_CONTINUOUS_VIDEO,
}

/** The loudness a Render's sound is levelled to. The Render carries it; levelling it is #26. */
enum class RenderLoudness {
    LEAVE_AS_IS,

    /** About -14 LUFS, the level YouTube plays at. */
    MINUS_14_LUFS,

    /** About -11 LUFS, where short-form feeds sit. */
    MINUS_11_LUFS,
}

/** What a Render adds to the Visual and the sound (#26): only the loudness target so far. */
data class RenderExtras(
    val loudness: RenderLoudness = RenderLoudness.LEAVE_AS_IS,
)

/** A Track as a Render needs it: its id, its [title] for the video's name, and its [length]. */
data class RenderTrack(
    val id: TrackId,
    val title: String,
    val length: Millis,
)

/**
 * Everything the person chose in the Render setup (#22): the Track, or the playlist's [tracks] in
 * order; the [look]; the [format]; the [length]; the [project] the videos land in (the default Project
 * when null); how a playlist renders; and the [extras]. These choices belong to the Render, never to
 * the song.
 */
data class RenderSpec(
    val tracks: List<RenderTrack>,
    val look: RenderLook = RenderLook.Current,
    val format: RenderFormat = RenderFormat(),
    val length: RenderLength = RenderLength.WholeTrack,
    val project: ProjectId? = null,
    val playlist: PlaylistMode = PlaylistMode.ONE_VIDEO_PER_TRACK,
    val extras: RenderExtras = RenderExtras(),
) {
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
 * One video the queue makes (#338): its [tracks] (one Track, or a whole playlist for one continuous
 * video) with the choices of the [RenderSpec] it was queued from. [batch] is the first Render queued
 * with it, so every track of one playlist shares it; [project] is the Project its video lands in.
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
) {
    /** The video's name: its Track's title, or every title of a continuous playlist. */
    val title: String get() = tracks.joinToString(TITLE_SEPARATOR) { it.title }

    /** Whether it still waits or runs. */
    val active: Boolean get() = status == RenderStatus.Queued || status is RenderStatus.Running

    private companion object {
        const val TITLE_SEPARATOR = " · "
    }
}
