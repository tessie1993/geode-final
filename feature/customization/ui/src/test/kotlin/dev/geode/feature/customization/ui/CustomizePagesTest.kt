package dev.geode.feature.customization.ui

import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.Target
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomizePagesTest {
    @Test
    fun `each slider lives in the section of its kind`() {
        assertEquals(AdvancedSection.CAMERA, sectionOf(Target.Shared(SharedProperty.ORBIT_YAW)))
        assertEquals(AdvancedSection.EFFECTS, sectionOf(Target.Shared(SharedProperty.VIGNETTE)))
        assertEquals(AdvancedSection.EXTRAS, sectionOf(Target.Extra(ExtraKind.SWIRL)))
        assertEquals(AdvancedSection.MACROS, sectionOf(Target.MacroSlot(1)))
        assertEquals(null, sectionOf(Target.ModulatorField(ModulatorId(1), ModulatorParameter.RATE)))
        assertEquals(
            CustomizePage.Advanced(AdvancedSection.REACTIONS),
            back(CustomizePage.ChooseSource(target = Target.ModulatorField(ModulatorId(1), ModulatorParameter.RATE))),
        )
    }

    @Test
    fun `Back from the Advanced view and the save goes to the basic view`() {
        assertEquals(CustomizePage.Basic, back(CustomizePage.Basic))
        assertEquals(CustomizePage.Basic, back(CustomizePage.Advanced(AdvancedSection.CAMERA)))
        assertEquals(CustomizePage.Basic, back(CustomizePage.SavePreset))
    }

    @Test
    fun `Back from a link's steps goes to the Reactions, or to the slider it was started from`() {
        val reactions = CustomizePage.Advanced(AdvancedSection.REACTIONS)
        assertEquals(reactions, back(CustomizePage.ChooseSource()))
        assertEquals(reactions, back(CustomizePage.ChooseSource(learn = true)))
        assertEquals(
            CustomizePage.Advanced(AdvancedSection.CAMERA),
            back(CustomizePage.ChooseSource(target = Target.Shared(SharedProperty.ZOOM))),
        )
        assertEquals(reactions, back(CustomizePage.ChooseTarget(ReactionSource.Sound(SoundSignal.BASS))))
        val draft = newReaction(ReactionSource.Sound(SoundSignal.BASS), Target.Shared(SharedProperty.ZOOM))
        assertEquals(reactions, back(CustomizePage.EditReaction(draft, isNew = true)))
    }

    @Test
    fun `Back from choosing a Macro's property returns to that Macro`() {
        assertEquals(CustomizePage.Advanced(AdvancedSection.MACROS, 2), back(CustomizePage.ChooseTarget(macro = 2)))
    }
}
