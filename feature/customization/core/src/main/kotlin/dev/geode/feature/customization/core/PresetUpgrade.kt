package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.Preset
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave
import kotlin.math.roundToLong

/**
 * An old, version 1 preset as it was saved: the flat `SceneParams` fields of its JSON file, the
 * numbers in [numbers] and the switches in [switches], read by the handlers.
 */
data class LegacyPreset(
    val name: String,
    val style: SceneId,
    val numbers: Map<String, Double>,
    val switches: Map<String, Boolean>,
)

/** One of the old app-wide modulation slots (`LfoConfig`), by its enum names. */
data class LegacyLfo(
    val enabled: Boolean,
    val source: String,
    val target: String,
    val wave: String,
    val periodSeconds: Double,
    val depth: Double,
    val polarity: String,
)

/** One of the old app-wide envelopes (`AdsrConfig`), by its enum names. */
data class LegacyEnvelope(
    val enabled: Boolean,
    val targets: List<String>,
    val attackSeconds: Double,
    val releaseSeconds: Double,
    val amount: Double,
    val band: String,
)

/**
 * The result of converting one old preset: the new [preset], the old fields that had no place in the
 * new model ([dropped], for the log), and whether its Style is gone so it now sits on the default
 * Style ([restyled]).
 */
data class Upgrade(
    val preset: Preset,
    val dropped: List<String>,
    val restyled: Boolean,
)

/**
 * The one-time conversion of old presets on upgrade (#11, rounds 4 and 5): old LFOs become
 * Modulators, envelopes become Reactions, and a preset on a removed Style keeps its settings on the
 * default Style. Pure: the handlers back the originals up before they write the result.
 *
 * Only the old values a person changed away from the old defaults are carried over, so a Style's own
 * starting look (its retired in-Style effects included) survives. Strobe, flash, formDrive, user GLSL
 * and the other removed fields are dropped and named in [Upgrade.dropped].
 */
object PresetUpgrade {
    /** An old field that maps onto one Shared core property, with the old default. */
    private data class SharedField(
        val property: SharedProperty,
        val oldDefault: Double,
    )

    /** An old field that maps onto one Extra, with the old default. */
    private data class ExtraField(
        val kind: ExtraKind,
        val oldDefault: Double,
    )

    private val SHARED_NUMBERS: Map<String, SharedField> =
        mapOf(
            "zoom" to SharedField(SharedProperty.ZOOM, 1.0),
            "rotation" to SharedField(SharedProperty.ROTATION, 0.0),
            "sway" to SharedField(SharedProperty.SWAY, 0.0),
            "shake" to SharedField(SharedProperty.SHAKE, 0.0),
            "driftX" to SharedField(SharedProperty.DRIFT_X, 0.0),
            "driftY" to SharedField(SharedProperty.DRIFT_Y, 0.0),
            "endlessZoomSpeed" to SharedField(SharedProperty.DIVE_SPEED, OLD_DIVE_SPEED),
            "palette" to SharedField(SharedProperty.PALETTE, 0.0),
            "palette2" to SharedField(SharedProperty.PALETTE_2, 1.0),
            "paletteMix" to SharedField(SharedProperty.PALETTE_MIX, 0.0),
            "paletteLut" to SharedField(SharedProperty.LUT, -1.0),
            "morph" to SharedField(SharedProperty.MORPH, 0.0),
            "colorShift" to SharedField(SharedProperty.HUE_SHIFT, 0.0),
            "hueRange" to SharedField(SharedProperty.HUE_RANGE, 1.0),
            "cycleSpeed" to SharedField(SharedProperty.CYCLE_SPEED, OLD_CYCLE_SPEED),
            "speed" to SharedField(SharedProperty.SPEED, 1.0),
            "audioDrive" to SharedField(SharedProperty.AUDIO_DRIVE, 1.0),
            "turbulence" to SharedField(SharedProperty.TURBULENCE, 0.0),
            "marchDetail" to SharedField(SharedProperty.DETAIL, 1.0),
            "bloom" to SharedField(SharedProperty.GLOW, 0.0),
            "symmetry" to SharedField(SharedProperty.SYMMETRY, OLD_SYMMETRY),
            "trailZoom" to SharedField(SharedProperty.TRAIL_ZOOM, 0.0),
            "trailWarp" to SharedField(SharedProperty.TRAIL_WARP, 0.0),
            "grain" to SharedField(SharedProperty.GRAIN, 0.0),
            "vignette" to SharedField(SharedProperty.VIGNETTE, 0.0),
            "chromaAb" to SharedField(SharedProperty.CHROMATIC, 0.0),
            "scanlines" to SharedField(SharedProperty.SCANLINES, 0.0),
            "fisheye" to SharedField(SharedProperty.FISHEYE, 0.0),
            "warp" to SharedField(SharedProperty.WARP, 0.0),
            "ripple" to SharedField(SharedProperty.RIPPLE, 0.0),
            "twist" to SharedField(SharedProperty.TWIST, 0.0),
            "tile" to SharedField(SharedProperty.TILE, 1.0),
            "glitch" to SharedField(SharedProperty.GLITCH, 0.0),
            "pixelate" to SharedField(SharedProperty.PIXELATE, 0.0),
            "posterize" to SharedField(SharedProperty.POSTERIZE, 0.0),
            "particleSize" to SharedField(SharedProperty.PARTICLE_SIZE, 1.0),
            "saturation" to SharedField(SharedProperty.SATURATION, 1.0),
            "brightness" to SharedField(SharedProperty.BRIGHTNESS, 1.0),
            "intensity" to SharedField(SharedProperty.INTENSITY, 1.0),
            "contrast" to SharedField(SharedProperty.CONTRAST, 1.0),
            "gamma" to SharedField(SharedProperty.GAMMA, 1.0),
            "temperature" to SharedField(SharedProperty.TEMPERATURE, 0.0),
        )

