package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.CompareSlot
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFact
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.PaletteChanges
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StartMode
import dev.geode.feature.customization.api.StylePool
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave
import dev.geode.feature.customization.core.Fixtures.JULIA
import dev.geode.feature.customization.core.Fixtures.TUNNEL
import dev.geode.feature.customization.core.Fixtures.julia
import dev.geode.feature.customization.core.Fixtures.styles
import dev.geode.feature.customization.core.Fixtures.tunnel
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Customization's reducer as a transition table (ADR-0003): every intent, undo and redo, and partial
 * preset load, each from a state with no history, with undo history and with redo history.
 */
class CustomizationReducerTest {
    private val reducer = CustomizationReducer(styles)

    private val zoomed: Customization = Edits.setShared(julia, SharedProperty.ZOOM, 2f)
    private val lfo = Modulator(ModulatorId(0), ModulatorShape.Lfo(Wave.SINE, Millis(2_000)))
    private val byHand = lfo.copy(start = StartMode.ByHand)
    private val withLfo: Customization = Edits.addModulator(julia, lfo)
    private val withHand: Customization = Edits.addModulator(julia, byHand)
    private val bassToZoom =
        Reaction(ReactionId(0), ReactionSource.Sound(SoundSignal.BASS), Target.Shared(SharedProperty.ZOOM), amount = 0.5f)

    private val fresh = CustomizationState(julia)
    private val withUndo = CustomizationState(zoomed, undo = listOf(julia))
    private val withRedo = CustomizationState(julia, redo = listOf(zoomed))

    private fun command(command: CustomizationCommand) = CustomizationEvent.Command(command)

    private fun publish(customization: Customization) = Perform(CustomizationWork.Publish(customization))

    /** One undo step from [state] to [next]: the old Customization joins the undo history, redo is cleared. */
    private fun edited(
        state: CustomizationState,
        next: Customization,
    ): Next<CustomizationState, CustomizationEffect> = Next(CustomizationState(next, state.undo + state.current), listOf(publish(next)))

    private data class Row(
        val name: String,
        val state: CustomizationState,
        val event: CustomizationEvent,
        val expected: Next<CustomizationState, CustomizationEffect>,
    )

    private fun edits(
        name: String,
        command: CustomizationCommand,
        next: (Customization) -> Customization,
    ): List<Row> =
        listOf(fresh, withUndo, withRedo).map { state ->
            Row("$name from ${state.undo.size}/${state.redo.size}", state, command(command), edited(state, next(state.current)))
        }

    private val switchedToTunnel = Edits.switchStyle(julia, styles.manifestOf(JULIA), styles.manifestOf(TUNNEL))
    private val onTunnel = CustomizationState(switchedToTunnel, undo = listOf(julia))
    private val tunnelRedo = CustomizationState(julia, redo = listOf(switchedToTunnel))
    private val zoomPreset = PresetRules.capture("Zoom", zoomed, setOf(PresetPart.CAMERA))
    private val newerZoomPreset = zoomPreset.copy(unread = "{\"comets\":{}}")
    private val handState = CustomizationState(withHand, undo = listOf(julia))
    private val handId = withHand.modulators.single().id
    private val playing = Edits.playModulator(withHand, handId, true)
    private val palettes = Automations(palette = PaletteChanges())
    private val switching = StyleSwitching(pool = StylePool.VISUALIZER)
    private val autoState =
        CustomizationState(
            Edits.setAutomations(julia, Automations(style = switching, palette = PaletteChanges())),
            undo = listOf(julia),
            visualizerStyles = listOf(JULIA, TUNNEL),
        )
    private val autoSwitched = Edits.switchStyle(autoState.current, styles.manifestOf(JULIA), styles.manifestOf(TUNNEL))
    private val stuckState = autoState.copy(visualizerStyles = listOf(JULIA))
    private val picksState =
        autoState.copy(
            current = Edits.setAutomations(julia, Automations(style = StyleSwitching(pool = StylePool.PICKS, seed = 5L))),
            styleSwitches = 3,
        )
    private val picked =
        checkNotNull(
            Automation.nextStyle(StyleSwitching(pool = StylePool.PICKS, seed = 5L), JULIA, emptyList(), styles.styles.map { it.id }, 3),
        )
    private val pickedSwitch = Edits.switchStyle(picksState.current, styles.manifestOf(JULIA), styles.manifestOf(picked.style))
    private val reset = Edits.setAutomations(zoomed, palettes)
    private val keptA = CustomizationState(julia, compare = mapOf(CompareSlot.A to zoomed))

