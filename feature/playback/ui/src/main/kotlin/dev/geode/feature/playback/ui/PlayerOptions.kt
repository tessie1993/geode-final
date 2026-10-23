package dev.geode.feature.playback.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.geode.design.element.LocalElementTokens
import dev.geode.feature.playback.api.RepeatMode

/** Shuffle, lit while [on]; the Player controls and the queue both show it. */
@Composable
internal fun ShuffleButton(
    on: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        if (on) {
            Icon(Icons.Filled.ShuffleOn, stringResource(R.string.playback_shuffle_on), tint = LocalElementTokens.current.colors.active)
        } else {
            Icon(Icons.Filled.Shuffle, stringResource(R.string.playback_shuffle_off))
        }
    }
}

/** Repeat in its [mode], lit while it repeats; the Player controls and the queue both show it. */
@Composable
internal fun RepeatButton(
    mode: RepeatMode,
    onClick: () -> Unit,
) {
    val lit = LocalElementTokens.current.colors.active
    IconButton(onClick = onClick) {
        when (mode) {
            RepeatMode.OFF -> Icon(Icons.Filled.Repeat, stringResource(R.string.playback_repeat_off))
            RepeatMode.ALL -> Icon(Icons.Filled.RepeatOn, stringResource(R.string.playback_repeat_all), tint = lit)
            RepeatMode.ONE -> Icon(Icons.Filled.RepeatOneOn, stringResource(R.string.playback_repeat_one), tint = lit)
        }
    }
}
