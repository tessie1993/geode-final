package dev.geode.studio

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.BeatSpan
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CameraMove
import dev.geode.feature.customization.api.CameraMoveKind
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Layer
import dev.geode.feature.customization.api.Lane
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.PRESET_VERSION
import dev.geode.feature.customization.api.PaletteChanges
import dev.geode.feature.customization.api.Preset
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.StepLength
import dev.geode.feature.customization.api.StyleMark
import dev.geode.feature.customization.api.StylePool
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Trigger
import dev.geode.feature.customization.handlers.PresetFormat
import dev.geode.feature.customization.handlers.StoredPreset
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Render's frozen look as Studio keeps it (#338): the whole Customization, Automations included, as
 * small versioned JSON. Everything a preset holds is written in Customization's own preset format,
 * with every part kept, so the two never drift; the Automations, which presets leave out, are written
 * beside it. A value this version does not know is left out rather than losing the look.
 */
object RenderLookFormat {
    private const val CURRENT_VERSION = 1

    fun encode(
        customization: Customization,
        context: SwitchingContext = SwitchingContext(),
    ): String =
        JSONObject()
            .put(VERSION, CURRENT_VERSION)
            .put(LOOK, JSONObject(PresetFormat.encode(presetOf(customization))))
            .put(AUTOMATIONS, automations(customization.automations))
            .put(SWITCHING_FROM, switchingContext(context))
            .toString()

    /**
     * What Style switching picks from beside the Customization in [text] (#343), as it was frozen; empty
     * for a look frozen before it was kept. Throws [org.json.JSONException] when [text] is not a look.
     */
    fun decodeContext(text: String): SwitchingContext {
        val o = JSONObject(text).optJSONObject(SWITCHING_FROM) ?: return SwitchingContext()
        val marks = HashMap<SceneId, StyleMark>()
        o.optJSONObject(MARKS)?.let { kept ->
            kept.keys().forEach { id -> enumOrNull<StyleMark>(kept.optString(id))?.let { marks[SceneId(id)] = it } }
        }
        return SwitchingContext(
            visualizer = strings(o.optJSONArray(VISUALIZER)).map(::SceneId),
            marks = marks,
            favourites = strings(o.optJSONArray(FAVOURITES)).map(::SceneId),
        )
    }

    private fun switchingContext(context: SwitchingContext): JSONObject =
        JSONObject()
            .put(VISUALIZER, JSONArray(context.visualizer.map { it.value }))
            .put(MARKS, JSONObject(context.marks.map { (id, mark) -> id.value to mark.name }.toMap()))
            .put(FAVOURITES, JSONArray(context.favourites.map { it.value }))

    private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else List(array.length()) { array.getString(it) }

    /**
     * The Customization [text] holds. A Style this version no longer has lands on [fallbackStyle].
     * Throws [org.json.JSONException] or [IllegalArgumentException] when [text] is not a look.
     */
    fun decode(
        text: String,
        fallbackStyle: SceneId,
    ): Customization {
        val o = JSONObject(text)
        val stored = PresetFormat.decode(o.getJSONObject(LOOK).toString(), fallbackStyle)
        require(stored is StoredPreset.Current) { "a frozen look is never in the old preset format" }
        val preset = stored.preset
        return Customization(
            style = preset.style,
            shared = preset.shared,
            extras = preset.extras,
            macros = preset.macros,
            reactions = preset.reactions,
            modulators = preset.modulators,
            signals = preset.signals,
            automations = automationsOf(o.optJSONObject(AUTOMATIONS)),
            layer = preset.layer ?: Layer.OFF,
        )
    }

    private fun presetOf(customization: Customization): Preset =
        Preset(
            version = PRESET_VERSION,
            name = LOOK,
            style = customization.style,
            parts = PresetPart.entries.toSet(),
            shared = customization.shared,
            extras = customization.extras,
            macros = customization.macros,
            reactions = customization.reactions,
            modulators = customization.modulators,
            signals = customization.signals,
            layer = customization.layer,
        )

    private fun automations(automations: Automations): JSONObject {
        val o = JSONObject()
        automations.style?.let { style ->
            o.put(
                STYLE,
                JSONObject()
                    .put(CADENCE, cadence(style.cadence))
                    .put(POOL, style.pool.name)
                    .put(SEED, style.seed)
                    .put(TRANSITION_MS, style.transition.value)
                    .put(CHOSEN, JSONArray(style.chosen.map { it.value })),
            )
        }
        automations.palette?.let { palette ->
            o.put(PALETTE, JSONObject().put(CADENCE, cadence(palette.cadence)).put(FADE_MS, palette.fade.value))
        }
        o.put(CAMERA, JSONArray(automations.camera.map(::cameraMove)))
        o.put(LANES, JSONArray(automations.lanes.map(::lane)))
        return o
    }

    private fun automationsOf(o: JSONObject?): Automations {
        if (o == null) return Automations.NONE
        return Automations(
            style = o.optJSONObject(STYLE)?.let(::styleSwitchingOf),
            palette = o.optJSONObject(PALETTE)?.let(::paletteOf),
            camera = objects(o.optJSONArray(CAMERA)).mapNotNull(::cameraMoveOf),
            lanes = objects(o.optJSONArray(LANES)).mapNotNull(::laneOf),
        )
    }

    private fun styleSwitchingOf(o: JSONObject): StyleSwitching {
        val defaults = StyleSwitching()
        return StyleSwitching(
            cadence = cadenceOf(o.optJSONObject(CADENCE), defaults.cadence),
            pool = enumOrNull<StylePool>(o.optString(POOL)) ?: defaults.pool,
            seed = o.optLong(SEED, defaults.seed),
            transition = Millis(o.optLong(TRANSITION_MS, defaults.transition.value)),
            chosen = strings(o.optJSONArray(CHOSEN)).map(::SceneId),
        )
    }

