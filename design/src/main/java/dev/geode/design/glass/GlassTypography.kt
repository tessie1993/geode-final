package dev.geode.design.glass

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.geode.design.R

private fun inter(weight: FontWeight) =
    Font(R.font.inter, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** Inter (SIL OFL 1.1), the one face of the glass App UI: plain, open and legible at every size. */
val GlassFontFamily =
    FontFamily(
        inter(FontWeight.Normal),
        inter(FontWeight.Medium),
        inter(FontWeight.SemiBold),
        inter(FontWeight.Bold),
    )

/** Material's own type scale, set in Inter. The person's text size scales it through the density. */
fun glassTypography(): Typography {
    val material = Typography()

    fun TextStyle.inInter() = copy(fontFamily = GlassFontFamily)
    return Typography(
        displayLarge = material.displayLarge.inInter(),
        displayMedium = material.displayMedium.inInter(),
        displaySmall = material.displaySmall.inInter(),
        headlineLarge = material.headlineLarge.inInter(),
        headlineMedium = material.headlineMedium.inInter(),
        headlineSmall = material.headlineSmall.inInter(),
        titleLarge = material.titleLarge.copy(fontFamily = GlassFontFamily, fontWeight = FontWeight.SemiBold),
        titleMedium = material.titleMedium.copy(fontFamily = GlassFontFamily, fontWeight = FontWeight.SemiBold),
        titleSmall = material.titleSmall.inInter(),
        bodyLarge = material.bodyLarge.inInter(),
        bodyMedium = material.bodyMedium.inInter(),
        bodySmall = material.bodySmall.inInter(),
        labelLarge = material.labelLarge.inInter(),
        labelMedium = material.labelMedium.inInter(),
        labelSmall = material.labelSmall.inInter(),
    )
}
