package dev.geode.studio

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.BeatSpan
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CameraMove
import dev.geode.feature.customization.api.CameraMoveKind
import dev.geode.feature.customization.api.Lane
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.PaletteChanges
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.StepLength
import dev.geode.feature.customization.api.StylePool
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Trigger
import dev.geode.feature.customization.api.Wave
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.core.Defaults
import dev.geode.feature.customization.core.Edits
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** A Render's frozen look keeps the whole Customization, Automations included (#338). */
class RenderLookFormatTest {
    private val style = BuiltInStyles.all[0].id
    private val base = Defaults.customization(BuiltInStyles.catalogue.manifestOf(style))
    private val automations =
        Automations(
            style = StyleSwitching(Cadence(Trigger.EveryBars(16), Millis(20_000)), StylePool.PICKS, seed = 42, transition = Millis(5_000)),
            palette = PaletteChanges(Cadence(Trigger.BigMoments(0.7f), Millis(12_000)), Millis(3_000)),
            camera = listOf(CameraMove(CameraMoveKind.ORBIT, BeatSpan.FOUR, 0.5f), CameraMove(CameraMoveKind.SHAKE)),
            lanes =
                listOf(
                    Lane(
                        listOf(
                            Target.Shared(SharedProperty.GLITCH),
                            Target.MacroSlot(2),
                            Target.ModulatorField(ModulatorId(1), ModulatorParameter.DEPTH),
                        ),
                        listOf(listOf(0f, 0.5f, 1f), listOf(1f, 0.25f, 0f)),
                        StepLength.BEAT,
                    ),
                ),
        )
    private val look =
        Edits
            .setShared(base, SharedProperty.ZOOM, 2.5f)
            .copy(
                modulators = listOf(Modulator(ModulatorId(1), ModulatorShape.Lfo(Wave.entries.first(), Millis(4_000), beatSync = true))),
                automations = automations,
            )

    @Test
    fun `a frozen look reads back as the whole Customization`() {
        assertEquals(look, RenderLookFormat.decode(RenderLookFormat.encode(look), SceneId("fallback")))
        assertEquals(base, RenderLookFormat.decode(RenderLookFormat.encode(base), SceneId("fallback")))
    }

    @Test
    fun `what this version does not know is left out, not the look`() {
        val o = JSONObject(RenderLookFormat.encode(look))
        val written = o.getJSONObject("automations")
        written.getJSONArray("camera").getJSONObject(0).put("kind", "BARREL_ROLL")
        written
            .getJSONArray("lanes")
            .getJSONObject(0)
            .getJSONArray("targets")
            .put(JSONObject().put("shared", "HOLOGRAM"))
        written.getJSONObject("style").remove("pool")

        val read = RenderLookFormat.decode(o.toString(), SceneId("fallback"))

        assertEquals(listOf(CameraMove(CameraMoveKind.SHAKE)), read.automations.camera)
        assertEquals(emptyList<Lane>(), read.automations.lanes)
        assertEquals(StylePool.BOTH, read.automations.style?.pool)
        assertEquals(look.copy(automations = Automations.NONE), read.copy(automations = Automations.NONE))
    }

    @Test
    fun `a look written without Automations has none`() {
        val o = JSONObject(RenderLookFormat.encode(look))
        o.remove("automations")
        assertEquals(Automations.NONE, RenderLookFormat.decode(o.toString(), SceneId("fallback")).automations)
    }
}
