package dev.geode.design.water

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.channels.Channel

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
