package dev.geode.design.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/** The pack's touch on a pebble (an ink bloom and a ripple ring), or null when it has none. */
@Composable
fun stoneTouch(): Indication? {
    val pack = LocalThemePack.current
    return remember(pack) { pack.touchIndication() }
}

internal fun ThemePack.touchIndication(): Indication? =
    if (motion.inkBloomMs <= 0 && motion.rippleRingMs <= 0) {
        null
    } else {
        InkBloomIndication(ink = palette.primary, ring = palette.glow, bloomMs = motion.inkBloomMs, ringMs = motion.rippleRingMs)
    }

/** An ink cloud blooms from the touch point and fades, with a ring spreading ahead of it. */
private data class InkBloomIndication(
    val ink: Color,
    val ring: Color,
    val bloomMs: Int,
    val ringMs: Int,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = InkBloomNode(interactionSource, this)
}

private class InkBloomNode(
    private val source: InteractionSource,
    private val spec: InkBloomIndication,
) : Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private var origin = Offset.Unspecified
    private val bloom = Animatable(1f)
    private val ring = Animatable(1f)

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                if (interaction is PressInteraction.Press) {
                    origin = interaction.pressPosition
                    if (spec.bloomMs > 0) {
                        launch {
                            bloom.snapTo(0f)
                            bloom.animateTo(1f, tween(spec.bloomMs, easing = LinearOutSlowInEasing))
                        }
                    }
                    if (spec.ringMs > 0 && !currentValueOf(LocalReducedMotion)) {
                        launch {
                            ring.snapTo(0f)
                            ring.animateTo(1f, tween(spec.ringMs, easing = LinearEasing))
                        }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val b = bloom.value
        val g = ring.value
        if (b >= 1f && g >= 1f) return
        val at = if (origin.isSpecified) origin else center
        val reach = size.maxDimension
        // Reduced motion: the bloom fades in place instead of spreading.
        val still = currentValueOf(LocalReducedMotion)
        val pebble =
            Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(size.minDimension / 2f)))
            }
        clipPath(pebble) {
            if (b < 1f) {
                val r = reach * (if (still) 1f else BLOOM_START + (1f - BLOOM_START) * b)
                drawCircle(
                    Brush.radialGradient(
                        listOf(spec.ink.copy(alpha = BLOOM_ALPHA * (1f - b)), Color.Transparent),
                        center = at,
                        radius = r,
                    ),
                    radius = r,
                    center = at,
                )
            }
            if (g < 1f) {
                drawCircle(
                    spec.ring.copy(alpha = RING_ALPHA * (1f - g)),
                    radius = (reach * g).coerceAtLeast(1f),
                    center = at,
                    style = Stroke(RING_STROKE.toPx()),
                )
            }
        }
    }
}

/**
 * The pack's Library scroll wake: scrolling the list this modifies leaves current lines behind its
 * rows, which settle over the pack's [StoneMotion.scrollWakeMs]. Driven by scrolling only, never by
 * audio. Does nothing for a pack without a wake or under reduced motion.
 */
@Composable
fun Modifier.stoneScrollWake(): Modifier {
    val pack = LocalThemePack.current
    val settleMs = pack.motion.scrollWakeMs
    if (settleMs <= 0 || LocalReducedMotion.current) return this
    val wake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val connection =
        remember(settleMs) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    val moved = abs(consumed.y)
                    if (moved > 0f) {
                        scope.launch {
                            wake.snapTo(wakeAfter(wake.value, moved))
                            wake.animateTo(0f, tween(settleMs, easing = LinearOutSlowInEasing))
                        }
                    }
                    return Offset.Zero
                }
            }
        }
    val ink = pack.palette.primary
    val sea = pack.palette.secondary
    return nestedScroll(connection).drawBehind {
        val w = wake.value
        if (w <= 0f) return@drawBehind
        val step = WAKE_STEP.toPx()
        val sway = WAKE_SWAY.toPx() * w
        for (i in 0 until WAKE_LINES) {
            val x = size.width * (i + 0.5f) / WAKE_LINES
            val line = Path()
            line.moveTo(x, 0f)
            var y = 0f
            while (y < size.height) {
                y += step
                line.lineTo(x + sin(y / WAKE_WAVELENGTH.toPx() + i) * sway, y)
            }
            drawPath(line, (if (i % 2 == 0) ink else sea).copy(alpha = WAKE_ALPHA * w), style = Stroke(WAKE_STROKE.toPx()))
        }
    }
}

/** The wake after the list moved [movedPx] more: it builds with scrolling and tops out at 1. */
internal fun wakeAfter(
    current: Float,
    movedPx: Float,
): Float = (current + movedPx / WAKE_FULL_PX).coerceIn(0f, 1f)

/** How big a bloom starts, as a share of the part's longest side. */
private const val BLOOM_START = 0.2f
private const val BLOOM_ALPHA = 0.45f
private const val RING_ALPHA = 0.7f
private val RING_STROKE = 1.5.dp

/** Scrolled pixels that raise the wake to full. */
private const val WAKE_FULL_PX = 600f
private const val WAKE_LINES = 5
private const val WAKE_ALPHA = 0.22f
private val WAKE_STEP = 12.dp
private val WAKE_SWAY = 10.dp
private val WAKE_WAVELENGTH = 40.dp
private val WAKE_STROKE = 1.5.dp
