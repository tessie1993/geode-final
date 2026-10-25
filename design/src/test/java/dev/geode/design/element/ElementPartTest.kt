package dev.geode.design.element

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.geode.design.forest.ForestMotion
import dev.geode.design.forest.WcagContrast
import dev.geode.design.theme.StoneComponent
import dev.geode.design.theme.StoneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementPartTest {
    private val water = ElementSkins.Water.colors
    private val labelled =
        listOf(StoneComponent.PRIMARY_BUTTON, StoneComponent.SECONDARY_BUTTON, StoneComponent.COMPACT_BUTTON, StoneComponent.CHIP)
    private val lit = listOf(StoneState.DEFAULT, StoneState.FOCUSED, StoneState.PRESSED, StoneState.SELECTED)

    @Test
    fun `labels keep AA body contrast on every lit pebble in every Element, through Water's glass too`() {
        for (element in ElementSkins.all) {
            val colors = element.colors
            for (component in labelled) {
                for (state in lit) {
                    val text = partContent(colors, component, state)
                    val fill = partFill(colors, component, state)
                    for (core in listOf(0f, partCore(component, state))) {
                        val face = partFace(colors, element.finish, component, fill, core)
                        assertAa("${element.key} $component $state core $core", text, face, WcagContrast.AA_TEXT)
                    }
                }
            }
        }
    }

    @Test
    fun `light glyphs keep icon contrast on the dark icon pebble at its brightest`() {
        for (element in ElementSkins.all) {
            val colors = element.colors
            for (state in lit) {
                val component = StoneComponent.ICON_BUTTON
                val face = partFace(colors, element.finish, component, partFill(colors, component, state), partCore(component, state))
                assertEquals(colors.textPrimary, partContent(colors, component, state))
                assertAa("${element.key} $state", colors.textPrimary, face, WcagContrast.AA_LARGE_TEXT)
            }
        }
    }

    @Test
    fun `labels are dark on a lit pebble and light on a dark pebble or a slab`() {
        assertEquals(water.onAccent, partContent(water, StoneComponent.PRIMARY_BUTTON, StoneState.DEFAULT))
        assertEquals(water.onActive, partContent(water, StoneComponent.SECONDARY_BUTTON, StoneState.PRESSED))
        assertEquals(water.onActive, partContent(water, StoneComponent.CHIP, StoneState.SELECTED))
        assertEquals(water.onAccent, partContent(water, StoneComponent.PRIMARY_BUTTON, StoneState.DISABLED))
        assertEquals(water.textPrimary, partContent(water, StoneComponent.LIST_ROW, StoneState.PRESSED))
        assertEquals(water.textPrimary, partContent(water, StoneComponent.KNOB, StoneState.SELECTED))
    }

    @Test
    fun `the inner light is off at rest, brighter when pressed or focused, fullest when selected`() {
        assertEquals(0f, partCore(StoneComponent.CHIP, StoneState.DEFAULT))
        assertEquals(0.5f, partCore(StoneComponent.CHIP, StoneState.PRESSED))
        assertEquals(0.5f, partCore(StoneComponent.CHIP, StoneState.FOCUSED))
        assertEquals(0.7f, partCore(StoneComponent.CHIP, StoneState.SELECTED))
        assertEquals(0f, partCore(StoneComponent.PRIMARY_BUTTON, StoneState.DISABLED))
        assertEquals(0.35f, partCore(StoneComponent.ICON_BUTTON, StoneState.SELECTED), 0.0001f)
        assertEquals(0.25f, partCore(StoneComponent.KNOB, StoneState.PRESSED), 0.0001f)
    }

    @Test
    fun `the accent pebble glows warm and every other part in the Element's glow`() {
        val warm = partLight(water, StoneComponent.PRIMARY_BUTTON)
        assertTrue(WcagContrast.relativeLuminance(warm) > WcagContrast.relativeLuminance(water.accent))
        assertTrue(warm.red > warm.blue)
        assertEquals(water.glow, partLight(water, StoneComponent.CHIP))
        assertEquals(water.glow, partLight(water, StoneComponent.ICON_BUTTON))
    }

    @Test
    fun `a disabled part is dry and dim`() {
        val dry = partFill(water, StoneComponent.PRIMARY_BUTTON, StoneState.DISABLED)
        assertEquals(WcagContrast.over(water.accent, 0.4f, water.background), dry)
        assertTrue(WcagContrast.relativeLuminance(dry) < WcagContrast.relativeLuminance(water.accent))
    }

    @Test
    fun `each part takes its fill from the Element's roles`() {
        assertEquals(water.accent, partFill(water, StoneComponent.PRIMARY_BUTTON, StoneState.PRESSED))
        assertEquals(water.secondary, partFill(water, StoneComponent.SECONDARY_BUTTON, StoneState.DEFAULT))
        assertEquals(water.active, partFill(water, StoneComponent.ALBUM_TILE, StoneState.DEFAULT))
        assertEquals(water.secondary, partFill(water, StoneComponent.CHIP, StoneState.DEFAULT))
        assertEquals(water.active, partFill(water, StoneComponent.CHIP, StoneState.SELECTED))
        assertEquals(water.surfaceHigh, partFill(water, StoneComponent.ICON_BUTTON, StoneState.DEFAULT))
        assertEquals(water.surface, partFill(water, StoneComponent.LIST_ROW, StoneState.DEFAULT))
        assertEquals(water.surfaceHigh, partFill(water, StoneComponent.LIST_ROW, StoneState.PRESSED))
        assertEquals(water.backgroundDeep, partFill(water, StoneComponent.TEXT_FIELD, StoneState.FOCUSED))
        assertEquals(water.surface, partFill(water, StoneComponent.SLIDER_TRACK, StoneState.DEFAULT))
        assertEquals(water.divider, partFill(water, StoneComponent.TOGGLE, StoneState.DEFAULT))
        assertEquals(water.active, partFill(water, StoneComponent.TOGGLE, StoneState.SELECTED))
        assertEquals(water.divider, partFill(water, StoneComponent.PROGRESS_RING, StoneState.DEFAULT))
    }

    @Test
    fun `every part has a body and an outline`() {
        assertEquals(PartBody.PEBBLE, partBody(StoneComponent.PRIMARY_BUTTON))
        assertEquals(PartBody.PEBBLE, partBody(StoneComponent.SLIDER_THUMB))
        assertEquals(PartBody.SLAB, partBody(StoneComponent.LIST_ROW))
        assertEquals(PartBody.SLAB, partBody(StoneComponent.NAVIGATION_BAR))
        assertEquals(PartBody.RECESS, partBody(StoneComponent.TEXT_FIELD))
        assertEquals(PartBody.RECESS, partBody(StoneComponent.TOGGLE))
        val shapes = ElementSkins.Water.shapes
        assertSame(shapes.button, partShape(shapes, StoneComponent.PRIMARY_BUTTON))
        assertSame(shapes.chip, partShape(shapes, StoneComponent.CHIP))
        assertSame(shapes.card, partShape(shapes, StoneComponent.LIST_ROW))
        assertSame(shapes.sheet, partShape(shapes, StoneComponent.BOTTOM_SHEET))
        assertSame(CircleShape, partShape(shapes, StoneComponent.ICON_BUTTON))
        assertSame(RectangleShape, partShape(shapes, StoneComponent.NAVIGATION_BAR))
        for (component in StoneComponent.entries) {
            partBody(component)
            partShape(shapes, component)
            partFill(water, component, StoneState.DEFAULT)
        }
    }

    @Test
    fun `a part eases over the Element's own timings, and cross-fades with motion reduced`() {
        val motion = ElementSkins.Water.motion
        assertEquals(90, partMillis(motion, StoneState.PRESSED, reducedMotion = false))
        assertEquals(160, partMillis(motion, StoneState.FOCUSED, reducedMotion = false))
        assertEquals(400, partMillis(motion, StoneState.SELECTED, reducedMotion = false))
        assertEquals(220, partMillis(motion, StoneState.DEFAULT, reducedMotion = false))
        assertEquals(220, partMillis(motion, StoneState.DISABLED, reducedMotion = false))
        for (state in StoneState.entries) {
            assertEquals(ForestMotion.REDUCED_PULSE_MILLIS, partMillis(motion, state, reducedMotion = true))
        }
    }

    @Test
    fun `a pressed pebble sinks, the accent one deepest, and nothing sinks with motion reduced`() {
        assertEquals(2.dp, partSink(StoneComponent.PRIMARY_BUTTON, StoneState.PRESSED, reducedMotion = false))
        assertEquals(1.dp, partSink(StoneComponent.CHIP, StoneState.PRESSED, reducedMotion = false))
        assertEquals(0.dp, partSink(StoneComponent.CHIP, StoneState.SELECTED, reducedMotion = false))
        assertEquals(0.dp, partSink(StoneComponent.LIST_ROW, StoneState.PRESSED, reducedMotion = false))
        assertEquals(0.dp, partSink(StoneComponent.PRIMARY_BUTTON, StoneState.PRESSED, reducedMotion = true))
    }

    @Test
    fun `only Water is painted as sea glass, and its glass is darker at the centre than its fill`() {
        assertEquals(PartFinish.SEA_GLASS, ElementSkins.Water.finish)
        for (element in ElementSkins.all - ElementSkins.Water) {
            assertEquals(element.key, PartFinish.FLAT, element.finish)
        }
        val fill = partFill(water, StoneComponent.COMPACT_BUTTON, StoneState.DEFAULT)
        assertEquals(fill, partFace(water, PartFinish.FLAT, StoneComponent.COMPACT_BUTTON, fill, 0f))
        val glass = partFace(water, PartFinish.SEA_GLASS, StoneComponent.COMPACT_BUTTON, fill, 0f)
        assertTrue(WcagContrast.relativeLuminance(glass) < WcagContrast.relativeLuminance(fill))
        val litGlass = partFace(water, PartFinish.SEA_GLASS, StoneComponent.COMPACT_BUTTON, fill, 0.7f)
        assertTrue(WcagContrast.relativeLuminance(litGlass) > WcagContrast.relativeLuminance(glass))
    }

    private fun assertAa(
        what: String,
        fg: Color,
        bg: Color,
        min: Float,
    ) {
        val ratio = WcagContrast.ratio(fg, bg)
        assertTrue("$what: contrast $ratio of $fg on $bg is under $min", ratio >= min)
    }
}
