package dev.geode.ui

import androidx.annotation.StringRes
import dev.geode.presentation.GeodeDestination
import dev.geode.resources.R
import dev.geode.ui.theme.StoneIcon

val GeodeDestination.labelRes: Int
    @StringRes get() =
        when (this) {
            GeodeDestination.PLAYER -> R.string.nav_player
            GeodeDestination.LIBRARY -> R.string.nav_library
            GeodeDestination.VISUALS -> R.string.nav_visuals
            GeodeDestination.STUDIO -> R.string.nav_studio
            GeodeDestination.SETTINGS -> R.string.nav_settings
        }

val GeodeDestination.icon: StoneIcon
    get() =
        when (this) {
            GeodeDestination.PLAYER -> StoneIcon.PLAY
            GeodeDestination.LIBRARY -> StoneIcon.LIBRARY
            GeodeDestination.VISUALS -> StoneIcon.VISUALIZER
            GeodeDestination.STUDIO -> StoneIcon.STUDIO
            GeodeDestination.SETTINGS -> StoneIcon.SETTINGS
        }
