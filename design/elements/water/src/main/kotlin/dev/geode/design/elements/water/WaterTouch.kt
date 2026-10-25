package dev.geode.design.elements.water

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/**
 * Water's touch on a pebble (#154): an aqua ink cloud blooms under the surface from [touch] and
 * fades, a caustic ripple ring runs out ahead of it, and the pebble brightens from within. Drawn over
 * the pebble and clipped to its round shape.
 *
 * Draw only: [progress] (0 at the press, 1 at the end; see [WaterTouchTiming.progressAt]) and [touch]
 * (in the pebble's own pixels, or unspecified for its centre) are read in the draw phase, so a running
 * touch never recomposes. The shared contract (#153) drives them. Nothing is blurred and nothing
 * behind the pebble is sampled: every glow is a radial gradient. Once [progress] reaches 1 it draws
 * nothing.
 *
 * ponytail: the optional AGSL ring crest for API 33+ is left out; this Canvas ring is the procedural
 * fallback #154 asks for and runs on every API. Add the shader when the crest is reviewed on a device.
 */
fun Modifier.waterTouch(
    progress: () -> Float,
    touch: () -> Offset,
    reducedMotion: Boolean,
    ink: Color = WaterInk,
    ring: Color = WaterRing,
): Modifier =
    drawWithCache {
        val pebble =
            Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(size.minDimension / 2f)))
            }
        onDrawWithContent {
            drawContent()
            val p = progress().coerceIn(0f, 1f)
            if (!WaterTouchTiming.isFinished(p) && size.minDimension > 0f) {
                clipPath(pebble) { drawTouch(p, touch(), reducedMotion, ink, ring) }
            }
        }
    }

private fun DrawScope.drawTouch(
    progress: Float,
    touch: Offset,
    reducedMotion: Boolean,
    ink: Color,
    ring: Color,
) {
    val at = if (touch.isSpecified) touch else center
    val full = size.maxDimension * BLOOM_REACH
    val lit = WaterTouchTiming.bloomAlpha(progress)
    // The pebble brightens from within.
    drawCircle(
        Brush.radialGradient(listOf(ring.copy(alpha = CORE_ALPHA * lit), Color.Transparent), center = center, radius = size.minDimension),
        radius = size.minDimension,
        center = center,
    )
    // The ink cloud under the surface.
    val bloom = full * WaterTouchTiming.bloomSpread(progress, reducedMotion)
    drawCircle(
        Brush.radialGradient(
            0f to ink.copy(alpha = BLOOM_ALPHA * lit),
            BLOOM_BODY to ink.copy(alpha = BLOOM_ALPHA * lit * BLOOM_BODY_ALPHA),
            1f to Color.Transparent,
            center = at,
            radius = bloom,
        ),
        radius = bloom,
        center = at,
    )
    // The ripple ring: a soft caustic band with a bright crest, ahead of the ink.
    val ringAlpha = WaterTouchTiming.ringAlpha(progress, reducedMotion)
    if (ringAlpha <= 0f) return
    val radius = full * WaterTouchTiming.ringTravel(progress)
    drawCircle(ring.copy(alpha = BAND_ALPHA * ringAlpha), radius = radius, center = at, style = Stroke(BAND_WIDTH.toPx()))
    drawCircle(ring.copy(alpha = CREST_ALPHA * ringAlpha), radius = radius, center = at, style = Stroke(CREST_WIDTH.toPx()))
}

/** Water's milky aqua ink and its glow (#105 tokens). */
private val WaterInk = Color(0xFF7FD8CF)
private val WaterRing = Color(0xFFA8F0E6)

/** How far the bloom and ring reach, as a share of the pebble's longest side. */
private const val BLOOM_REACH = 0.9f
private const val BLOOM_ALPHA = 0.5f

/** The ink stays dense to this share of its radius, then thins out. */
private const val BLOOM_BODY = 0.45f
private const val BLOOM_BODY_ALPHA = 0.6f
private const val CORE_ALPHA = 0.35f
private const val BAND_ALPHA = 0.25f
private const val CREST_ALPHA = 0.8f
private val BAND_WIDTH = 6.dp
private val CREST_WIDTH = 1.5.dp