    private val rows: List<Row> =
        edits("set shared", CustomizationCommand.SetShared(SharedProperty.SPEED, 2f)) { Edits.setShared(it, SharedProperty.SPEED, 2f) } +
            edits("set extra", CustomizationCommand.SetExtra(ExtraKind.CENTER_X, 0.9f)) { Edits.setExtra(it, ExtraKind.CENTER_X, 0.9f) } +
            edits("set macro", CustomizationCommand.SetMacro(0, 1f)) { Edits.setMacro(it, 0, 1f) } +
            edits("rename macro", CustomizationCommand.RenameMacro(0, "Punch")) { Edits.renameMacro(it, 0, "Punch") } +
            edits("clear macro", CustomizationCommand.ClearMacro(0)) { Edits.clearMacro(it, 0) } +
            edits("link macro", CustomizationCommand.LinkMacro(1, MacroLink(Target.Shared(SharedProperty.TWIST), 0f, 1f))) {
                Edits.linkMacro(it, 1, MacroLink(Target.Shared(SharedProperty.TWIST), 0f, 1f))
            } +
            edits("unlink macro", CustomizationCommand.UnlinkMacro(0, Target.Shared(SharedProperty.SPEED))) {
                Edits.unlinkMacro(it, 0, Target.Shared(SharedProperty.SPEED))
            } +
            edits("add reaction", CustomizationCommand.AddReaction(bassToZoom)) { Edits.addReaction(it, bassToZoom) } +
            edits("update reaction", CustomizationCommand.UpdateReaction(bassToZoom.copy(id = ReactionId(1)))) {
                Edits.updateReaction(it, bassToZoom.copy(id = ReactionId(1)))
            } +
            edits("remove reaction", CustomizationCommand.RemoveReaction(ReactionId(1))) { Edits.removeReaction(it, ReactionId(1)) } +
            edits("add modulator", CustomizationCommand.AddModulator(lfo)) { Edits.addModulator(it, lfo) } +
            edits("set automations", CustomizationCommand.SetAutomations(palettes)) { Edits.setAutomations(it, palettes) } +
            edits("trim signal", CustomizationCommand.TrimSignal(SoundSignal.BASS, SignalTrim(gain = 2f))) {
                Edits.trimSignal(it, SoundSignal.BASS, SignalTrim(gain = 2f))
            } +
            listOf(
                Row("an edit that changes nothing", fresh, command(CustomizationCommand.SetShared(SharedProperty.ZOOM, 1f)), Next(fresh)),
                Row("an Extra the Style lacks", fresh, command(CustomizationCommand.SetExtra(ExtraKind.FLIGHT, 1f)), Next(fresh)),
                Row(
                    "update modulator",
                    CustomizationState(withLfo),
                    command(CustomizationCommand.UpdateModulator(withLfo.modulators.single().copy(depth = 0.5f))),
                    edited(CustomizationState(withLfo), Edits.updateModulator(withLfo, withLfo.modulators.single().copy(depth = 0.5f))),
                ),
                Row(
                    "remove modulator",
                    CustomizationState(withLfo),
                    command(CustomizationCommand.RemoveModulator(withLfo.modulators.single().id)),
                    edited(CustomizationState(withLfo), julia),
                ),
                Row(
                    "playing by hand is live, not history",
                    handState,
                    command(CustomizationCommand.PlayModulator(handId, true)),
                    Next(handState.copy(current = playing), listOf(publish(playing))),
                ),
                Row(
                    "playing an unknown Modulator",
                    handState,
                    command(CustomizationCommand.PlayModulator(ModulatorId(9), true)),
                    Next(handState),
                ),
                Row("reset to Style", withUndo, command(CustomizationCommand.ResetToStyle), edited(withUndo, julia)),
                Row(
                    "reset to Style keeps the Automations",
                    CustomizationState(reset),
                    command(CustomizationCommand.ResetToStyle),
                    edited(CustomizationState(reset), Edits.setAutomations(julia, palettes)),
                ),
                Row("reset when already at the Style", fresh, command(CustomizationCommand.ResetToStyle), Next(fresh)),
                Row(
                    "undo",
                    withUndo,
                    command(CustomizationCommand.Undo),
                    Next(CustomizationState(julia, redo = listOf(zoomed)), listOf(publish(julia))),
                ),
                Row("undo with nothing to undo", fresh, command(CustomizationCommand.Undo), Next(fresh)),
                Row(
                    "undo and redo leave the Visualizer styles and the switch count alone",
                    autoState.copy(styleSwitches = 4),
                    command(CustomizationCommand.Undo),
                    Next(
                        autoState.copy(current = julia, undo = emptyList(), redo = listOf(autoState.current), styleSwitches = 4),
                        listOf(publish(julia)),
                    ),
                ),
                Row(
                    "an edit leaves the Visualizer styles and the switch count alone",
                    autoState.copy(styleSwitches = 4),
                    command(CustomizationCommand.SetShared(SharedProperty.SPEED, 2f)),
                    Next(
                        autoState.copy(
                            current = Edits.setShared(autoState.current, SharedProperty.SPEED, 2f),
                            undo = listOf(julia, autoState.current),
                            styleSwitches = 4,
                        ),
                        listOf(publish(Edits.setShared(autoState.current, SharedProperty.SPEED, 2f))),
                    ),
                ),
                Row("undo with only redo", withRedo, command(CustomizationCommand.Undo), Next(withRedo)),
                Row(
                    "redo",
                    withRedo,
                    command(CustomizationCommand.Redo),
                    Next(CustomizationState(zoomed, undo = listOf(julia)), listOf(publish(zoomed))),
                ),
                Row("redo with nothing to redo", fresh, command(CustomizationCommand.Redo), Next(fresh)),
                Row("redo with only undo", withUndo, command(CustomizationCommand.Redo), Next(withUndo)),
                Row(
                    "undo across a change of Style",
                    onTunnel,
                    command(CustomizationCommand.Undo),
                    Next(
                        CustomizationState(julia, redo = listOf(switchedToTunnel)),
                        listOf(publish(julia), Emit(CustomizationFact.StyleChanged(JULIA))),
                    ),
                ),
                Row(
                    "redo across a change of Style",
                    tunnelRedo,
                    command(CustomizationCommand.Redo),
                    Next(onTunnel, listOf(publish(switchedToTunnel), Emit(CustomizationFact.StyleChanged(TUNNEL)))),
                ),
                Row(
                    "choose another Style",
                    fresh,
                    command(CustomizationCommand.ChooseStyle(TUNNEL)),
                    Next(onTunnel, listOf(publish(switchedToTunnel), Emit(CustomizationFact.StyleChanged(TUNNEL)))),
                ),
                Row("choose the same Style", fresh, command(CustomizationCommand.ChooseStyle(JULIA)), Next(fresh)),
                Row(
                    "choose an unknown Style",
                    fresh,
                    command(CustomizationCommand.ChooseStyle(SceneId("nope"))),
                    Next(fresh, listOf(ReportFailure("no Style named nope"))),
                ),
                Row(
                    "save a preset with the ticked parts",
                    withUndo,
                    command(CustomizationCommand.SavePreset("Mine", setOf(PresetPart.CAMERA))),
                    Next(
                        withUndo,
                        listOf(Perform(CustomizationWork.SavePreset(PresetRules.capture("Mine", zoomed, setOf(PresetPart.CAMERA))))),
                    ),
                ),
                Row(
                    "save a preset with no name",
                    fresh,
                    command(CustomizationCommand.SavePreset("  ", setOf(PresetPart.CAMERA))),
                    Next(fresh),
                ),
                Row(
                    "load a preset",
                    fresh,
                    command(CustomizationCommand.LoadPreset(PresetId("Mine"))),
                    Next(fresh, listOf(Perform(CustomizationWork.LoadPreset(PresetId("Mine"))))),
                ),
                Row(
                    "keep for A/B compare is not history",
                    withUndo,
                    command(CustomizationCommand.KeepForCompare(CompareSlot.A)),
                    Next(withUndo.copy(compare = mapOf(CompareSlot.A to zoomed))),
                ),
                Row(
                    "keep for A/B compare replaces what the slot kept",
                    keptA,
                    command(CustomizationCommand.KeepForCompare(CompareSlot.A)),
                    Next(keptA.copy(compare = mapOf(CompareSlot.A to julia))),
                ),
                Row(
                    "compare with a kept slot is an edit, the Automations kept",
                    keptA.copy(current = Edits.setAutomations(julia, palettes)),
                    command(CustomizationCommand.Compare(CompareSlot.A)),
                    Next(
                        CustomizationState(
                            Edits.setAutomations(zoomed, palettes),
                            undo = listOf(Edits.setAutomations(julia, palettes)),
                            compare = keptA.compare,
                        ),
                        listOf(publish(Edits.setAutomations(zoomed, palettes))),
                    ),
                ),
                Row("compare with an empty slot", keptA, command(CustomizationCommand.Compare(CompareSlot.B)), Next(keptA)),
                Row(
                    "compare with what is on screen already",
                    CustomizationState(zoomed, compare = keptA.compare),
                    command(CustomizationCommand.Compare(CompareSlot.A)),
                    Next(CustomizationState(zoomed, compare = keptA.compare)),
                ),
                Row("apply a partial preset", fresh, command(CustomizationCommand.ApplyPreset(zoomPreset)), edited(fresh, zoomed)),
                Row("a loaded partial preset", fresh, CustomizationEvent.PresetLoaded(zoomPreset), edited(fresh, zoomed)),
                Row(
                    "a loaded preset with parts only a newer Geode reads applies the rest and says so",
                    fresh,
                    CustomizationEvent.PresetLoaded(newerZoomPreset),
                    edited(fresh, zoomed).let { it.copy(effects = it.effects + ReportFailure(NEEDS_NEWER_GEODE)) },
                ),
                Row(
                    "an applied preset with parts only a newer Geode reads says so even when nothing else changes",
                    withUndo,
                    command(CustomizationCommand.ApplyPreset(newerZoomPreset)),
                    Next(withUndo, listOf(ReportFailure(NEEDS_NEWER_GEODE))),
                ),
                Row(
                    "a loaded preset from another Style brings only the shared parts",
                    fresh,
                    CustomizationEvent.PresetLoaded(PresetRules.capture("Tunnel", tunnel, PresetRules.ALL_PARTS - PresetPart.STYLE)),
                    Next(fresh),
                ),
                Row(
                    "a Style switch that is due is live, not history, keeps the Automations and counts the switch",
                    autoState,
                    CustomizationEvent.StyleDue,
                    Next(
                        autoState.copy(current = autoSwitched, styleSwitches = 2),
                        listOf(publish(autoSwitched), Emit(CustomizationFact.StyleChanged(TUNNEL))),
                    ),
                ),
                Row(
                    "a Style switch due on the app's picks takes the next of the shuffle",
                    picksState,
                    CustomizationEvent.StyleDue,
                    Next(
                        picksState.copy(current = pickedSwitch, styleSwitches = picked.played),
                        listOf(publish(pickedSwitch), Emit(CustomizationFact.StyleChanged(picked.style))),
                    ),
                ),
                Row("a Style switch due with switching off", fresh, CustomizationEvent.StyleDue, Next(fresh)),
                Row("a Style switch due with nowhere to go", stuckState, CustomizationEvent.StyleDue, Next(stuckState)),
                Row(
                    "the Visualizer styles are kept beside the history and reach nothing else",
                    withUndo,
                    command(CustomizationCommand.UseVisualizerStyles(listOf(TUNNEL, JULIA))),
                    Next(withUndo.copy(visualizerStyles = listOf(TUNNEL, JULIA))),
                ),
                Row(
                    "a failed piece of work is reported",
                    withUndo,
                    CustomizationEvent.WorkFailed(WorkFailure.PRESET_NOT_SAVED),
                    Next(withUndo, listOf(ReportFailure("could not save the preset"))),
                ),
            )

