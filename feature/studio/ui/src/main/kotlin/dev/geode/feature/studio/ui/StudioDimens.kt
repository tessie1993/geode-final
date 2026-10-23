package dev.geode.feature.studio.ui

import androidx.compose.ui.unit.dp

/** Spacing and sizes of the Studio screens. */
internal object StudioDimens {
    val padding = 16.dp
    val gap = 8.dp
    val thumbnailWidth = 96.dp

    /** Thumbnails are drawn 16:9 in lists. */
    const val THUMBNAIL_RATIO = 16f / 9f

    /** Text beside a control takes the room the control leaves. */
    const val FILL = 1f
}
