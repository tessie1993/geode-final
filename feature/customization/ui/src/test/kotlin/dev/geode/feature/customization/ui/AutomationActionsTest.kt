package dev.geode.feature.customization.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.BeatSpan
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CameraMove
import dev.geode.feature.customization.api.CameraMoveKind
import dev.geode.feature.customization.api.Lane
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.PaletteChanges
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.StepLength
import dev.geode.feature.customization.api.StylePool
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationActionsTest {
    private val trails = Target.Shared(SharedProperty.TRAILS)
    private val mirror = Target.Shared(SharedProperty.MIRROR)

    @Test
    fun `Style switching and palette changes turn on from their defaults and keep their settings`() {
        val on = Automations.NONE.switchingStyle(true)
        assertEquals(StyleSwitching(), on.style)
        val tuned = on.copy(style = StyleSwitching(Cadence(Trigger.EveryBars(4))))
        assertEquals(tuned, tuned.switchingStyle(true))
        assertEquals(null, tuned.switchingStyle(false).style)
        assertEquals(PaletteChanges(), Automations.NONE.changingPalette(true).palette)
        val palette = Automations(palette = PaletteChanges(Cadence(Trigger.BigMoments())))
        assertEquals(palette, palette.changingPalette(true))
        assertEquals(null, palette.changingPalette(false).palette)
    }

    @Test
    fun `the trigger, its threshold and the spacing are the person's`() {
        val big = Cadence(Trigger.BigMoments(0.8f), Millis(5_000))
        assertSame("a big moment keeps its threshold", big, big.triggeredBy(Trigger.BigMoments()))
        assertEquals(Cadence(Trigger.EveryBars(4), Millis(5_000)), big.triggeredBy(Trigger.EveryBars(4)))
        val bars = Cadence(Trigger.EveryBars(8))
        assertEquals(bars.copy(trigger = Trigger.BigMoments()), bars.triggeredBy(Trigger.BigMoments()))
        assertEquals(Cadence(Trigger.BigMoments(0.3f), Millis(5_000)), big.thresholdOf(0.3f))
        assertSame(bars, bars.thresholdOf(0.3f))
        assertEquals(Millis(2_500), big.spacingOf(2.5f).spacing)
        assertEquals(5f, big.spacingSeconds, 0f)
        assertTrue(TRIGGERS.first().sameChoice(Trigger.BigMoments(0.1f)))
        assertFalse(Trigger.EveryBars(4).sameChoice(Trigger.EveryBars(8)))
        assertFalse(Trigger.EveryBars(4).sameChoice(Trigger.BigMoments()))
    }

    @Test
    fun `the Style transition and the palette fade in seconds`() {
        assertEquals(Millis(2_500), StyleSwitching().transitionOf(2.5f).transition)
        assertEquals(3f, StyleSwitching().seconds, 0f)
        assertEquals(Millis(4_000), PaletteChanges().fadeOf(4f).fade)
        assertEquals(2f, PaletteChanges().seconds, 0f)
    }

    @Test
    fun `Camera moves toggle one kind at a time, each with its own span and strength`() {
        val push = Automations.NONE.toggledCamera(CameraMoveKind.PUSH_IN)
        assertEquals(listOf(CameraMove(CameraMoveKind.PUSH_IN)), push.camera)
        assertEquals(emptyList<CameraMove>(), push.toggledCamera(CameraMoveKind.PUSH_IN).camera)
        val two = push.toggledCamera(CameraMoveKind.ROLL)
        assertEquals(2, two.camera.size)
        val faster = two.withCamera(two.camera.last().every(BeatSpan.ONE))
        assertEquals(CameraMove(CameraMoveKind.ROLL, BeatSpan.ONE), faster.camera.last())
        assertEquals(CameraMove(CameraMoveKind.PUSH_IN), faster.camera.first())
    }

    @Test
    fun `lanes start on one target and gain or lose targets with their values`() {
        val one = Automations.NONE.newLane(trails)
        assertEquals(listOf(Lane(listOf(trails), NEW_SEQUENCE_STEPS.map { listOf(it) })), one.lanes)
        val two = one.toggledInLane(0, mirror)
        assertEquals(listOf(trails, mirror), two.lanes.single().targets)
        assertEquals(NEW_SEQUENCE_STEPS.map { listOf(it, it) }, two.lanes.single().steps)
        assertEquals(Lane(listOf(mirror), NEW_SEQUENCE_STEPS.map { listOf(it) }), two.toggledInLane(0, trails).lanes.single())
        assertEquals(emptyList<Lane>(), one.toggledInLane(0, trails).lanes)
        assertSame(one, one.toggledInLane(3, mirror))
        assertEquals(
            StepLength.BEAT,
            one
                .laneStepping(0, StepLength.BEAT)
                .lanes
                .single()
                .step,
        )
        assertEquals(
            StepLength.BAR,
            one
                .laneStepping(1, StepLength.BEAT)
                .lanes
                .single()
                .step,
        )
        val pair = one.newLane(mirror)
        assertEquals(
            listOf(mirror),
            pair
                .withoutLane(0)
                .lanes
                .single()
                .targets,
        )
        assertEquals(
            listOf(SharedProperty.KALEIDOSCOPE).map {
                Target.Shared(it)
            },
            pair.freeTargets(listOf(trails, Target.Shared(SharedProperty.KALEIDOSCOPE), mirror)),
        )
    }

    @Test
    fun `only effects that keep brightness steady can be sequenced, and every Modulator's depth`() {
        val targets = PreviewModels.editing.sequenceTargets()
        assertEquals(Target.Shared(SharedProperty.KALEIDOSCOPE), targets.first())
        assertTrue(trails in targets)
        assertFalse(Target.Shared(SharedProperty.GLITCH) in targets)
        assertFalse(Target.Shared(SharedProperty.INVERT) in targets)
        assertFalse(Target.Shared(SharedProperty.SOLARIZE) in targets)
        assertEquals(
            (1..4).map { Target.ModulatorField(ModulatorId(it), ModulatorParameter.DEPTH) },
            targets.filterIsInstance<Target.ModulatorField>(),
        )
    }

    @Test
    fun `a folder's chip switches within its Styles as they are now`() {
        val calm = StyleFolderChip("Calm", listOf(SceneId("a"), SceneId("b")))
        val within = StyleSwitching().withinFolder(calm)
        assertEquals(StylePool.CHOSEN, within.pool)
        assertEquals(calm.styles, within.chosen)
        assertTrue(within.within(calm))
        assertFalse(within.within(calm.copy(styles = listOf(SceneId("a")))))
        assertFalse(StyleSwitching(pool = StylePool.PICKS, chosen = calm.styles).within(calm))
        assertFalse(StylePool.CHOSEN in POOL_CHOICES)
        assertTrue(StylePool.FAVOURITES in POOL_CHOICES)
    }

    @Test
    fun `a pair keeps two Styles, the newest taking the older one's place`() {
        val (a, b, c) = listOf(SceneId("a"), SceneId("b"), SceneId("c"))
        val pair = StyleSwitching(pool = StylePool.PAIR)
        assertEquals(listOf(a), pair.pairToggled(a).pair)
        assertEquals(listOf(a, b), pair.pairToggled(a).pairToggled(b).pair)
        assertEquals(listOf(b, c), pair.pairToggled(a).pairToggled(b).pairToggled(c).pair)
        assertEquals(listOf(b), pair.pairToggled(a).pairToggled(b).pairToggled(a).pair)
        assertEquals(listOf(a, b), StyleSwitching(chosen = listOf(a, b, c)).pair)
        assertEquals(listOf(b), StyleSwitching(chosen = listOf(a, b, c)).pairToggled(a).chosen)
        assertTrue(StylePool.PAIR in POOL_CHOICES)
    }
}