    @Test
    fun `every row of the transition table`() {
        rows.forEach { row -> assertEquals(row.name, row.expected, reducer.reduce(row.state, row.event)) }
    }

    @Test
    fun `every edit row really changes something`() {
        rows.filter { it.expected.state.undo.size > it.state.undo.size }.forEach { row ->
            assertNotEquals(row.name, row.state.current, row.expected.state.current)
        }
    }

    @Test
    fun `undo is unlimited`() {
        val steps = (1..50).map { CustomizationCommand.SetShared(SharedProperty.SPEED, 1f + it / 100f) }
        val edited = steps.fold(fresh) { state, step -> reducer.reduce(state, command(step)).state }
        assertEquals(50, edited.undo.size)
        val undone = steps.fold(edited) { state, _ -> reducer.reduce(state, command(CustomizationCommand.Undo)).state }
        assertEquals(julia, undone.current)
        assertEquals(50, undone.redo.size)
    }

    @Test
    fun `a new store converts old presets and then hands its starting Customization to the engine`() {
        assertEquals(
            listOf(Perform(CustomizationWork.UpgradePresets), publish(zoomed)),
            CustomizationReducer.start(CustomizationState(zoomed, undo = listOf(julia))),
        )
    }

    @Test
    fun `every failed piece of work is reported under its own message`() {
        val expected =
            mapOf(
                WorkFailure.PRESET_NOT_LOADED to "could not load the preset",
                WorkFailure.PRESET_NOT_SAVED to "could not save the preset",
                WorkFailure.ENGINE_REFUSED to "the engine did not take the Customization",
                WorkFailure.PRESET_NOT_MIRRORED to "saved the preset, but could not copy it to the mirror folder",
                WorkFailure.PRESETS_NOT_UPGRADED to "could not upgrade the presets saved by an older Geode",
            )
        assertEquals(WorkFailure.entries.toSet(), expected.keys)
        expected.forEach { (failure, message) ->
            assertEquals(Next(fresh, listOf(ReportFailure(message))), reducer.reduce(fresh, CustomizationEvent.WorkFailed(failure)))
        }
    }

    @Test
    fun `every failure has its own message`() {
        val messages = WorkFailure.entries.map { it.message }
        assertEquals(WorkFailure.entries.size, messages.toSet().size)
        assertEquals("customization", CUSTOMIZATION_FEATURE)
    }
}
