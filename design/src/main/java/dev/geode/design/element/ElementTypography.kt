package dev.geode.design.element

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.geode.design.R

private fun variable(
    font: Int,
    weight: FontWeight,
) = Font(font, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** Space Grotesk (SIL OFL 1.1), the display face of every first-wave Element. */
val SpaceGroteskFamily =
    FontFamily(
        variable(R.font.space_grotesk, FontWeight.Light),
        variable(R.font.space_grotesk, FontWeight.Normal),
        variable(R.font.space_grotesk, FontWeight.Medium),
        variable(R.font.space_grotesk, FontWeight.SemiBold),
        variable(R.font.space_grotesk, FontWeight.Bold),
    )

/** Inter (SIL OFL 1.1), the text face of every first-wave Element. */
val InterFamily =
    FontFamily(
        variable(R.font.inter, FontWeight.Normal),
        variable(R.font.inter, FontWeight.Medium),
        variable(R.font.inter, FontWeight.SemiBold),
        variable(R.font.inter, FontWeight.Bold),
    )

/** The type scale every Element shares; only the faces ([ElementType]) may differ. */
object ElementTypeScale {
    val DisplayLarge = 48.sp
    val DisplayMedium = 40.sp
    val DisplaySmall = 32.sp
    val HeadlineLarge = 28.sp
    val HeadlineMedium = 24.sp
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

    /** Line height as a multiple of the size, for the display face. */
    const val DISPLAY_LINE_HEIGHT = 1.15f

    /** Line height as a multiple of the size, for the text face. */
    const val TEXT_LINE_HEIGHT = 1.45f
}

/** The Material type scale set in [type]: the display face for display and headline text, the text face for the rest. */
fun elementTypography(type: ElementType): Typography {
    fun display(
        size: TextUnit,
        weight: FontWeight,
    ) = TextStyle(
        fontFamily = type.display,
        fontWeight = weight,
        fontSize = size,
        lineHeight = size * ElementTypeScale.DISPLAY_LINE_HEIGHT,
    )

    fun text(
        size: TextUnit,
        weight: FontWeight,
    ) = TextStyle(
        fontFamily = type.text,
        fontWeight = weight,
        fontSize = size,
        lineHeight = size * ElementTypeScale.TEXT_LINE_HEIGHT,
    )
    return Typography(
        displayLarge = display(ElementTypeScale.DisplayLarge, FontWeight.Light),
        displayMedium = display(ElementTypeScale.DisplayMedium, FontWeight.Light),
        displaySmall = display(ElementTypeScale.DisplaySmall, FontWeight.Normal),
        headlineLarge = display(ElementTypeScale.HeadlineLarge, FontWeight.Normal),
        headlineMedium = display(ElementTypeScale.HeadlineMedium, FontWeight.Medium),
        headlineSmall = display(ElementTypeScale.HeadlineSmall, FontWeight.Medium),
        titleLarge = text(ElementTypeScale.TitleLarge, FontWeight.SemiBold),
        titleMedium = text(ElementTypeScale.TitleMedium, FontWeight.Medium),
        titleSmall = text(ElementTypeScale.TitleSmall, FontWeight.Medium),
        bodyLarge = text(ElementTypeScale.BodyLarge, FontWeight.Normal),
        bodyMedium = text(ElementTypeScale.BodyMedium, FontWeight.Normal),
        bodySmall = text(ElementTypeScale.BodySmall, FontWeight.Normal),
        labelLarge = text(ElementTypeScale.LabelLarge, FontWeight.Medium),
        labelMedium = text(ElementTypeScale.LabelMedium, FontWeight.Medium),
        labelSmall = text(ElementTypeScale.LabelSmall, FontWeight.Medium),
    )
}
