package dev.geode.ui

import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.SharedProperty

/**
 * Where two fingers leave the Camera is the Customization's own Camera (#397): its ZOOM, PAN_X, PAN_Y
 * and TURN. Each move previews what changed; the end of the gesture sets it, so the whole gesture,
 * zoom, pan and turn together, is one undo step and stays where the person left it. Customize's Camera
 * controls and their resets are the one-finger way to the same values.
 */
class CustomizationCamera(
    private val customization: CustomizationFeature,
) : CameraGesture.Target {
    override fun placement(): CameraGesture.Placement {
        val shared = customization.state.value.current.shared

        fun value(property: SharedProperty) = shared[property] ?: property.default
        return CameraGesture.Placement(
            zoom = value(SharedProperty.ZOOM),
            panX = value(SharedProperty.PAN_X),
            panY = value(SharedProperty.PAN_Y),
            turn = value(SharedProperty.TURN),
        )
    }

    override fun preview(placement: CameraGesture.Placement) {
        val now = placement()
        if (placement.zoom != now.zoom) show(SharedProperty.ZOOM, placement.zoom)
        if (placement.panX != now.panX) show(SharedProperty.PAN_X, placement.panX)
        if (placement.panY != now.panY) show(SharedProperty.PAN_Y, placement.panY)
        if (placement.turn != now.turn) show(SharedProperty.TURN, placement.turn)
    }

    override fun commit(placement: CameraGesture.Placement) {
        preview(placement)
        // Setting a value ends the preview as one undo step back to before the gesture; every value is
        // already shown, so one set is enough and the zoom's is as good as any.
        customization.send(CustomizationCommand.SetShared(SharedProperty.ZOOM, placement.zoom))
    }

    private fun show(
        property: SharedProperty,
        value: Float,
    ) {
        customization.send(CustomizationCommand.Preview(CustomizationCommand.SetShared(property, value)))
    }
}
