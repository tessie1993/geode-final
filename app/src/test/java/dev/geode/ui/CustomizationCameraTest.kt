package dev.geode.ui

import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFact
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.core.CUSTOMIZATION_FEATURE
import dev.geode.feature.customization.core.CustomizationEvent
import dev.geode.feature.customization.core.CustomizationReducer
import dev.geode.feature.customization.core.CustomizationWork
import dev.geode.feature.customization.core.Defaults
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Results
import dev.geode.kernel.inPlaceRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where two fingers leave the Camera is the Customization's own Camera (#397), through the real reducer:
 * it follows the fingers as a preview, stays where the gesture leaves it, and the whole gesture, zoom,
 * pan and turn together, is one undo step.
 */
class CustomizationCameraTest {
    private val customization: CustomizationFeature =
        FeatureStore<CustomizationState, CustomizationCommand, CustomizationEvent, CustomizationWork, CustomizationFact>(
            name = CUSTOMIZATION_FEATURE,
            initial = CustomizationState(Defaults.customization(BuiltInStyles.catalogue.manifestOf(BuiltInStyles.all[0].id))),
            reducer = CustomizationReducer(BuiltInStyles.catalogue),
            toEvent = CustomizationEvent::Command,
            runner =
                object : EffectRunner<CustomizationWork, CustomizationEvent> {
                    override suspend fun run(
                        work: CustomizationWork,
                        results: Results<CustomizationEvent>,
                    ) = Unit
                },
            runtime = inPlaceRuntime(),
        )

    private val camera = CustomizationCamera(customization)

    private fun shared(property: SharedProperty): Float? = customization.state.value.current.shared[property]

    @Test
    fun `a gesture starts from the Customization's Camera`() {
        customization.send(CustomizationCommand.SetShared(SharedProperty.ZOOM, 1.25f))
        customization.send(CustomizationCommand.SetShared(SharedProperty.PAN_X, -0.5f))
        customization.send(CustomizationCommand.SetShared(SharedProperty.TURN, 0.25f))

        assertEquals(CameraGesture.Placement(zoom = 1.25f, panX = -0.5f, panY = 0f, turn = 0.25f), camera.placement())
    }

    @Test
    fun `the Camera follows the fingers, then stays where the gesture leaves it, as one undo step`() {
        val gesture = CameraGesture(SLOP, camera).apply { resize(SIZE, SIZE) }
        val before = customization.state.value.current

        gesture.start(400f, 500f, 600f, 500f)
        gesture.move(380f, 500f, 620f, 500f)
        // Spread to twice the distance and slid up a fifth of the view.
        gesture.move(260f, 400f, 740f, 400f)
        assertEquals(2f, shared(SharedProperty.ZOOM))
        assertEquals(0.2f, shared(SharedProperty.PAN_Y)!!, EPSILON)
        assertNotNull(customization.state.value.previewFrom)

        gesture.end()

        val after = customization.state.value
        assertEquals(2f, after.current.shared[SharedProperty.ZOOM])
        assertEquals(0.2f, after.current.shared[SharedProperty.PAN_Y]!!, EPSILON)
        assertNull(after.previewFrom)
        assertEquals(listOf(before), after.undo)
        customization.send(CustomizationCommand.Undo)
        assertEquals(before, customization.state.value.current)
    }

    @Test
    fun `a gesture that never got past the slop changes nothing and adds no undo step`() {
        val gesture = CameraGesture(SLOP, camera).apply { resize(SIZE, SIZE) }
        val before = customization.state.value

        gesture.start(400f, 500f, 600f, 500f)
        gesture.move(402f, 500f, 602f, 500f)
        gesture.end()

        assertEquals(before, customization.state.value)
    }

    private companion object {
        const val SLOP = 16f
        const val SIZE = 1000f
        const val EPSILON = 1e-5f
    }
}
