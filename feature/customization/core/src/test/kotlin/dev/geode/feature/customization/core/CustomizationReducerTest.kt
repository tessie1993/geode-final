package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
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
    private val handState = CustomizationState(withHand, undo = listOf(julia))
    private val handId = withHand.modulators.single().id
    private val playing = Edits.playModulator(withHand, handId, true)
    private val palettes = Automations(palette = PaletteChanges())
    private val switching = StyleSwitching(pool = StylePool.MINE, mine = listOf(JULIA, TUNNEL))
    private val autoState = CustomizationState(Edits.setAutomations(julia, Automations(style = switching)), undo = listOf(julia))
    private val autoSwitched = Edits.switchStyle(autoState.current, styles.manifestOf(JULIA), styles.manifestOf(TUNNEL))
    private val stuckState = CustomizationState(Edits.setAutomations(julia, Automations(style = switching.copy(mine = listOf(JULIA)))))

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
            edits("trim signal",CustomizationCommand.TrimSignal(SoundSignal.BASS, SignalTrim(gain = 2f))) {
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
                Row("reset when already at the Style", fresh, command(CustomizationCommand.ResetToStyle), Next(fresh)),
                Row(
                    "undo",
                    withUndo,
                    command(CustomizationCommand.Undo),
                    Next(CustomizationState(julia, redo = listOf(zoomed)), listOf(publish(julia))),
                ),
                Row("undo with nothing to undo", fresh, command(CustomizationCommand.Undo), Next(fresh)),
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
                Row("apply a partial preset", fresh, command(CustomizationCommand.ApplyPreset(zoomPreset)), edited(fresh, zoomed)),
                Row("a loaded partial preset", fresh, CustomizationEvent.PresetLoaded(zoomPreset), edited(fresh, zoomed)),
                Row(
                    "a loaded preset from another Style brings only the shared parts",
                    fresh,
                    CustomizationEvent.PresetLoaded(PresetRules.capture("Tunnel", tunnel, PresetRules.ALL_PARTS - PresetPart.STYLE)),
                    Next(fresh),
                ),
                Row(
                    "a Style switch that is due is live, not history, and keeps the Automations",
                    autoState,
                    CustomizationEvent.StyleDue,
                    Next(
                        autoState.copy(current = autoSwitched),
                        listOf(publish(autoSwitched), Emit(CustomizationFact.StyleChanged(TUNNEL))),
                    ),
                ),
                Row("a Style switch due with switching off", fresh, CustomizationEvent.StyleDue, Next(fresh)),
                Row("a Style switch due with nowhere to go", stuckState, CustomizationEvent.StyleDue, Next(stuckState)),
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
    fun `every failure has its own message`() {
        val messages = WorkFailure.entries.map { it.message }
        assertEquals(WorkFailure.entries.size, messages.toSet().size)
        assertEquals("customization", CUSTOMIZATION_FEATURE)
    }
}
