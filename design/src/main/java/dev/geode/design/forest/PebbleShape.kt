package dev.geode.design.forest

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** One corner of a pebble: an ellipse [across] wide and [down] tall, so no two corners need match. */
data class PebbleCorner(
    val across: Dp,
    val down: Dp,
)

/** The four corners of a pebble, named by layout direction so the stone mirrors in right-to-left. */
data class PebbleCorners(
    val topStart: PebbleCorner,
    val topEnd: PebbleCorner,
    val bottomEnd: PebbleCorner,
    val bottomStart: PebbleCorner,
)

/**
 * A river-stone outline: a rounded box whose corners are uneven ellipses, so it never reads as a
 * perfect rectangle or a perfect capsule. A corner never reaches past half of its side, so a short
 * button closes into a pebble rather than folding over itself. Taps follow the bounds, not the
 * outline, so every pebble keeps a full [ForestSpacing.TapTarget].
 */
class PebbleShape(
    private val corners: PebbleCorners,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rounded(roundRect(size, layoutDirection, density))

    /** The outline as a [RoundRect], each corner radius clamped to half its sides. */
    fun roundRect(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): RoundRect {
        fun radius(corner: PebbleCorner): CornerRadius =
            with(density) {
                CornerRadius(
                    corner.across.toPx().coerceIn(0f, size.width / 2f),
                    corner.down.toPx().coerceIn(0f, size.height / 2f),
                )
            }
        // Mirroring swaps which corner lands where; an ellipse's width and height stay as they are.
        val ltr = layoutDirection == LayoutDirection.Ltr
        val topLeft = if (ltr) corners.topStart else corners.topEnd
        val topRight = if (ltr) corners.topEnd else corners.topStart
        val bottomRight = if (ltr) corners.bottomEnd else corners.bottomStart
        val bottomLeft = if (ltr) corners.bottomStart else corners.bottomEnd
        return RoundRect(
            left = 0f,
            top = 0f,
            right = size.width,
            bottom = size.height,
            topLeftCornerRadius = radius(topLeft),
            topRightCornerRadius = radius(topRight),
            bottomRightCornerRadius = radius(bottomRight),
            bottomLeftCornerRadius = radius(bottomLeft),
        )
    }

    override fun equals(other: Any?): Boolean = other is PebbleShape && other.corners == corners

    override fun hashCode(): Int = corners.hashCode()

    override fun toString(): String = "PebbleShape($corners)"
}

/** The pebble outlines of the App UI's surfaces. */
object ForestShapes {
    /** Buttons and the play control. */
    val Button =
        PebbleShape(
            PebbleCorners(
                topStart = PebbleCorner(22.dp, 18.dp),
                topEnd = PebbleCorner(18.dp, 22.dp),
                bottomEnd = PebbleCorner(24.dp, 20.dp),
                bottomStart = PebbleCorner(20.dp, 24.dp),
            ),
        )

    /** Chips and small toggles. */
    val Chip =
        PebbleShape(
            PebbleCorners(
                topStart = PebbleCorner(16.dp, 14.dp),
                topEnd = PebbleCorner(14.dp, 16.dp),
                bottomEnd = PebbleCorner(16.dp, 15.dp),
                bottomStart = PebbleCorner(15.dp, 16.dp),
            ),
        )

    /** Cards, album tiles and glass panels. */
    val Card =
        PebbleShape(
            PebbleCorners(
                topStart = PebbleCorner(28.dp, 24.dp),
                topEnd = PebbleCorner(24.dp, 30.dp),
                bottomEnd = PebbleCorner(30.dp, 26.dp),
                bottomStart = PebbleCorner(26.dp, 28.dp),
            ),
        )

    /** Bottom sheets: a worn top edge, square where they meet the screen's bottom. */
    val Sheet =
        PebbleShape(
            PebbleCorners(
                topStart = PebbleCorner(36.dp, 30.dp),
                topEnd = PebbleCorner(30.dp, 36.dp),
                bottomEnd = PebbleCorner(0.dp, 0.dp),
                bottomStart = PebbleCorner(0.dp, 0.dp),
            ),
        )
}