    private val SHARED_SWITCHES: Map<String, SharedProperty> =
        mapOf(
            "endlessZoom" to SharedProperty.ENDLESS_ZOOM,
            "duotone" to SharedProperty.DUOTONE,
            "colorCycle" to SharedProperty.COLOUR_CYCLE,
            "kaleidoscope" to SharedProperty.KALEIDOSCOPE,
            "mirror" to SharedProperty.MIRROR,
            "solarize" to SharedProperty.SOLARIZE,
            "invert" to SharedProperty.INVERT,
        )

    /** The old Fluid, Water and Cymatics controls that are Extras now; applied only where the Style has them. */
    private val EXTRA_NUMBERS: Map<String, ExtraField> =
        mapOf(
            "fluidCurl" to ExtraField(ExtraKind.SWIRL, OLD_FLUID_CURL),
            "fluidVelocityDissipation" to ExtraField(ExtraKind.VISCOSITY, OLD_FLUID_VELOCITY),
            "fluidDensityDissipation" to ExtraField(ExtraKind.DECAY, 1.0),
            "fluidSplatRadius" to ExtraField(ExtraKind.SIZE, OLD_FLUID_RADIUS),
            "fluidSplatForce" to ExtraField(ExtraKind.FORCE, 1.0),
            "waterWaveSpeed" to ExtraField(ExtraKind.DRIFT, 1.0),
            "waterDamping" to ExtraField(ExtraKind.VISCOSITY, OLD_WATER_DAMPING),
            "waterRippleStrength" to ExtraField(ExtraKind.FORCE, 1.0),
            "waterDepth" to ExtraField(ExtraKind.FOG, OLD_WATER_DEPTH),
            "waterFlow" to ExtraField(ExtraKind.SWIRL, OLD_WATER_FLOW),
            "cymaticsModes" to ExtraField(ExtraKind.COUNT, OLD_CYMATICS_MODES),
            "cymaticsScale" to ExtraField(ExtraKind.SCALE, OLD_CYMATICS_SCALE),
            "cymaticsLine" to ExtraField(ExtraKind.THICKNESS, 1.0),
            "cymaticsRing" to ExtraField(ExtraKind.EDGE, OLD_CYMATICS_RING),
            "cymaticsSwirl" to ExtraField(ExtraKind.SWIRL, OLD_CYMATICS_SWIRL),
            "cymaticsFlow" to ExtraField(ExtraKind.DRIFT, OLD_CYMATICS_FLOW),
            "cymaticsFill" to ExtraField(ExtraKind.SIZE, OLD_CYMATICS_FILL),
            "cymaticsGeometry" to ExtraField(ExtraKind.VARIANT, 0.0),
        )

