package dev.geode.design.water

import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.geode.design.R
import dev.geode.design.forest.rememberAnimationsRemoved
import kotlin.math.roundToInt

/** The ripple tier of this phone: the shader on 13+, the ring on 12, a pulse when animations are removed. */
@Composable
fun rememberRippleTier(): RippleTier {
    val removed = rememberAnimationsRemoved()
    return remember(removed) { RippleTier.select(Build.VERSION.SDK_INT, animationsEnabled = !removed) }
}

/** A [WaterRippleState] for one surface, with its frame loop running (only while it ripples) while composed. */
@Composable
fun rememberWaterRippleState(tier: RippleTier = rememberRippleTier()): WaterRippleState {
    val density = LocalDensity.current
    val ring = ImageBitmap.imageResource(R.drawable.design_ripple_ring)
    val state =
        remember(tier, density, ring) {
            val spec = WaterTokens.rippleSpec(tier, density)
            WaterRippleState(tier, spec, ring, shaderFor(tier, spec))
        }
    LaunchedEffect(state) { state.run() }
    return state
}

/**
 * Lets [state] ripple through everything drawn after this modifier where it is touched. The touch
 * is not consumed, so the control underneath still gets its click, and a [WateryHaptic.Ripple]
 * plays through [haptics] (silent when the person switched haptics off in Settings). The ripple
 * follows touch only: nothing about it listens to the music.
 */
fun Modifier.waterRipple(
    state: WaterRippleState,
    haptics: WateryHapticsPlayer,
): Modifier =
    this
        .pointerInput(state, haptics) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                state.touch(down.position, size)
                haptics.play(WateryHaptic.Ripple)
            }
        }.then(
            when (state.tier) {
                RippleTier.Shader -> Modifier.graphicsLayer { renderEffect = state.effect() }
                RippleTier.Ring -> Modifier.drawWithContent { drawRings(state) }
                RippleTier.Pulse -> Modifier.drawWithContent { drawPulse(state) }
            },
        )

private fun shaderFor(
    tier: RippleTier,
    spec: RippleSpec,
): RippleEffectSource? =
    if (tier == RippleTier.Shader && Build.VERSION.SDK_INT >= RippleTier.SHADER_MIN_SDK) {
        RippleShader(spec, WaterTokens.CREST_LIGHT)
    } else {
        null
    }

/** Android 12: the pre-rendered ring, scaled out with the wavefront and faded with the ripple's strength. */
private fun ContentDrawScope.drawRings(state: WaterRippleState) {
    drawContent()
    state.rippleField.ripples.forEach { ripple ->
        val frame = RipplePhysics.frame(ripple, state.nowNanos, state.spec)
        val half = frame.radiusPx / WaterTokens.RING_SPRITE_FRONT
        val side = (half * 2).roundToInt()
        if (side > 0) {
            drawImage(
                image = state.ring,
                dstOffset = IntOffset((ripple.x - half).roundToInt(), (ripple.y - half).roundToInt()),
                dstSize = IntSize(side, side),
                alpha = (frame.strength * WaterTokens.RING_ALPHA).coerceIn(0f, 1f),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/** Animations removed: a brief, still brightening of the touched glass that fades out. */
private fun ContentDrawScope.drawPulse(state: WaterRippleState) {
    drawContent()
    state.rippleField.ripples.forEach { ripple ->
        val frame = RipplePhysics.frame(ripple, state.nowNanos, state.spec)
        drawRect(Color.White, alpha = (frame.strength * WaterTokens.PULSE_ALPHA).coerceIn(0f, 1f))
    }
}
