package dev.geode.feature.settings.ui

import androidx.compose.ui.unit.dp

/** The App UI's sizes for Settings, until the shared design tokens (:design) are in place. */
internal object SettingsDimens {
    val gutter = 16.dp
    val groupPadding = 14.dp
    val groupGap = 14.dp
    val rowGap = 10.dp
    val chipGap = 6.dp
    val groupCorner = 18.dp

    // The Element picker's tiles; each is a tap target of at least 48 dp.
    val elementTileHeight = 56.dp
    val elementTilePadding = 10.dp
    val elementSwatch = 28.dp
    val elementBorder = 1.dp
    val elementChosenBorder = 2.dp
}
