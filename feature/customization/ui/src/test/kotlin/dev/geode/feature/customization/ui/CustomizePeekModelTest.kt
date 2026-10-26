package dev.geode.feature.customization.ui

import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.ValueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The peek bar of a lowered Customize panel (#412) keeps the control touched last. */
class CustomizePeekModelTest {
    private val model = PreviewModels.editing

    @Test
    fun `setting, resetting or previewing one value touches its control, nothing else does`() {
        val zoom = Target.Shared(SharedProperty.ZOOM)
        val table =
            listOf(
                CustomizationCommand.SetShared(SharedProperty.ZOOM, 1.2f) to zoom,
                CustomizationCommand.SetExtra(ExtraKind.FLIGHT, 0.4f) to Target.Extra(ExtraKind.FLIGHT),
                CustomizationCommand.SetMacro(2, 0.7f) to Target.MacroSlot(2),
                CustomizationCommand.ResetValue(zoom) to zoom,
                CustomizationCommand.Preview(CustomizationCommand.SetMacro(4, 0.1f)) to Target.MacroSlot(4),
                CustomizationCommand.Preview(CustomizationCommand.Undo) to null,
                CustomizationCommand.Undo to null,
                CustomizationCommand.Redo to null,
            )
        for ((command, touched) in table) assertEquals(command.toString(), touched, touchedTarget(command))
    }

    @Test
    fun `a Shared core property or an Extra shows as its Advanced row`() {
        val zoom = Target.Shared(SharedProperty.ZOOM)
        val zoomRow =
            model.shared.values
                .flatten()
                .first { it.target == zoom }
        assertEquals(zoomRow, peekRow(model, zoom))

        val flight = Target.Extra(ExtraKind.FLIGHT)
        assertEquals(model.extras.first { it.target == flight }, peekRow(model, flight))
    }

    @Test
    fun `a Macro shows as a 0 to 1 row with its value, drivers and greying`() {
        val knob = model.macros[0]
        val row = peekRow(model, Target.MacroSlot(0))
        assertEquals(
            SliderRow(Target.MacroSlot(0), knob.value, 0f, 1f, ValueKind.CONTINUOUS, knob.drivers.isNotEmpty(), ignored = knob.ignored),
            row,
        )
        val ignored = model.copy(macros = model.macros.map { it.copy(ignored = true, drivers = emptyList()) })
        val greyed = requireNotNull(peekRow(ignored, Target.MacroSlot(1)))
        assertEquals(true, greyed.ignored)
        assertEquals(false, greyed.linked)
    }

    @Test
    fun `a control the model no longer has, or a Modulator's field, shows nothing`() {
        assertNull(peekRow(model, Target.MacroSlot(model.macros.size)))
        assertNull(peekRow(model.copy(extras = emptyList()), Target.Extra(ExtraKind.FLIGHT)))
        assertNull(peekRow(model.copy(shared = emptyMap()), Target.Shared(SharedProperty.ZOOM)))
        assertNull(peekRow(model, Target.ModulatorField(ModulatorId(1), ModulatorParameter.entries.first())))
    }
}
