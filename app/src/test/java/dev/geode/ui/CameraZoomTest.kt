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
 * A pinch's zoom is the Customization's own Camera zoom (#397), through the real reducer: it follows the
 * fingers as a preview, stays where the pinch leaves it, and the whole pinch is one undo step.
 */
class CameraZoomTest {
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

    private val camera = CameraZoom(customization)

    private fun zoomNow(): Float? = customization.state.value.current.shared[SharedProperty.ZOOM]

    @Test
    fun `a pinch starts from the Customization's zoom`() {
        customization.send(CustomizationCommand.SetShared(SharedProperty.ZOOM, 1.25f))

        assertEquals(1.25f, camera.zoom(), 0f)
    }

    @Test
    fun `the zoom follows the fingers, then stays where the pinch leaves it`() {
        val pinch = PinchZoom(SLOP, camera)
        val before = customization.state.value.current

        pinch.start(200f)
        pinch.move(240f)
        pinch.move(480f)
        assertEquals(2f, zoomNow())
        assertNotNull(customization.state.value.previewFrom)

        pinch.end()

        val after = customization.state.value
        assertEquals(2f, after.current.shared[SharedProperty.ZOOM])
        assertNull(after.previewFrom)
        // One undo step, back to the zoom from before the pinch.
        assertEquals(listOf(before), after.undo)
        customization.send(CustomizationCommand.Undo)
        assertEquals(before, customization.state.value.current)
    }

    private companion object {
        const val SLOP = 16f
    }
}
