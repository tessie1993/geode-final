package dev.geode.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** The corner sets the App UI can be drawn with, smallest to largest Material shape. */
object CornerShapes {
    /** No rounding anywhere. */
    val Sharp: Shapes =
        Shapes(
            extraSmall = RoundedCornerShape(0.dp),
            small = RoundedCornerShape(0.dp),
            medium = RoundedCornerShape(0.dp),
            large = RoundedCornerShape(0.dp),
            extraLarge = RoundedCornerShape(0.dp),
        )

    /** Material 3's own corners. */
    val Rounded: Shapes = Shapes()

    /** Corners rounded nearly into pills. */
    val Pill: Shapes =
        Shapes(
            extraSmall = RoundedCornerShape(PillRadius.ExtraSmall),
            small = RoundedCornerShape(PillRadius.Small),
            medium = RoundedCornerShape(PillRadius.Medium),
            large = RoundedCornerShape(PillRadius.Large),
            extraLarge = RoundedCornerShape(PillRadius.ExtraLarge),
        )

    private object PillRadius {
        val ExtraSmall = 12.dp
        val Small = 16.dp
        val Medium = 24.dp
        val Large = 28.dp
        val ExtraLarge = 32.dp
    }
}

/** The range the person may scale the App UI's text over, as a multiple of its designed size. */
object TextScale {
    const val MIN = 0.85f
    const val DEFAULT = 1f
    const val MAX = 1.3f
}
