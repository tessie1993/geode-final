package dev.geode.ui.shell

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import dev.geode.R

/** Each place's name on the Bar. The same for everyone. */
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

/** Each place's icon on the Bar: familiar Material icons, all from the one rounded set. */
val Place.icon: ImageVector
    get() =
        when (this) {
            Place.MUSIC -> Icons.Rounded.LibraryMusic
            Place.VISUALS -> Icons.Rounded.AutoAwesome
            Place.HOME -> Icons.Rounded.Home
            Place.STUDIO -> Icons.Rounded.VideoLibrary
            Place.SETTINGS -> Icons.Rounded.Settings
        }
