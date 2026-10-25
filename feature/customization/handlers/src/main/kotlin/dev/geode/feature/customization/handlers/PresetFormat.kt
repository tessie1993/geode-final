package dev.geode.feature.customization.handlers

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Macro
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.PRESET_VERSION
import dev.geode.feature.customization.api.Preset
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.ResponseCurve
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StartMode
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave
import dev.geode.feature.customization.core.LegacyPreset
import org.json.JSONArray
import org.json.JSONObject

/** A preset file as read: one in the current format, or one an older Geode saved (version 1). */
sealed interface StoredPreset {
    data class Current(
        val preset: Preset,
    ) : StoredPreset

    data class Legacy(
        val preset: LegacyPreset,
    ) : StoredPreset
}

/**
 * The preset file format (ADR-0004: small, versioned JSON files that can be shared). Version 1 was the
 * flat `SceneParams` file, which has no `version` key; it is read as a [LegacyPreset] for the one-time
 * upgrade. A file from a newer Geode is read for what this version knows: an entry it does not know
 * (a new Shared property, Extra, curve or kind of target) is left out rather than failing the file.
 */
object PresetFormat {
    /** [preset] as the text of its file. */
    fun encode(preset: Preset): String =
        JSONObject()
            .put(VERSION, PRESET_VERSION)
            .put(NAME, preset.name)
            .put(STYLE, preset.style.value)
            .put(PARTS, JSONArray(preset.parts.sortedBy { it.ordinal }.map { it.name }))
            .put(SHARED, numbers(preset.shared.mapKeys { it.key.name }))
            .put(EXTRAS, numbers(preset.extras.mapKeys { it.key.name }))
            .put(MACROS, JSONArray(preset.macros.map(::macro)))
            .put(REACTIONS, JSONArray(preset.reactions.map(::reaction)))
            .put(MODULATORS, JSONArray(preset.modulators.map(::modulator)))
            .put(SIGNALS, JSONObject(preset.signals.entries.associate { (signal, trim) -> signal.name to trim(trim) }))
            .toString(INDENT)

    /**
     * Reads a preset file. An old file whose look was a user shader, which is gone (#87), lands on
     * [fallbackStyle], the app's default Style. Throws [org.json.JSONException] when [text] is not a
     * preset at all.
     */
    fun decode(
        text: String,
        fallbackStyle: SceneId,
    ): StoredPreset {
        val o = JSONObject(text)
        val version = o.optInt(VERSION, LEGACY_VERSION)
        return if (version <= LEGACY_VERSION) StoredPreset.Legacy(legacy(o, fallbackStyle)) else StoredPreset.Current(current(o, version))
    }

    private fun current(
        o: JSONObject,
        version: Int,
    ): Preset =
        Preset(
            version = version,
            name = o.getString(NAME),
            style = SceneId(o.getString(STYLE)),
            parts = names(o.optJSONArray(PARTS)).mapNotNull { enumOrNull<PresetPart>(it) }.toSet(),
            shared = floats(o.optJSONObject(SHARED)).mapNotNullKeys { enumOrNull<SharedProperty>(it) },
            extras = floats(o.optJSONObject(EXTRAS)).mapNotNullKeys { enumOrNull<ExtraKind>(it) },
            macros = objects(o.optJSONArray(MACROS)).map(::macroOf),
            reactions = objects(o.optJSONArray(REACTIONS)).mapNotNull(::reactionOf),
            modulators = objects(o.optJSONArray(MODULATORS)).mapNotNull(::modulatorOf),
            signals = signalsOf(o.optJSONObject(SIGNALS)),
        )

    private fun legacy(
        o: JSONObject,
        fallbackStyle: SceneId,
    ): LegacyPreset {
        val numbers = mutableMapOf<String, Double>()
        val switches = mutableMapOf<String, Boolean>()
        for (key in o.keys()) {
            when (val value = o.get(key)) {
                is Boolean -> switches[key] = value
                is Number -> numbers[key] = value.toDouble()
            }
        }
        val style = if (o.has(LEGACY_USER_SHADER)) fallbackStyle else SceneId(o.getString(LEGACY_STYLE))
        return LegacyPreset(o.getString(NAME), style, numbers, switches)
    }

