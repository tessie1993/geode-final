package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.PRESET_VERSION
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave
import dev.geode.feature.customization.core.Fixtures.GONE
import dev.geode.feature.customization.core.Fixtures.JULIA
import dev.geode.feature.customization.core.Fixtures.NEBULA
import dev.geode.feature.customization.core.Fixtures.WATER
import dev.geode.feature.customization.core.Fixtures.julia
import dev.geode.feature.customization.core.Fixtures.styles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetUpgradeTest {
    private val delta = 1e-5f

    private fun legacy(
        style: SceneId = JULIA,
        numbers: Map<String, Double> = emptyMap(),
        switches: Map<String, Boolean> = emptyMap(),
    ) = LegacyPreset("Old", style, numbers, switches)

    private fun upgrade(
        preset: LegacyPreset,
        lfos: List<LegacyLfo> = emptyList(),
        envelopes: List<LegacyEnvelope> = emptyList(),
        fallback: SceneId = JULIA,
    ): Upgrade = PresetUpgrade.upgrade(preset, lfos, envelopes, styles, fallback)

    private fun lfo(
        source: String,
        target: String,
        enabled: Boolean = true,
        wave: String = "TRIANGLE",
        polarity: String = "NEGATIVE",
    ) = LegacyLfo(enabled, source, target, wave, periodSeconds = 4.0, depth = 0.5, polarity = polarity)

    @Test
    fun `an untouched old preset becomes the Style's own start`() {
        val result = upgrade(legacy())
        assertEquals(PresetRules.capture("Old", julia, PresetRules.ALL_PARTS), result.preset)
        assertEquals(PRESET_VERSION, result.preset.version)
        assertFalse(result.restyled)
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    fun `changed shared values carry over, in range`() {
        val numbers =
            mapOf(
                "zoom" to 2.0,
                "speed" to 1.0,
                "brightness" to 2.0,
                "trailLength" to 0.7,
                "particleShape" to 4.0,
                "bassGain" to 2.0,
                "midGain" to 9.0,
                "palette" to 3.0,
            )
        val switches = mapOf("mirror" to true, "kaleidoscope" to false, "trails" to true)
        val shared = upgrade(legacy(numbers = numbers, switches = switches)).preset
        assertEquals(2f, shared.shared[SharedProperty.ZOOM])
        assertEquals(1f, shared.shared[SharedProperty.SPEED])
        assertEquals(1.25f, shared.shared[SharedProperty.BRIGHTNESS])
        assertEquals(1f, shared.shared[SharedProperty.MIRROR])
        assertEquals(0f, shared.shared[SharedProperty.KALEIDOSCOPE])
        assertEquals(0.7f, shared.shared.getValue(SharedProperty.TRAILS), delta)
        assertEquals(1f, shared.shared[SharedProperty.PARTICLE_KIND])
        assertEquals(3f, shared.shared[SharedProperty.PALETTE])
        assertEquals(SignalTrim(2f), shared.signals[SoundSignal.BASS])
        assertEquals(SignalTrim(4f), shared.signals[SoundSignal.MID])
        assertEquals(SignalTrim.NEUTRAL, shared.signals[SoundSignal.TREBLE])
    }

    @Test
    fun `trails that were off stay at 0, and an unknown particle shape becomes motes`() {
        val off = upgrade(legacy(numbers = mapOf("trailLength" to 0.7, "particleShape" to 42.0), switches = mapOf("trails" to false)))
        assertEquals(0f, off.preset.shared[SharedProperty.TRAILS])
        assertEquals(0f, off.preset.shared[SharedProperty.PARTICLE_KIND])
        assertTrue(off.dropped.isEmpty())
        val defaultLength = upgrade(legacy(switches = mapOf("trails" to true)))
        assertEquals(0.5f, defaultLength.preset.shared[SharedProperty.TRAILS])
    }

    @Test
    fun `a value left at the old default keeps the Style's own start`() {
        val nebula = upgrade(legacy(NEBULA, numbers = mapOf("vignette" to 0.0, "zoom" to 1.0)))
        assertEquals(0.3f, nebula.preset.shared[SharedProperty.VIGNETTE])
        assertEquals(0.5f, upgrade(legacy(NEBULA, numbers = mapOf("vignette" to 0.5))).preset.shared[SharedProperty.VIGNETTE])
    }

    @Test
    fun `removed and unknown fields are dropped and named`() {
        val result =
            upgrade(
                legacy(
                    numbers =
                        mapOf("flash" to 0.3, "strobe" to 0.1, "formDrive" to 0.7, "zoom" to 1.0, "pulse" to 0.0, "trailLength" to 0.5),
                    switches = mapOf("fluidBassPump" to true, "trails" to false, "mirror" to false),
                ),
            )
        assertEquals(listOf("flash", "fluidBassPump", "formDrive", "strobe"), result.dropped)
    }

    @Test
    fun `a preset on a removed Style moves to the default Style`() {
        val moved = upgrade(legacy(GONE, numbers = mapOf("zoom" to 2.0)))
        assertTrue(moved.restyled)
        assertEquals(JULIA, moved.preset.style)
        assertEquals(julia.extras, moved.preset.extras)
        assertEquals(2f, moved.preset.shared[SharedProperty.ZOOM])
        val bare = upgrade(legacy(GONE), fallback = GONE)
        assertEquals(GONE, bare.preset.style)
        assertTrue(bare.preset.extras.isEmpty())
    }

    @Test
    fun `old Fluid, Water and Cymatics controls become Extras where the Style has them`() {
        val water = upgrade(legacy(WATER, numbers = mapOf("waterDamping" to 0.97, "waterDepth" to 0.6, "fluidCurl" to 45.0))).preset
        assertEquals((0.97f - 0.95f) / 0.049f, water.extras.getValue(ExtraKind.VISCOSITY), delta)
        assertEquals(0.6f, water.extras.getValue(ExtraKind.FOG), delta)
        val onJulia = upgrade(legacy(numbers = mapOf("fluidCurl" to 45.0)))
        assertEquals(julia.extras, onJulia.preset.extras)
        assertTrue(onJulia.dropped.isEmpty())
    }

    @Test
    fun `old LFO slots become Modulators and follower slots become Reactions`() {
        val lfos =
            listOf(
                lfo("LFO", "ZOOM"),
                lfo("BASS", "FLUID_CURL"),
                lfo("LEVEL", "LFO1_RATE", polarity = "BIPOLAR"),
                lfo("LFO", "ZOOM", enabled = false),
                lfo("NOPE", "ZOOM"),
                lfo("LFO", "NOPE"),
                lfo("TRANSIENT", "LFO2_DEPTH"),
                lfo("LFO", "SPEED", wave = "WOBBLE"),
            )
        val preset = upgrade(legacy(), lfos).preset
        assertEquals(
            listOf(
                Modulator(ModulatorId(1), ModulatorShape.Lfo(Wave.TRIANGLE, Millis(4_000))),
                Modulator(ModulatorId(8), ModulatorShape.Lfo(Wave.SINE, Millis(4_000))),
            ),
            preset.modulators,
        )
        val added = preset.reactions.drop(julia.reactions.size)
        assertEquals(julia.reactions, preset.reactions.take(julia.reactions.size))
        assertEquals(
            listOf(
                Triple(ReactionSource.Generated(ModulatorId(1)), Target.Shared(SharedProperty.ZOOM), true),
                Triple(ReactionSource.Sound(SoundSignal.LEVEL), Target.ModulatorField(ModulatorId(1), ModulatorParameter.RATE), false),
                Triple(ReactionSource.Generated(ModulatorId(8)), Target.Shared(SharedProperty.SPEED), true),
            ),
            added.map { Triple(it.source, it.target, it.invert) },
        )
        assertTrue(added.all { it.amount == 0.5f })
        assertEquals(julia.reactions.size + 1, added.first().id.value)
    }

    @Test
    fun `old envelopes and the transient pulse become Reactions`() {
        val envelopes =
            listOf(
                LegacyEnvelope(
                    true,
                    listOf("ZOOM", "BRIGHTNESS", "NOPE"),
                    attackSeconds = 0.05,
                    releaseSeconds = 0.35,
                    amount = 0.5,
                    band = "RMS",
                ),
                LegacyEnvelope(false, listOf("ZOOM"), 0.05, 0.35, 0.5, "BASS"),
                LegacyEnvelope(true, listOf("ZOOM"), 0.05, 0.35, 0.5, "NOPE"),
                LegacyEnvelope(true, listOf("SWAY"), -1.0, 0.1, 0.25, "WIDTH"),
            )
        val added = upgrade(legacy(numbers = mapOf("pulse" to 0.4)), envelopes = envelopes).preset.reactions.drop(julia.reactions.size)
        val level = ReactionSource.Sound(SoundSignal.LEVEL)
        assertEquals(
            listOf(
                Reaction(added[0].id, level, Target.Shared(SharedProperty.ZOOM), 0.5f, rise = Millis(50), fall = Millis(350)),
                Reaction(added[1].id, level, Target.Shared(SharedProperty.BRIGHTNESS), 0.5f, rise = NO_FLASH_MIN_RISE, fall = Millis(350)),
                Reaction(
                    added[2].id,
                    ReactionSource.Sound(SoundSignal.STEREO_WIDTH),
                    Target.Shared(SharedProperty.SWAY),
                    0.25f,
                    rise = Millis.ZERO,
                    fall = Millis(100),
                ),
                Reaction(added[3].id, ReactionSource.Sound(SoundSignal.HIT), Target.Shared(SharedProperty.ZOOM), 0.4f, fall = Millis(250)),
            ),
            added,
        )
        assertEquals(listOf(4, 5, 6, 7), added.map { it.id.value })
        assertEquals(julia.reactions, upgrade(legacy(numbers = mapOf("pulse" to 0.0))).preset.reactions)
    }
}
