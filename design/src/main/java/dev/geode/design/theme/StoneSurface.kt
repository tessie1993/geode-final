package dev.geode.design.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import dev.geode.design.element.ElementPart

/**
 * [component]'s body in [state], drawn in the current Element ([ElementPart]), so the Crystal controls
 * follow the Element chosen in Settings rather than the theme pack's art (#193).
 */
@Composable
fun StoneSurfaceArt(
    component: StoneComponent,
    state: StoneState,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = LocalReducedMotion.current,
) = ElementPart(component, state, modifier, reducedMotion)

@Composable
fun Modifier.stonePress(
    interaction: InteractionSource,
    reducedMotion: Boolean = LocalReducedMotion.current,
): Modifier {
    val motion = LocalThemePack.current.motion
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    LaunchedEffect(pressed) {
        if (pressed) view.performStoneHaptic(StoneHapticCue.TAP)
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) motion.pressScale else 1f,
        animationSpec =
            if (pressed) {
                tween(motion.pressDurationMs)
            } else {
                spring(dampingRatio = 0.78f, stiffness = 380f)
            },
        label = "stone-press",
    )
    return scale(scale)
}

@Composable
fun rememberStoneState(
    interaction: MutableInteractionSource,
    enabled: Boolean = true,
    selected: Boolean = false,
): StoneState {
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    return stoneStateOf(enabled = enabled, pressed = pressed, selected = selected, focused = focused)
}

@Composable
fun rememberStoneInteraction(): MutableInteractionSource = remember { MutableInteractionSource() }
