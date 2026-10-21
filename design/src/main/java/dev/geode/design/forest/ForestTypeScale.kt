package dev.geode.design.forest

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.geode.design.R

/** How Fraunces and Figtree are set: the variable axes and the type scale. */
object ForestTypeScale {
    /** Fraunces's softness axis at full: letters rounded like water-worn stone. */
    const val FRAUNCES_SOFTNESS = 100f

    /** The variable axis tag of Fraunces's softness. */
    const val SOFTNESS_AXIS = "SOFT"

    /** The smallest size Fraunces is set at; everything smaller is Figtree. */
    val DisplayFloor = 24.sp

    val DisplayLarge = 48.sp
    val DisplayMedium = 40.sp
    val DisplaySmall = 32.sp
    val HeadlineLarge = 28.sp
    val HeadlineMedium = DisplayFloor
    val HeadlineSmall = 22.sp
    val TitleLarge = 20.sp
    val TitleMedium = 16.sp
    val TitleSmall = 14.sp
    val BodyLarge = 16.sp
    val BodyMedium = 14.sp
    val BodySmall = 12.sp
    val LabelLarge = 14.sp
    val LabelMedium = 12.sp
    val LabelSmall = 11.sp

    /** Line height as a multiple of the size for Fraunces display text. */
    const val DISPLAY_LINE_HEIGHT = 1.15f

    /** Line height as a multiple of the size for Figtree text. */
    const val TEXT_LINE_HEIGHT = 1.45f
}

private fun fraunces(weight: FontWeight) =
    Font(
        R.font.fraunces,
        weight,
        variationSettings =
            FontVariation.Settings(
                FontVariation.weight(weight.weight),
                FontVariation.Setting(ForestTypeScale.SOFTNESS_AXIS, ForestTypeScale.FRAUNCES_SOFTNESS),
            ),
    )

private fun figtree(weight: FontWeight) =
    Font(R.font.figtree, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** Fraunces (SIL OFL 1.1), the display face: one expressive headline per screen, at 24 sp and up. */
val FrauncesFamily =
    FontFamily(fraunces(FontWeight.Light), fraunces(FontWeight.Normal), fraunces(FontWeight.Medium))

/** Figtree (SIL OFL 1.1), the text face for everything that is not a headline. */
val FigtreeFamily =
    FontFamily(
        figtree(FontWeight.Normal),
        figtree(FontWeight.Medium),
        figtree(FontWeight.SemiBold),
        figtree(FontWeight.Bold),
    )

/** The Material type scale in Fraunces and Figtree. */
fun forestTypography(): Typography {
    fun display(
        size: TextUnit,
        weight: FontWeight,
    ) = TextStyle(
        fontFamily = FrauncesFamily,
        fontWeight = weight,
        fontSize = size,
        lineHeight = size * ForestTypeScale.DISPLAY_LINE_HEIGHT,
    )

    fun text(
        size: TextUnit,
        weight: FontWeight,
    ) = TextStyle(
        fontFamily = FigtreeFamily,
        fontWeight = weight,
        fontSize = size,
        lineHeight = size * ForestTypeScale.TEXT_LINE_HEIGHT,
    )
    return Typography(
        displayLarge = display(ForestTypeScale.DisplayLarge, FontWeight.Light),
        displayMedium = display(ForestTypeScale.DisplayMedium, FontWeight.Light),
        displaySmall = display(ForestTypeScale.DisplaySmall, FontWeight.Normal),
        headlineLarge = display(ForestTypeScale.HeadlineLarge, FontWeight.Normal),
        headlineMedium = display(ForestTypeScale.HeadlineMedium, FontWeight.Normal),
        headlineSmall = text(ForestTypeScale.HeadlineSmall, FontWeight.SemiBold),
        titleLarge = text(ForestTypeScale.TitleLarge, FontWeight.SemiBold),
        titleMedium = text(ForestTypeScale.TitleMedium, FontWeight.Medium),
        titleSmall = text(ForestTypeScale.TitleSmall, FontWeight.Medium),
        bodyLarge = text(ForestTypeScale.BodyLarge, FontWeight.Normal),
        bodyMedium = text(ForestTypeScale.BodyMedium, FontWeight.Normal),
        bodySmall = text(ForestTypeScale.BodySmall, FontWeight.Normal),
        labelLarge = text(ForestTypeScale.LabelLarge, FontWeight.Medium),
        labelMedium = text(ForestTypeScale.LabelMedium, FontWeight.Medium),
        labelSmall = text(ForestTypeScale.LabelSmall, FontWeight.Medium),
    )
}
