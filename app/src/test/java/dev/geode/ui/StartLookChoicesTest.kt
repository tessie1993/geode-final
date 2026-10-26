package dev.geode.ui

import dev.geode.di.startCommand
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.settings.api.StartLook
import dev.geode.feature.settings.ui.StartLookChoice
import dev.geode.feature.visuals.api.PresetShelf
import dev.geode.feature.visuals.api.SavedPreset
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The look Geode starts on (#383): what Settings offers, and what Customization is sent at launch. */
class StartLookChoicesTest {
    private val aurora = SceneId("aurora")
    private val cymatics = SceneId("cymatics")
    private val gone = SceneId("gone")

    @Test
    fun `the Style on screen comes first, then the Favourites, then the saved presets`() {
        val visuals =
            VisualsState(
                styles = listOf(StyleEntry(aurora, "Aurora", StyleGroup.SHADERS), StyleEntry(cymatics, "Cymatics", StyleGroup.CYMATICS)),
                current = cymatics,
                library = StyleLibrary(favourites = listOf(aurora, cymatics, gone)),
                presets = PresetShelf(saved = listOf(SavedPreset(PresetId("Party"), aurora))),
            )

        assertEquals(
            listOf(
                StartLookChoice(StartLook.Style(cymatics), "Cymatics"),
                StartLookChoice(StartLook.Style(aurora), "Aurora"),
                StartLookChoice(StartLook.Preset(PresetId("Party")), "Party"),
            ),
            startLookChoices(visuals),
        )
    }

    @Test
    fun `nothing is offered before Visuals has listed anything`() {
        assertEquals(emptyList<StartLookChoice>(), startLookChoices(VisualsState()))
    }

    @Test
    fun `a chosen Style or preset is sent once at launch, and the last look sends nothing`() {
        assertEquals(null, startCommand(StartLook.Last))
        assertEquals(CustomizationCommand.ChooseStyle(aurora), startCommand(StartLook.Style(aurora)))
        assertEquals(CustomizationCommand.LoadPreset(PresetId("Party")), startCommand(StartLook.Preset(PresetId("Party"))))
    }
}