    /** The old band gains, now the normaliser's gain for that Sound signal. */
    private val GAINS: Map<String, SoundSignal> =
        mapOf("bassGain" to SoundSignal.BASS, "midGain" to SoundSignal.MID, "trebGain" to SoundSignal.TREBLE)

    /** The old particle shapes (Dot, Ring, Star, Square, Spark, Hex, Bubble) as the shared layer's kinds. */
    private val PARTICLE_KINDS: List<Float> = listOf(MOTES, BUBBLES, SPARKS, DUST, SPARKS, DUST, BUBBLES)

    /** The old modulation targets (`LfoTarget` names) that have a place in the new model. */
    private val TARGETS: Map<String, Target> =
        mapOf(
            "SPEED" to SharedProperty.SPEED,
            "ZOOM" to SharedProperty.ZOOM,
            "ROTATION" to SharedProperty.ROTATION,
            "SWAY" to SharedProperty.SWAY,
            "DRIFT_X" to SharedProperty.DRIFT_X,
            "DRIFT_Y" to SharedProperty.DRIFT_Y,
            "WARP" to SharedProperty.WARP,
            "RIPPLE" to SharedProperty.RIPPLE,
            "MORPH" to SharedProperty.MORPH,
            "TWIST" to SharedProperty.TWIST,
            "TILE" to SharedProperty.TILE,
            "PIXELATE" to SharedProperty.PIXELATE,
            "POSTERIZE" to SharedProperty.POSTERIZE,
            "COLOR_SHIFT" to SharedProperty.HUE_SHIFT,
            "PALETTE_MIX" to SharedProperty.PALETTE_MIX,
            "SATURATION" to SharedProperty.SATURATION,
            "BRIGHTNESS" to SharedProperty.BRIGHTNESS,
            "INTENSITY" to SharedProperty.INTENSITY,
            "BLOOM" to SharedProperty.GLOW,
            "TEMPERATURE" to SharedProperty.TEMPERATURE,
            "TURBULENCE" to SharedProperty.TURBULENCE,
            "CHROMA_AB" to SharedProperty.CHROMATIC,
            "VIGNETTE" to SharedProperty.VIGNETTE,
            "GLITCH" to SharedProperty.GLITCH,
            "FISHEYE" to SharedProperty.FISHEYE,
            "PARTICLE_SIZE" to SharedProperty.PARTICLE_SIZE,
            "TRAIL_LENGTH" to SharedProperty.TRAILS,
            "FLUID_GLOW" to SharedProperty.GLOW,
        ).mapValues { Target.Shared(it.value) } +
            mapOf(
                "FLUID_CURL" to ExtraKind.SWIRL,
                "FLUID_RADIUS" to ExtraKind.SIZE,
                "FLUID_FORCE" to ExtraKind.FORCE,
                "FLUID_FADE" to ExtraKind.DECAY,
                "WATER_RIPPLE" to ExtraKind.FORCE,
            ).mapValues { Target.Extra(it.value) } +
            (1..OLD_SLOTS).flatMap { slot ->
                listOf(
                    "LFO${slot}_RATE" to Target.ModulatorField(ModulatorId(slot), ModulatorParameter.RATE),
                    "LFO${slot}_DEPTH" to Target.ModulatorField(ModulatorId(slot), ModulatorParameter.DEPTH),
                )
            }

    /** The old follower sources (`ModSource` names) as Sound signals; "LFO" makes a Modulator instead. */
    private val SOURCES: Map<String, SoundSignal> =
        mapOf(
            "BASS" to SoundSignal.BASS,
            "MID" to SoundSignal.MID,
            "TREBLE" to SoundSignal.TREBLE,
            "LEVEL" to SoundSignal.LEVEL,
            "BRIGHTNESS" to SoundSignal.BRIGHTNESS,
            "TRANSIENT" to SoundSignal.HIT,
            "STEREO_WIDTH" to SoundSignal.STEREO_WIDTH,
            "STEREO_PAN" to SoundSignal.STEREO_PAN,
        )

