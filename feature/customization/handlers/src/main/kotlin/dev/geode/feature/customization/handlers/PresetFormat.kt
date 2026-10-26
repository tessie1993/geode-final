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
import org.json.JSONException
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
 * upgrade.
 *
 * A file is read the way MilkDrop reads its presets, for what can be read:
 * - an entry this version does not know (a new top-level part, Shared property, Extra, Sound signal,
 *   curve, kind of target, source, shape or start) is kept unapplied in [Preset.unread] and written
 *   back untouched, so a preset from a newer Geode survives being saved again here;
 * - an entry that is broken (a missing field or a value of the wrong kind) is skipped, and the rest
 *   of the preset still loads.
 */
object PresetFormat {
    /**
     * [preset] as the text of its file, with what it kept unread written back where it was. A preset
     * that kept parts of a newer format keeps that format's version.
     */
    fun encode(preset: Preset): String {
        val unread = preset.unread?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val o =
            JSONObject()
                .put(VERSION, maxOf(PRESET_VERSION, unread.optInt(VERSION, PRESET_VERSION)))
                .put(NAME, preset.name)
                .put(STYLE, preset.style.value)
                .put(PARTS, JSONArray(preset.parts.sortedBy { it.ordinal }.map { it.name }))
                .put(SHARED, numbers(preset.shared.mapKeys { it.key.name }))
                .put(EXTRAS, numbers(preset.extras.mapKeys { it.key.name }))
                .put(MACROS, JSONArray(preset.macros.map(::macro)))
                .put(REACTIONS, JSONArray(preset.reactions.map(::reaction)))
                .put(MODULATORS, JSONArray(preset.modulators.map(::modulator)))
                .put(SIGNALS, JSONObject(preset.signals.entries.associate { (signal, trim) -> signal.name to trim(trim) }))
        preset.milkdrop?.let { o.put(MILK_PRESET, it) }
        writeBack(o, unread)
        return o.toString(INDENT)
    }

