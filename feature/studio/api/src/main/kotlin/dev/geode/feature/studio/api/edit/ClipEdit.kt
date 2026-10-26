package dev.geode.feature.studio.api.edit

import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderSize

/** The clip editor's one-tap looks: each sets the grade it names. */
enum class ClipLook(
    val label: String,
) {
    NONE("As shot"),
    PUNCH("Punch"),
    BLEACH("Bleach"),
    NEON("Neon"),
    NIGHT("Night"),
    MONO("Mono"),
    INVERT("Invert"),
    ;

    fun applyTo(edit: ClipEdit): ClipEdit =
        edit.copy(
            brightness = brightness,
            contrast = contrast,
            saturation = saturation,
            hueDegrees = hue,
            monochrome = this == MONO,
            invert = this == INVERT,
        )

    private val brightness: Float
        get() =
            when (this) {
                PUNCH, MONO -> 0.02f
                BLEACH -> 0.06f
                NIGHT -> -0.14f
                else -> 0f
            }

    private val contrast: Float
        get() =
            when (this) {
                PUNCH -> 0.28f
                BLEACH -> 0.18f
                NEON -> 0.22f
                NIGHT -> 0.12f
                MONO -> 0.2f
                else -> 0f
            }

    private val saturation: Float
        get() =
            when (this) {
                PUNCH -> 30f
                BLEACH -> -55f
                NEON -> 65f
                NIGHT -> -15f
                else -> 0f
            }

    private val hue: Float
        get() =
            when (this) {
                NEON -> 14f
                NIGHT -> -18f
                else -> 0f
            }
}

/**
 * One clip's edit (#318): its trim, look, grade, LUT, gamma, speed, rotation, reframe, mute and
 * caption, as data. The Media3 effects that carry it out are Studio's handlers'. [ratio] reframes
 * to a shape at [quality]'s size; null keeps the clip as shot. The reframe's crop sits at [cropX]
 * across (0 at the left edge, 1 at the right) and [cropY] down (0 at the top), centred unless moved
 * (Make Short, #328).
 */
data class ClipEdit(
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val look: ClipLook = ClipLook.NONE,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val hueDegrees: Float = 0f,
    val monochrome: Boolean = false,
    val invert: Boolean = false,
    val speed: Float = 1f,
    val rotationDegrees: Float = 0f,
    val ratio: RenderAspect? = null,
    val quality: RenderSize = RenderSize.FHD1080,
    val mute: Boolean = false,
    val caption: String = "",
    val lutUri: String? = null,
    val gammaRed: Float = 1f,
    val gammaGreen: Float = 1f,
    val gammaBlue: Float = 1f,
    val cropX: Float = CENTRED,
    val cropY: Float = CENTRED,
) {
    val hasGamma: Boolean get() = gammaRed != 1f || gammaGreen != 1f || gammaBlue != 1f

    /** How much of a source of [sourceDurationMs] the trim keeps. */
    fun trimmedMs(sourceDurationMs: Long): Long {
        val end = if (endMs > 0) endMs.coerceAtMost(sourceDurationMs) else sourceDurationMs
        return (end - startMs).coerceAtLeast(0L)
    }

    /** How long the clip plays once trimmed and sped up or slowed down. */
    fun outputMs(sourceDurationMs: Long): Long = (trimmedMs(sourceDurationMs) / speed.coerceAtLeast(MIN_SPEED)).toLong()

    /** Whether it changes nothing about a source of [sourceDurationMs]. */
    fun isIdentity(sourceDurationMs: Long): Boolean =
        startMs == 0L &&
            (endMs == 0L || endMs >= sourceDurationMs) &&
            brightness == 0f &&
            contrast == 0f &&
            saturation == 0f &&
            hueDegrees == 0f &&
            !monochrome &&
            !invert &&
            speed == 1f &&
            rotationDegrees == 0f &&
            ratio == null &&
            !mute &&
            caption.isBlank() &&
            lutUri == null &&
            !hasGamma

    companion object {
        /** Where a crop sits until it is moved: the middle of the picture. */
        const val CENTRED: Float = 0.5f

        /** The slowest a speed divides by, so a zero speed never stretches a clip without end. */
        private const val MIN_SPEED = 0.01f
    }
}

/** A clip grade value a keyframe track may drive (`clip.<field>`), within [min]..[max]. */
data class ClipParam(
    val id: ParamId,
    val min: Float,
    val max: Float,
    val read: (ClipEdit) -> Float,
    val write: (ClipEdit, Float) -> ClipEdit,
)

/** The clip grade values keyframes may animate, and how a frame's animated values fold into an edit. */
object ClipParams {
    val SPEED: ParamId = ParamId("clip.speed")

    val all: List<ClipParam> =
        listOf(
            ClipParam(ParamId("clip.brightness"), -1f, 1f, { it.brightness }) { e, v -> e.copy(brightness = v) },
            ClipParam(ParamId("clip.contrast"), -1f, 1f, { it.contrast }) { e, v -> e.copy(contrast = v) },
            ClipParam(ParamId("clip.saturation"), -100f, 100f, { it.saturation }) { e, v -> e.copy(saturation = v) },
            ClipParam(ParamId("clip.hueDegrees"), -180f, 180f, { it.hueDegrees }) { e, v -> e.copy(hueDegrees = v) },
            ClipParam(SPEED, 0.25f, 4f, { it.speed }) { e, v -> e.copy(speed = v) },
            ClipParam(ParamId("clip.rotationDegrees"), -180f, 180f, { it.rotationDegrees }) { e, v -> e.copy(rotationDegrees = v) },
        )

    private val byId: Map<ParamId, ClipParam> = all.associateBy { it.id }

    fun find(id: ParamId): ClipParam? = byId[id]

    /**
     * [base] with the animated [values] folded in, each held to its range; ids that are not clip
     * values, and values that are not one number, are ignored.
     */
    fun applyTo(
        base: ClipEdit,
        values: Map<ParamId, ParamValue>,
    ): ClipEdit =
        values.entries.fold(base) { edit, (id, value) ->
            val param = byId[id]
            if (param == null || value !is ParamValue.Scalar) edit else param.write(edit, value.value.coerceIn(param.min, param.max))
        }
}