    /** The old envelope bands (`EnvBand` names) as Sound signals. */
    private val BANDS: Map<String, SoundSignal> =
        mapOf(
            "BASS" to SoundSignal.BASS,
            "MID" to SoundSignal.MID,
            "TREBLE" to SoundSignal.TREBLE,
            "RMS" to SoundSignal.LEVEL,
            "BRIGHTNESS" to SoundSignal.BRIGHTNESS,
            "WIDTH" to SoundSignal.STEREO_WIDTH,
        )

    /** The fields read together with another one rather than on their own. */
    private val PAIRED: Set<String> = setOf("trails", "trailLength", "particleShape", "pulse")

    /**
     * Converts [legacy], with the app-wide [lfos] and [envelopes] it was used with, into a current
     * preset on its own Style, or on [fallbackStyle] (the app's single default Style constant) when
     * [styles] no longer has it.
     */
    fun upgrade(
        legacy: LegacyPreset,
        lfos: List<LegacyLfo>,
        envelopes: List<LegacyEnvelope>,
        styles: StyleCatalogue,
        fallbackStyle: SceneId,
    ): Upgrade {
        val restyled = styles[legacy.style] == null
        val start = Defaults.customization(styles.manifestOf(if (restyled) fallbackStyle else legacy.style))
        val used = mutableSetOf<String>()
        val withShared = shared(start, legacy, used)
        val gains = GAINS.filterKeys { it in legacy.numbers }
        used += gains.keys
        val withSignals = withShared.copy(signals = withShared.signals + gains.map { (key, signal) -> signal to gain(legacy, key) })
        val withExtras = extras(withSignals, legacy, used, styles)
        val modulated = modulation(withExtras, lfos, envelopes, legacy)
        val customization = Rules.prune(modulated)
        val dropped = (legacy.numbers.keys + legacy.switches.keys).filterNot { it in used || it in PAIRED }.sorted()
        return Upgrade(PresetRules.capture(legacy.name, customization, PresetRules.ALL_PARTS), dropped, restyled)
    }

    private fun gain(
        legacy: LegacyPreset,
        key: String,
    ): SignalTrim = Rules.trim(SignalTrim(gain = legacy.numbers.getValue(key).toFloat()))

    private fun shared(
        start: Customization,
        legacy: LegacyPreset,
        used: MutableSet<String>,
    ): Customization {
        val values = start.shared.toMutableMap()
        for ((key, field) in SHARED_NUMBERS) {
            val value = legacy.numbers[key] ?: continue
            used += key
            if (value != field.oldDefault) values[field.property] = Rules.shared(field.property, value.toFloat())
        }
        for ((key, property) in SHARED_SWITCHES) {
            val on = legacy.switches[key] ?: continue
            used += key
            if (on) values[property] = 1f
        }
        // Old trails were a switch and a length; the shared Trails is one amount that is 0 when off.
        if (legacy.switches["trails"] == true) {
            val length = legacy.numbers["trailLength"] ?: OLD_TRAIL_LENGTH
            values[SharedProperty.TRAILS] = Rules.shared(SharedProperty.TRAILS, length.toFloat())
        }
        legacy.numbers["particleShape"]?.let { shape ->
            values[SharedProperty.PARTICLE_KIND] = PARTICLE_KINDS.getOrElse(shape.toInt()) { MOTES }
        }
        return start.copy(shared = values)
    }

    private fun extras(
        start: Customization,
        legacy: LegacyPreset,
        used: MutableSet<String>,
        styles: StyleCatalogue,
    ): Customization {
        val manifest = styles[start.style]
        val values = start.extras.toMutableMap()
        for ((key, field) in EXTRA_NUMBERS) {
            val value = legacy.numbers[key] ?: continue
            used += key
            val spec = manifest?.extras?.firstOrNull { it.kind == field.kind }
            if (spec != null && value != field.oldDefault) {
                values[field.kind] = Rules.unit(((value - spec.nativeMin) / (spec.nativeMax - spec.nativeMin)).toFloat())
            }
        }
        return start.copy(extras = values)
    }