    /** Puts what [unread] kept back into the file [o], each part where it was read from. */
    private fun writeBack(
        o: JSONObject,
        unread: JSONObject,
    ) {
        for (key in keysOf(unread)) {
            when (key) {
                VERSION -> Unit
                PARTS, REACTIONS, MODULATORS -> unread.optJSONArray(key)?.let(::items)?.forEach { o.getJSONArray(key).put(it) }
                SHARED, EXTRAS, SIGNALS ->
                    unread.optJSONObject(key)?.let { kept ->
                        val section = o.getJSONObject(key)
                        for (name in keysOf(kept)) if (!section.has(name)) section.put(name, kept.get(name))
                    }
                MACROS ->
                    unread.optJSONObject(key)?.let { kept ->
                        val macros = o.getJSONArray(MACROS)
                        for (index in keysOf(kept)) {
                            val links = index.toIntOrNull()?.let { macros.optJSONObject(it) }?.optJSONArray(LINKS) ?: continue
                            kept.optJSONArray(index)?.let(::items)?.forEach { links.put(it) }
                        }
                    }
                else -> if (!o.has(key)) o.put(key, unread.get(key))
            }
        }
    }

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
    ): Preset {
        val unread = Unread()
        for (key in keysOf(o)) if (key !in KNOWN) unread.part(key, o.get(key))
        val parts =
            names(o.optJSONArray(PARTS)).mapNotNull { name ->
                enumOrNull<PresetPart>(name) ?: null.also { unread.item(PARTS, name) }
            }
        val macros =
            objects(o.optJSONArray(MACROS)).mapIndexedNotNull { index, macro ->
                attempt { macroOf(macro) { link -> unread.link(index, link) } }.valueOrNull()
            }
        val preset =
            Preset(
                version = version,
                name = o.getString(NAME),
                style = SceneId(o.getString(STYLE)),
                parts = parts.toSet(),
                shared = section(o, SHARED, unread) { enumOrNull<SharedProperty>(it) },
                extras = section(o, EXTRAS, unread) { enumOrNull<ExtraKind>(it) },
                macros = macros,
                reactions = entries(o, REACTIONS, unread, ::reactionOf),
                modulators = entries(o, MODULATORS, unread, ::modulatorOf),
                signals = signalsOf(o.optJSONObject(SIGNALS), unread),
                milkdrop = o.opt(MILK_PRESET) as? String,
            )
        return preset.copy(unread = unread.text(version))
    }

    /**
     * What this version could not read in one file, laid out like the file: top-level parts by their
     * own keys, unknown names under [SHARED], [EXTRAS] and [SIGNALS], unknown entries appended under
     * [PARTS], [REACTIONS] and [MODULATORS], and unknown Macro links under [MACROS] by the Macro's place.
     */
    private class Unread {
        private val kept = JSONObject()

        fun part(
            key: String,
            value: Any,
        ) {
            kept.put(key, value)
        }

        fun entry(
            section: String,
            name: String,
            value: Any,
        ) {
            (kept.optJSONObject(section) ?: JSONObject().also { kept.put(section, it) }).put(name, value)
        }

        fun item(
            section: String,
            value: Any,
        ) {
            (kept.optJSONArray(section) ?: JSONArray().also { kept.put(section, it) }).put(value)
        }

        fun link(
            macro: Int,
            value: Any,
        ) {
            val macros = kept.optJSONObject(MACROS) ?: JSONObject().also { kept.put(MACROS, it) }
            (macros.optJSONArray(macro.toString()) ?: JSONArray().also { macros.put(macro.toString(), it) }).put(value)
        }

        /** The kept parts as text, with the file's [version] when it is newer; null when nothing was kept. */
        fun text(version: Int): String? {
            if (kept.length() == 0) return null
            if (version > PRESET_VERSION) kept.put(VERSION, version)
            return kept.toString()
        }
    }

    /** How reading one entry went: [Read], not known to this version, or broken. */
    private sealed interface Entry<out T> {
        data class Read<T>(
            val value: T,
        ) : Entry<T>

        data object Unknown : Entry<Nothing>

        data object Broken : Entry<Nothing>
    }

    private fun <T> Entry<T>.valueOrNull(): T? =
        when (this) {
            is Entry.Read -> value
            Entry.Unknown, Entry.Broken -> null
        }

    /** [read] of one entry: null from it means unknown; a missing field or a value of the wrong kind, broken. */
    private inline fun <T : Any> attempt(read: () -> T?): Entry<T> =
        try {
            read()?.let { Entry.Read(it) } ?: Entry.Unknown
        } catch (_: JSONException) {
            Entry.Broken
        } catch (_: IllegalArgumentException) {
            Entry.Broken
        }

    /** The entries of the array [key]: one this version does not know is kept in [unread], a broken one skipped. */
    private inline fun <T : Any> entries(
        o: JSONObject,
        key: String,
        unread: Unread,
        read: (JSONObject) -> T?,
    ): List<T> =
        objects(o.optJSONArray(key)).mapNotNull { entry ->
            when (val result = attempt { read(entry) }) {
                is Entry.Read -> result.value
                Entry.Unknown -> null.also { unread.item(key, entry) }
                Entry.Broken -> null
            }
        }

    /**
     * The numbers of the object [key] by name: a name this version does not know is kept in [unread],
     * a value that is not a number is skipped.
     */
    private inline fun <K : Any> section(
        o: JSONObject,
        key: String,
        unread: Unread,
        known: (String) -> K?,
    ): Map<K, Float> {
        val values = o.optJSONObject(key) ?: return emptyMap()
        return keysOf(values).mapNotNull { name ->
            val value = values.get(name)
            val k = known(name)
            when {
                k == null -> null.also { unread.entry(key, name, value) }
                value is Number && !value.toDouble().isNaN() -> k to value.toFloat()
                else -> null
            }
        }.toMap()
    }

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
        val milkPreset = o.opt(MILK_PRESET) as? String
        return LegacyPreset(o.getString(NAME), style, numbers, switches, milkPreset)
    }

    private fun macro(macro: Macro): JSONObject =
        JSONObject()
            .put(NAME, macro.name)
            .put(VALUE, macro.value.toDouble())
            .put(LINKS, JSONArray(macro.links.map(::link)))

    /** A Macro, its links read one by one: [unknown] keeps each link this version does not know. */
    private inline fun macroOf(
        o: JSONObject,
        unknown: (JSONObject) -> Unit,
    ): Macro {
        val name = o.getString(NAME)
        val value = o.getDouble(VALUE).toFloat()
        val links =
            objects(o.optJSONArray(LINKS)).mapNotNull { link ->
                when (val result = attempt { linkOf(link) }) {
                    is Entry.Read -> result.value
                    Entry.Unknown -> null.also { unknown(link) }
                    Entry.Broken -> null
                }
            }
        return Macro(name = name, value = value, links = links)
    }

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

    private fun signalsOf(
        o: JSONObject?,
        unread: Unread,
    ): Map<SoundSignal, SignalTrim> {
        if (o == null) return emptyMap()
        return keysOf(o)
            .mapNotNull { key ->
                val signal = enumOrNull<SoundSignal>(key) ?: return@mapNotNull null.also { unread.entry(SIGNALS, key, o.get(key)) }
                val trim = attempt { o.getJSONObject(key).let { SignalTrim(it.getDouble(GAIN).toFloat(), it.getDouble(FLOOR).toFloat()) } }
                trim.valueOrNull()?.let { signal to it }
            }.toMap()
    }

    private fun numbers(values: Map<String, Float>): JSONObject = JSONObject(values.mapValues { it.value.toDouble() })

    /** The names in [array]; an entry that is not a name is skipped. */
    private fun names(array: JSONArray?): List<String> =
        if (array == null) emptyList() else List(array.length()) { array.opt(it) }.filterIsInstance<String>()

    private fun keysOf(o: JSONObject): List<String> = o.keys().asSequence().toList()

    private fun items(array: JSONArray): List<Any> = List(array.length()) { array.get(it) }

    private fun doubles(array: JSONArray): List<Double> = List(array.length()) { array.getDouble(it) }

    /** The objects in [array]; an entry that is not an object is skipped. */
    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else List(array.length()) { array.opt(it) }.filterIsInstance<JSONObject>()

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }

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
    private const val MILK_PRESET = "milkPreset"

    /** The top-level keys this version reads; any other is kept unread. */
    private val KNOWN = setOf(VERSION, NAME, STYLE, PARTS, SHARED, EXTRAS, MACROS, REACTIONS, MODULATORS, SIGNALS, MILK_PRESET)

    /** Version 1's keys: the Style id, and the user shader that replaced its look. */
    private const val LEGACY_STYLE = "sceneId"
    private const val LEGACY_USER_SHADER = "customShader"
}
