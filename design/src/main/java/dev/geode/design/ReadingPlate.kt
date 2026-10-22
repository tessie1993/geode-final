package dev.geode.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A tinted plate behind text laid over the Visual, darker at its top and bottom edges. */
fun Modifier.readingPlate(
    opacity: Float,
    tint: Color,
    corner: Dp = 0.dp,
    glow: Color? = null,
): Modifier {
    val a = opacity.coerceIn(0f, PLATE_OPACITY_MAX)
    val shape: Shape = if (corner > 0.dp) RoundedCornerShape(corner) else RectangleShape
    val edge = lerp(tint, Color.Black, PLATE_EDGE_DARKEN)
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                0f to edge.copy(alpha = (a + PLATE_TOP_EDGE_BOOST).coerceAtMost(PLATE_EDGE_OPACITY_MAX)),
                PLATE_EDGE_BAND to tint.copy(alpha = a),
                1f - PLATE_EDGE_BAND to tint.copy(alpha = a),
                1f to edge.copy(alpha = (a + PLATE_BOTTOM_EDGE_BOOST).coerceAtMost(PLATE_EDGE_OPACITY_MAX)),
            ),
        ).then(
            if (glow == null) {
                Modifier
            } else {
                Modifier
                    .border(
                        CrystalSize.Hairline,
                        Brush.verticalGradient(
                            0f to glow.copy(alpha = 0.55f),
                            0.6f to glow.copy(alpha = 0.14f),
                            1f to glow.copy(alpha = 0.32f),
                        ),
                        shape,
                    )
            },
        )
}

/** A gradient from clear at the top to [color] at the bottom, so text there reads over the Visual. */
fun Modifier.bottomScrim(
    color: Color = Color.Black,
    maxAlpha: Float = SCRIM_ALPHA,
): Modifier =
    background(
        Brush.verticalGradient(
            0f to Color.Transparent,
            1f to color.copy(alpha = maxAlpha.coerceIn(0f, 1f)),
        ),
    )

/** The most opaque a reading plate's body may be. */
private const val PLATE_OPACITY_MAX = 0.92f

/** The most opaque a reading plate's edges may be. */
private const val PLATE_EDGE_OPACITY_MAX = 0.94f

/** How far toward black a reading plate's edges are. */
private const val PLATE_EDGE_DARKEN = 0.4f

/** How much more opaque than the body a reading plate's top and bottom edges are. */
private const val PLATE_TOP_EDGE_BOOST = 0.14f
private const val PLATE_BOTTOM_EDGE_BOOST = 0.1f

/** The fraction of a reading plate's height each darker edge takes. */
private const val PLATE_EDGE_BAND = 0.14f

/** How dark a [bottomScrim] gets at its bottom by default. */
private const val SCRIM_ALPHA = 0.35f
