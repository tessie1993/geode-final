package dev.geode.design.water

import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.channels.Channel
import kotlin.math.roundToInt

/**
 * The ripples of one glass surface. Touches arrive through [touch]; the frame loop that
 * [rememberWaterRippleState] runs stamps them on the frame clock and advances them, and goes back
 * to sleep the moment the last one is still. An idle surface runs no frames, sets no render effect
 * and keeps its layer cached.
 */
@Stable
class WaterRippleState internal constructor(
    /** How this phone draws the ripple. */
    val tier: RippleTier,
    internal val spec: RippleSpec,
    internal val ring: ImageBitmap,
    private val shader: RippleEffectSource?,
) {
    /** The ripples alive now. */
    var rippleField by mutableStateOf(RippleField())
        private set

    /** The frame time the ripples are drawn at. */
    var nowNanos by mutableLongStateOf(0L)
        private set

    private val touches = Channel<Offset>(Channel.CONFLATED)
    private var surface = IntSize.Zero

    /** Starts a ripple at [position] in a surface of [size]. Only touch starts one, never sound. */
    fun touch(
        position: Offset,
        size: IntSize,
    ) {
        surface = size
        touches.trySend(position)
    }

    /**
     * Holds one ripple from [position] still at [progress] (0 to 1) of its life, without running
     * frames: for previews and screenshots that need one exact frame.
     */
    fun hold(
        position: Offset,
        size: IntSize,
        progress: Float,
    ) {
        val now = (progress.coerceIn(0f, 1f) * spec.durationMillis * NANOS_PER_MILLI).toLong()
        rippleField = RippleField().touched(ripple(position, size, HELD_START_NANOS))
        nowNanos = now
    }

    internal fun effect() = shader?.effect(rippleField, nowNanos)

    internal suspend fun run() {
        while (true) {
            val first = touches.receive()
            withFrameNanos { now -> start(first, now) }
            while (!rippleField.isIdle) {
                withFrameNanos { now ->
                    touches.tryReceive().getOrNull()?.let { start(it, now) }
                    nowNanos = now
                    rippleField = rippleField.prunedAt(now, spec)
                }
            }
        }
    }

    private fun start(
        position: Offset,
        now: Long,
    ) {
        nowNanos = now
        rippleField = rippleField.touched(ripple(position, surface, now))
    }

    private fun ripple(
        position: Offset,
        size: IntSize,
        start: Long,
    ) = Ripple(
        x = position.x,
        y = position.y,
        startNanos = start,
        reachPx = RipplePhysics.reach(position.x, position.y, size.width.toFloat(), size.height.toFloat()),
    )

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val HELD_START_NANOS = 0L
    }
}

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