    private fun paletteOf(o: JSONObject): PaletteChanges {
        val defaults = PaletteChanges()
        return PaletteChanges(cadenceOf(o.optJSONObject(CADENCE), defaults.cadence), Millis(o.optLong(FADE_MS, defaults.fade.value)))
    }

    private fun cadence(cadence: Cadence): JSONObject {
        val trigger =
            when (val t = cadence.trigger) {
                is Trigger.BigMoments -> JSONObject().put(KIND, BIG_MOMENTS).put(THRESHOLD, t.threshold.toDouble())
                is Trigger.EveryBars -> JSONObject().put(KIND, EVERY_BARS).put(BARS, t.bars)
            }
        return JSONObject().put(TRIGGER, trigger).put(SPACING_MS, cadence.spacing.value)
    }

    private fun cadenceOf(
        o: JSONObject?,
        fallback: Cadence,
    ): Cadence {
        if (o == null) return fallback
        val t = o.optJSONObject(TRIGGER) ?: JSONObject()
        val trigger =
            when (t.optString(KIND)) {
                BIG_MOMENTS -> Trigger.BigMoments(t.optDouble(THRESHOLD, Trigger.BigMoments.DEFAULT_THRESHOLD.toDouble()).toFloat())
                EVERY_BARS -> Trigger.EveryBars(t.optInt(BARS, Trigger.EveryBars.DEFAULT_BARS))
                else -> fallback.trigger
            }
        return Cadence(trigger, Millis(o.optLong(SPACING_MS, fallback.spacing.value)))
    }

    private fun cameraMove(move: CameraMove): JSONObject =
        JSONObject().put(KIND, move.kind.name).put(EVERY, move.every.name).put(STRENGTH, move.strength.toDouble())

    private fun cameraMoveOf(o: JSONObject): CameraMove? {
        val kind = enumOrNull<CameraMoveKind>(o.optString(KIND)) ?: return null
        val every = enumOrNull<BeatSpan>(o.optString(EVERY)) ?: return null
        return CameraMove(kind, every, o.optDouble(STRENGTH, CameraMove.DEFAULT_STRENGTH.toDouble()).toFloat())
    }

    private fun lane(lane: Lane): JSONObject =
        JSONObject()
            .put(TARGETS, JSONArray(lane.targets.map(::target)))
            .put(STEPS, JSONArray(lane.steps.map { step -> JSONArray(step.map { it.toDouble() }) }))
            .put(STEP, lane.step.name)

    private fun laneOf(o: JSONObject): Lane? {
        val targets = objects(o.optJSONArray(TARGETS)).map(::targetOf)
        // A lane whose targets this version does not know would set the wrong ones: it is left out whole.
        if (targets.any { it == null }) return null
        val steps = o.optJSONArray(STEPS) ?: return null
        val step = enumOrNull<StepLength>(o.optString(STEP)) ?: return null
        return Lane(
            targets = targets.filterNotNull(),
            steps = List(steps.length()) { i -> steps.getJSONArray(i).let { s -> List(s.length()) { s.getDouble(it).toFloat() } } },
            step = step,
        )
    }

    private fun target(target: Target): JSONObject =
        when (target) {
            is Target.Shared -> JSONObject().put(SHARED, target.property.name)
            is Target.Extra -> JSONObject().put(EXTRA, target.kind.name)
            is Target.MacroSlot -> JSONObject().put(MACRO, target.index)
            is Target.ModulatorField -> JSONObject().put(MODULATOR, target.modulator.value).put(PARAMETER, target.parameter.name)
        }

    private fun targetOf(o: JSONObject): Target? =
        when {
            o.has(SHARED) -> enumOrNull<SharedProperty>(o.getString(SHARED))?.let(Target::Shared)
            o.has(EXTRA) -> enumOrNull<ExtraKind>(o.getString(EXTRA))?.let(Target::Extra)
            o.has(MACRO) -> Target.MacroSlot(o.getInt(MACRO))
            o.has(MODULATOR) ->
                enumOrNull<ModulatorParameter>(o.optString(PARAMETER))?.let { Target.ModulatorField(ModulatorId(o.getInt(MODULATOR)), it) }
            else -> null
        }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array ==
            null
        ) {
            emptyList()
        } else {
            List(array.length()) { array.getJSONObject(it) }
        }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }

    private const val VERSION = "version"
    private const val LOOK = "look"
    private const val AUTOMATIONS = "automations"
    private const val STYLE = "style"
    private const val PALETTE = "palette"
    private const val CAMERA = "camera"
    private const val LANES = "lanes"
    private const val CADENCE = "cadence"
    private const val POOL = "pool"
    private const val SEED = "seed"
    private const val TRANSITION_MS = "transitionMs"
    private const val FADE_MS = "fadeMs"
    private const val TRIGGER = "trigger"
    private const val SPACING_MS = "spacingMs"
    private const val KIND = "kind"
    private const val BIG_MOMENTS = "bigMoments"
    private const val EVERY_BARS = "everyBars"
    private const val THRESHOLD = "threshold"
    private const val BARS = "bars"
    private const val EVERY = "every"
    private const val STRENGTH = "strength"
    private const val TARGETS = "targets"
    private const val STEPS = "steps"
    private const val STEP = "step"
    private const val SHARED = "shared"
    private const val EXTRA = "extra"
    private const val MACRO = "macro"
    private const val MODULATOR = "modulator"
    private const val PARAMETER = "parameter"
    private const val CHOSEN = "chosen"
    private const val SWITCHING_FROM = "switchingFrom"
    private const val VISUALIZER = "visualizer"
    private const val MARKS = "marks"
    private const val FAVOURITES = "favourites"
}
