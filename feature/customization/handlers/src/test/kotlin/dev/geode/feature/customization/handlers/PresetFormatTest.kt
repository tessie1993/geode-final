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
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetFormatTest {
    private val fallback = SceneId("julia")

    /** A preset with every part, every kind of target, source, shape and start setting. */
    private val everything =
        Preset(
            version = PRESET_VERSION,
            name = "Everything",
            style = SceneId("tunnel"),
            parts = PresetPart.entries.toSet(),
            shared = mapOf(SharedProperty.ZOOM to 1.75f, SharedProperty.LUT to -1f, SharedProperty.INVERT to 1f),
            extras = mapOf(ExtraKind.FLIGHT to 0.3f, ExtraKind.COUNT to 0.8125f),
            macros =
                listOf(
                    Macro("Energy", 0.25f, listOf(MacroLink(Target.Shared(SharedProperty.SPEED), 0.1f, 0.9f))),
                    Macro("Depth", links = listOf(MacroLink(Target.Extra(ExtraKind.FLIGHT), 0.9f, 0.2f))),
                    Macro("Empty", 0.75f),
                ),
            reactions =
                listOf(
                    Reaction(ReactionId(1), ReactionSource.Sound(SoundSignal.BASS), Target.Shared(SharedProperty.GLOW)),
                    Reaction(
                        ReactionId(2),
                        ReactionSource.Generated(ModulatorId(3)),
                        Target.MacroSlot(4),
                        amount = 0.4f,
                        invert = true,
                        rise = Millis(150),
                        fall = Millis(900),
                        low = 0.1f,
                        high = 0.6f,
                        curve = ResponseCurve.S_CURVE,
                    ),
                    Reaction(
                        ReactionId(3),
                        ReactionSource.Sound(SoundSignal.SURGE),
                        Target.ModulatorField(ModulatorId(4), ModulatorParameter.PATTERN),
                    ),
                    Reaction(
                        ReactionId(4),
                        ReactionSource.Sound(SoundSignal.HIT),
                        Target.Extra(ExtraKind.COUNT),
                        curve = ResponseCurve.EXPONENTIAL,
                    ),
                ),
            modulators =
                listOf(
                    Modulator(ModulatorId(1), ModulatorShape.Lfo(Wave.SQUARE, Millis(2_000), beatSync = true)),
                    Modulator(
                        ModulatorId(2),
                        ModulatorShape.HitEnvelope(SoundSignal.KICK, Millis(5), Millis(400), 0.35f),
                        StartMode.OnSound(SoundSignal.KICK),
                    ),
                    Modulator(
                        ModulatorId(3),
                        ModulatorShape.StepSequencer(listOf(0f, 0.5f, 1f, 0.25f), Millis(250)),
                        StartMode.ByHand,
                        rate = 2f,
                        depth = 0.5f,
                        playing = true,
                    ),
                    Modulator(ModulatorId(4), ModulatorShape.RandomDrift(Millis(8_000))),
                ),
            signals = mapOf(SoundSignal.BASS to SignalTrim(gain = 1.5f, floor = 0.1f), SoundSignal.TREBLE to SignalTrim.NEUTRAL),
        )

    @Test
    fun `a preset reads back as it was written`() {
        assertEquals(StoredPreset.Current(everything), PresetFormat.decode(PresetFormat.encode(everything), fallback))
    }

    @Test
    fun `a preset with only some parts reads back as it was written`() {
        val partial =
            Preset(
                PRESET_VERSION,
                "Camera only",
                SceneId("julia"),
                setOf(PresetPart.CAMERA),
                shared =
                    mapOf(SharedProperty.SHAKE to 0.5f),
            )
        assertEquals(StoredPreset.Current(partial), PresetFormat.decode(PresetFormat.encode(partial), fallback))
    }

    @Test
    fun `a file names its version, its Style and its parts`() {
        val o = JSONObject(PresetFormat.encode(everything))
        assertEquals(2, o.getInt("version"))
        assertEquals("tunnel", o.getString("style"))
        assertEquals("CAMERA", o.getJSONArray("parts").getString(1))
        assertEquals(1.75, o.getJSONObject("shared").getDouble("ZOOM"), 0.0)
    }

    @Test
    fun `a file without a version is an old preset, read field by field`() {
        val stored = PresetFormat.decode(oldPresetFile("Old", "tunnel", zoom = 2.0), fallback)
        assertEquals(
            StoredPreset.Legacy(
                LegacyPreset(
                    name = "Old",
                    style = SceneId("tunnel"),
                    numbers = mapOf("attack" to 0.1, "decay" to 0.4, "zoom" to 2.0, "speed" to 1.0, "strobe" to 0.0),
                    switches = mapOf("kaleidoscope" to true),
                ),
            ),
            stored,
        )
    }

    @Test
    fun `an old preset whose look was a user shader lands on the default Style`() {
        val stored = PresetFormat.decode(oldPresetFile("Mine", "plasma", extra = ",\n\"customShader\": \"void main() {}\""), fallback)
        assertEquals(fallback, (stored as StoredPreset.Legacy).preset.style)
    }

    @Test
    fun `a newer preset is read for what this version knows`() {
        val newer =
            JSONObject(PresetFormat.encode(everything)).apply {
                put("version", PRESET_VERSION + 1)
                put("comet", JSONObject().put("tail", 3))
                getJSONArray("parts").put("COMETS")
                getJSONObject("shared").put("COMET_TAIL", 0.5)
                getJSONArray("reactions").put(
                    JSONObject(getJSONArray("reactions").getJSONObject(0).toString()).put("target", JSONObject().put("comet", 1)),
                )
                getJSONArray("modulators").put(
                    JSONObject(getJSONArray("modulators").getJSONObject(0).toString()).put("shape", JSONObject().put("kind", "comet")),
                )
            }
        assertEquals(StoredPreset.Current(everything.copy(version = PRESET_VERSION + 1)), PresetFormat.decode(newer.toString(), fallback))
    }

    @Test(expected = JSONException::class)
    fun `a file that is not a preset is refused`() {
        PresetFormat.decode("[1, 2, 3]", fallback)
    }

    @Test
    fun `a preset's file is named after it, safely`() {
        assertEquals("Deep zoom.json", PresetLibrary.fileNameOf("Deep zoom"))
        assertEquals("Bass_Drop_-8609fd85.json", PresetLibrary.fileNameOf("Bass/Drop!"))
    }
}
