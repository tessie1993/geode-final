package dev.geode.ui

import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.SharedProperty

/**
 * A pinch's zoom is the Customization's own Camera zoom (#397): previewed while the fingers move, then
 * set, so the whole pinch is one undo step and the zoom stays where the person left it. Customize's Zoom
 * control and its reset are the one-finger way to the same value.
 */
class CameraZoom(
    private val customization: CustomizationFeature,
) : PinchZoom.Target {
    override fun zoom(): Float = customization.state.value.current.shared[SharedProperty.ZOOM] ?: SharedProperty.ZOOM.default

    override fun preview(zoom: Float) {
        customization.send(CustomizationCommand.Preview(CustomizationCommand.SetShared(SharedProperty.ZOOM, zoom)))
    }

    override fun commit(zoom: Float) {
        customization.send(CustomizationCommand.SetShared(SharedProperty.ZOOM, zoom))
    }
}