    private fun macro(macro: Macro): JSONObject =
        JSONObject()
            .put(NAME, macro.name)
            .put(VALUE, macro.value.toDouble())
            .put(LINKS, JSONArray(macro.links.map(::link)))

    private fun macroOf(o: JSONObject): Macro =
        Macro(
            name = o.getString(NAME),
            value = o.getDouble(VALUE).toFloat(),
            links = objects(o.optJSONArray(LINKS)).mapNotNull(::linkOf),
        )

    private fun link(link: MacroLink): JSONObject =
        JSONObject()
            .put(TARGET, target(link.target))
            .put(LOW, link.low.toDouble())
            .put(HIGH, link.high.toDouble())

    private fun linkOf(o: JSONObject): MacroLink? {
        val target = targetOf(o.getJSONObject(TARGET)) ?: return null
        return MacroLink(target, o.getDouble(LOW).toFloat(), o.getDouble(HIGH).toFloat())
    }

    private fun reaction(reaction: Reaction): JSONObject =
        JSONObject()
            .put(ID, reaction.id.value)
            .put(SOURCE, source(reaction.source))
            .put(TARGET, target(reaction.target))
            .put(AMOUNT, reaction.amount.toDouble())
            .put(INVERT, reaction.invert)
            .put(RISE, reaction.rise.value)
            .put(FALL, reaction.fall.value)
            .put(LOW, reaction.low.toDouble())
            .put(HIGH, reaction.high.toDouble())
            .put(CURVE, reaction.curve.name)

    private fun reactionOf(o: JSONObject): Reaction? {
        val source = sourceOf(o.getJSONObject(SOURCE)) ?: return null
        val target = targetOf(o.getJSONObject(TARGET)) ?: return null
        val curve = enumOrNull<ResponseCurve>(o.getString(CURVE)) ?: return null
        return Reaction(
            id = ReactionId(o.getInt(ID)),
            source = source,
            target = target,
            amount = o.getDouble(AMOUNT).toFloat(),
            invert = o.getBoolean(INVERT),
            rise = Millis(o.getLong(RISE)),
            fall = Millis(o.getLong(FALL)),
            low = o.getDouble(LOW).toFloat(),
            high = o.getDouble(HIGH).toFloat(),
            curve = curve,
        )
    }

    private fun source(source: ReactionSource): JSONObject =
        when (source) {
            is ReactionSource.Sound -> JSONObject().put(SOUND, source.signal.name)
            is ReactionSource.Generated -> JSONObject().put(MODULATOR, source.modulator.value)
        }

    private fun sourceOf(o: JSONObject): ReactionSource? =
        when {
            o.has(SOUND) -> enumOrNull<SoundSignal>(o.getString(SOUND))?.let(ReactionSource::Sound)
            o.has(MODULATOR) -> ReactionSource.Generated(ModulatorId(o.getInt(MODULATOR)))
            else -> null
        }

    private fun target(target: Target): JSONObject =
        when (target) {
            is Target.Shared -> JSONObject().put(SHARED_TARGET, target.property.name)
            is Target.Extra -> JSONObject().put(EXTRA_TARGET, target.kind.name)
            is Target.MacroSlot -> JSONObject().put(MACRO_TARGET, target.index)
            is Target.ModulatorField ->
                JSONObject().put(MODULATOR, target.modulator.value).put(PARAMETER, target.parameter.name)
        }

    private fun targetOf(o: JSONObject): Target? =
        when {
            o.has(SHARED_TARGET) -> enumOrNull<SharedProperty>(o.getString(SHARED_TARGET))?.let(Target::Shared)
            o.has(EXTRA_TARGET) -> enumOrNull<ExtraKind>(o.getString(EXTRA_TARGET))?.let(Target::Extra)
            o.has(MACRO_TARGET) -> Target.MacroSlot(o.getInt(MACRO_TARGET))
            o.has(MODULATOR) ->
                enumOrNull<ModulatorParameter>(o.getString(PARAMETER))?.let {
                    Target.ModulatorField(ModulatorId(o.getInt(MODULATOR)), it)
                }
            else -> null
        }