    /** Old LFO slots become Modulators (or Reactions, for a follower slot), envelopes and the transient pulse become Reactions. */
    private fun modulation(
        start: Customization,
        lfos: List<LegacyLfo>,
        envelopes: List<LegacyEnvelope>,
        legacy: LegacyPreset,
    ): Customization {
        val modulators = mutableListOf<Modulator>()
        val reactions = start.reactions.toMutableList()

        fun add(
            source: ReactionSource,
            target: Target,
            amount: Double,
            invert: Boolean = false,
            rise: Millis = Millis.ZERO,
            fall: Millis = Millis.ZERO,
        ) {
            val id = ReactionId((reactions.maxOfOrNull { it.id.value } ?: 0) + 1)
            reactions += Rules.reaction(Reaction(id, source, target, amount.toFloat(), invert, rise, fall))
        }
        lfos.forEachIndexed { index, lfo ->
            val target = TARGETS[lfo.target]
            if (!lfo.enabled || target == null) return@forEachIndexed
            val invert = lfo.polarity == NEGATIVE
            if (lfo.source == LFO) {
                val id = ModulatorId(index + 1)
                val wave = Wave.entries.firstOrNull { it.name == lfo.wave } ?: Wave.SINE
                modulators += Rules.modulator(Modulator(id, ModulatorShape.Lfo(wave, seconds(lfo.periodSeconds))))
                add(ReactionSource.Generated(id), target, lfo.depth, invert)
            } else {
                val signal = SOURCES[lfo.source] ?: return@forEachIndexed
                add(ReactionSource.Sound(signal), target, lfo.depth, invert)
            }
        }
        for (envelope in envelopes.filter { it.enabled }) {
            val signal = BANDS[envelope.band] ?: continue
            for (target in envelope.targets.mapNotNull { TARGETS[it] }) {
                val rise = seconds(envelope.attackSeconds)
                add(ReactionSource.Sound(signal), target, envelope.amount, rise = rise, fall = seconds(envelope.releaseSeconds))
            }
        }
        // The old transient pulse was a hard-wired zoom kick; it becomes a visible Reaction.
        val pulse = legacy.numbers["pulse"] ?: 0.0
        if (pulse > 0.0) add(ReactionSource.Sound(SoundSignal.HIT), Target.Shared(SharedProperty.ZOOM), pulse, fall = PULSE_FALL)
        return start.copy(modulators = modulators, reactions = reactions)
    }

    private fun seconds(value: Double): Millis =
        if (value.isNaN()) Millis.ZERO else Millis((value * MILLIS_PER_SECOND).roundToLong().coerceAtLeast(0L))

    private const val LFO = "LFO"
    private const val NEGATIVE = "NEGATIVE"
    private const val OLD_SLOTS = 3
    private const val MILLIS_PER_SECOND = 1_000.0
    private val PULSE_FALL = Millis(250)

    private const val OLD_DIVE_SPEED = 0.3
    private const val OLD_CYCLE_SPEED = 0.1
    private const val OLD_SYMMETRY = 6.0
    private const val OLD_TRAIL_LENGTH = 0.5
    private const val OLD_FLUID_CURL = 30.0
    private const val OLD_FLUID_VELOCITY = 0.2
    private const val OLD_FLUID_RADIUS = 0.12
    private const val OLD_WATER_DAMPING = 0.985
    private const val OLD_WATER_DEPTH = 0.6
    private const val OLD_WATER_FLOW = 0.3
    private const val OLD_CYMATICS_MODES = 5.0
    private const val OLD_CYMATICS_SCALE = 3.2
    private const val OLD_CYMATICS_RING = 0.35
    private const val OLD_CYMATICS_SWIRL = 0.05
    private const val OLD_CYMATICS_FLOW = 0.35
    private const val OLD_CYMATICS_FILL = 0.45

    private const val MOTES = 0f
    private const val SPARKS = 1f
    private const val DUST = 2f
    private const val BUBBLES = 3f
}
