package dev.geode.ui.shell

import androidx.annotation.StringRes
import dev.geode.R
import dev.geode.design.theme.StoneIcon

/** Each place's name on the Bar. The same in every Element. */
@get:StringRes
val Place.label: Int
    get() =
        when (this) {
            Place.MUSIC -> R.string.nav_music
            Place.VISUALS -> R.string.nav_visuals
            Place.HOME -> R.string.nav_home
            Place.STUDIO -> R.string.nav_studio
            Place.SETTINGS -> R.string.nav_settings
        }

/** Each place's glyph on the Bar. */
val Place.icon: StoneIcon
    get() =
        when (this) {
            Place.MUSIC -> StoneIcon.LIBRARY
            Place.VISUALS -> StoneIcon.VISUALIZER
            Place.HOME -> StoneIcon.HOME
            Place.STUDIO -> StoneIcon.STUDIO
            Place.SETTINGS -> StoneIcon.SETTINGS
        }