    private fun modulator(modulator: Modulator): JSONObject =
        JSONObject()
            .put(ID, modulator.id.value)
            .put(SHAPE, shape(modulator.shape))
            .put(START, start(modulator.start))
            .put(RATE, modulator.rate.toDouble())
            .put(DEPTH, modulator.depth.toDouble())
            .put(PLAYING, modulator.playing)

    private fun modulatorOf(o: JSONObject): Modulator? {
        val shape = shapeOf(o.getJSONObject(SHAPE)) ?: return null
        val start = startOf(o.getJSONObject(START)) ?: return null
        return Modulator(
            id = ModulatorId(o.getInt(ID)),
            shape = shape,
            start = start,
            rate = o.getDouble(RATE).toFloat(),
            depth = o.getDouble(DEPTH).toFloat(),
            playing = o.getBoolean(PLAYING),
        )
    }

    private fun shape(shape: ModulatorShape): JSONObject =
        when (shape) {
            is ModulatorShape.Lfo ->
                JSONObject()
                    .put(KIND, LFO)
                    .put(WAVE, shape.wave.name)
                    .put(PERIOD, shape.period.value)
                    .put(BEAT_SYNC, shape.beatSync)
            is ModulatorShape.HitEnvelope ->
                JSONObject()
                    .put(KIND, HIT_ENVELOPE)
                    .put(SIGNAL, shape.signal.name)
                    .put(ATTACK, shape.attack.value)
                    .put(RELEASE, shape.release.value)
                    .put(THRESHOLD, shape.threshold.toDouble())
            is ModulatorShape.StepSequencer ->
                JSONObject()
                    .put(KIND, STEP_SEQUENCER)
                    .put(STEPS, JSONArray(shape.steps.map { it.toDouble() }))
                    .put(STEP_LENGTH, shape.stepLength.value)
                    .put(BEAT_SYNC, shape.beatSync)
            is ModulatorShape.RandomDrift -> JSONObject().put(KIND, RANDOM_DRIFT).put(PERIOD, shape.period.value)
        }

    private fun shapeOf(o: JSONObject): ModulatorShape? =
        when (o.getString(KIND)) {
            LFO ->
                enumOrNull<Wave>(o.getString(WAVE))?.let {
                    ModulatorShape.Lfo(it, Millis(o.getLong(PERIOD)), o.getBoolean(BEAT_SYNC))
                }
            HIT_ENVELOPE ->
                enumOrNull<SoundSignal>(o.getString(SIGNAL))?.let {
                    ModulatorShape.HitEnvelope(
                        it,
                        Millis(o.getLong(ATTACK)),
                        Millis(o.getLong(RELEASE)),
                        o.getDouble(THRESHOLD).toFloat(),
                    )
                }
            STEP_SEQUENCER ->
                ModulatorShape.StepSequencer(
                    steps = doubles(o.getJSONArray(STEPS)).map { it.toFloat() },
                    stepLength = Millis(o.getLong(STEP_LENGTH)),
                    beatSync = o.getBoolean(BEAT_SYNC),
                )
            RANDOM_DRIFT -> ModulatorShape.RandomDrift(Millis(o.getLong(PERIOD)))
            else -> null
        }

    private fun start(start: StartMode): JSONObject =
        when (start) {
            StartMode.WhilePlaying -> JSONObject().put(MODE, WHILE_PLAYING)
            is StartMode.OnSound -> JSONObject().put(MODE, ON_SOUND).put(SIGNAL, start.signal.name)
            StartMode.ByHand -> JSONObject().put(MODE, BY_HAND)
        }

    private fun startOf(o: JSONObject): StartMode? =
        when (o.getString(MODE)) {
            WHILE_PLAYING -> StartMode.WhilePlaying
            ON_SOUND -> enumOrNull<SoundSignal>(o.getString(SIGNAL))?.let(StartMode::OnSound)
            BY_HAND -> StartMode.ByHand
            else -> null
        }

