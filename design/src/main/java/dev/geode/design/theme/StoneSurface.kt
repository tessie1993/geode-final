package dev.geode.design.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource

/** Whether the person asked for reduced motion; [CrystalTheme] provides it. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun StoneSurfaceArt(
    component: StoneComponent,
    state: StoneState,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = LocalReducedMotion.current,
) {
    val pack = LocalThemePack.current
    val motion = pack.motion
    val spun = modifier.stoneSpin(state == StoneState.SELECTED, spinMs(motion, component), reducedMotion)
    pack.drawn?.let { draw ->
        draw(component, state, spun, reducedMotion)
        return
    }
    val art = pack.surface(component)

    @Composable
    fun fade(target: StoneState): Float {
        val visible = state == target
        val durationMs =
            when {
                reducedMotion -> motion.reduceMotionCrossfadeMs
                target == StoneState.PRESSED || state == StoneState.PRESSED -> motion.pressDurationMs
                target == StoneState.FOCUSED || state == StoneState.FOCUSED -> motion.focusDurationMs
                else -> motion.selectedDurationMs
            }
        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(durationMs),
            label = "stone-state-$target",
        )
        return alpha
    }

    Box(modifier = spun) {
        Image(
            painter = painterResource(art.default),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize(),
        )
        for (s in listOf(StoneState.FOCUSED, StoneState.SELECTED, StoneState.PRESSED, StoneState.DISABLED)) {
            val alpha = fade(s)
            if (alpha > 0.01f) {
                Image(
                    painter = painterResource(art.forState(s)),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    alpha = alpha,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * How long [component]'s selection spin takes, or 0 when it does not spin. Pebbles spin; slabs,
 * rows and channels stay calm. Chips take the short spin.
 */
internal fun spinMs(
    motion: StoneMotion,
    component: StoneComponent,
): Int =
    when (component) {
        StoneComponent.ALBUM_TILE, StoneComponent.ICON_BUTTON -> motion.selectSpinMs
        StoneComponent.CHIP -> motion.selectSpinMs * SHORT_SPIN_PERCENT / PERCENT
        else -> 0
    }

/**
 * Turns the part once about its vertical axis, eased out over [durationMs], each time [selected]
 * becomes true. Nothing happens for a duration of 0 or under reduced motion, where the selected
 * state's glow swell is the whole cue.
 */
@Composable
fun Modifier.stoneSpin(
    selected: Boolean,
    durationMs: Int,
    reducedMotion: Boolean = LocalReducedMotion.current,
): Modifier {
    if (durationMs <= 0 || reducedMotion) return this
    val turn = remember { Animatable(0f) }
    val was = remember { booleanArrayOf(selected) }
    LaunchedEffect(selected) {
        val rising = selected && !was[0]
        was[0] = selected
        if (rising) {
            turn.snapTo(0f)
            turn.animateTo(FULL_TURN_DEGREES, tween(durationMs, easing = LinearOutSlowInEasing))
            turn.snapTo(0f)
        }
    }
    return graphicsLayer {
        rotationY = turn.value
        cameraDistance = SPIN_CAMERA_DISTANCE * density
    }
}

@Composable
fun Modifier.stonePress(
    interaction: InteractionSource,
    reducedMotion: Boolean = LocalReducedMotion.current,
): Modifier {
    val pack = LocalThemePack.current
    val motion = pack.motion
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    LaunchedEffect(pressed) {
        if (pressed) view.performStoneHaptic(StoneHapticCue.TAP, pack)
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

private const val FULL_TURN_DEGREES = 360f

/** How far the camera sits from a spinning part, in multiples of the density, so the turn reads as 3D. */
private const val SPIN_CAMERA_DISTANCE = 12f

private const val SHORT_SPIN_PERCENT = 70
private const val PERCENT = 100