    private fun trim(trim: SignalTrim): JSONObject = JSONObject().put(GAIN, trim.gain.toDouble()).put(FLOOR, trim.floor.toDouble())

    private fun signalsOf(o: JSONObject?): Map<SoundSignal, SignalTrim> {
        if (o == null) return emptyMap()
        return o
            .keys()
            .asSequence()
            .toList()
            .mapNotNull { key ->
                val signal = enumOrNull<SoundSignal>(key) ?: return@mapNotNull null
                val trim = o.getJSONObject(key)
                signal to SignalTrim(trim.getDouble(GAIN).toFloat(), trim.getDouble(FLOOR).toFloat())
            }.toMap()
    }

    private fun numbers(values: Map<String, Float>): JSONObject = JSONObject(values.mapValues { it.value.toDouble() })

    private fun floats(o: JSONObject?): Map<String, Float> =
        o
            ?.keys()
            ?.asSequence()
            ?.associateWith { o.getDouble(it).toFloat() }
            .orEmpty()

    private fun names(array: JSONArray?): List<String> = if (array == null) emptyList() else List(array.length()) { array.getString(it) }

    private fun doubles(array: JSONArray): List<Double> = List(array.length()) { array.getDouble(it) }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else List(array.length()) { array.getJSONObject(it) }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }

    private inline fun <K> Map<String, Float>.mapNotNullKeys(key: (String) -> K?): Map<K, Float> =
        entries.mapNotNull { (name, value) -> key(name)?.let { it to value } }.toMap()

    /** The version old files carry implicitly: they have no version key. */
    private const val LEGACY_VERSION = 1
    private const val INDENT = 2

    private const val VERSION = "version"
    private const val NAME = "name"
    private const val STYLE = "style"
    private const val PARTS = "parts"
    private const val SHARED = "shared"
    private const val EXTRAS = "extras"
    private const val MACROS = "macros"
    private const val REACTIONS = "reactions"
    private const val MODULATORS = "modulators"
    private const val SIGNALS = "signals"
    private const val VALUE = "value"
    private const val LINKS = "links"
    private const val TARGET = "target"
    private const val LOW = "low"
    private const val HIGH = "high"
    private const val ID = "id"
    private const val SOURCE = "source"
    private const val AMOUNT = "amount"
    private const val INVERT = "invert"
    private const val RISE = "riseMs"
    private const val FALL = "fallMs"
    private const val CURVE = "curve"
    private const val SOUND = "sound"
    private const val MODULATOR = "modulator"
    private const val PARAMETER = "parameter"
    private const val SHARED_TARGET = "shared"
    private const val EXTRA_TARGET = "extra"
    private const val MACRO_TARGET = "macro"
    private const val SHAPE = "shape"
    private const val START = "start"
    private const val RATE = "rate"
    private const val DEPTH = "depth"
    private const val PLAYING = "playing"
    private const val KIND = "kind"
    private const val LFO = "lfo"
    private const val HIT_ENVELOPE = "hitEnvelope"
    private const val STEP_SEQUENCER = "stepSequencer"
    private const val RANDOM_DRIFT = "randomDrift"
    private const val WAVE = "wave"
    private const val PERIOD = "periodMs"
    private const val BEAT_SYNC = "beatSync"
    private const val SIGNAL = "signal"
    private const val ATTACK = "attackMs"
    private const val RELEASE = "releaseMs"
    private const val THRESHOLD = "threshold"
    private const val STEPS = "steps"
    private const val STEP_LENGTH = "stepLengthMs"
    private const val MODE = "mode"
    private const val WHILE_PLAYING = "whilePlaying"
    private const val ON_SOUND = "onSound"
    private const val BY_HAND = "byHand"
    private const val GAIN = "gain"
    private const val FLOOR = "floor"

    /** Version 1's keys: the Style id, and the user shader that replaced its look. */
    private const val LEGACY_STYLE = "sceneId"
    private const val LEGACY_USER_SHADER = "customShader"
}
