package dev.geode.feature.playback.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOn
import androidx.compose.material.icons.rounded.RepeatOneOn
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ShuffleOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.geode.design.glass.rememberPressing
import dev.geode.feature.playback.api.RepeatMode

/** Shuffle, lit while [on]; the Player controls and the queue both show it. */
@Composable
internal fun ShuffleButton(
    on: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = rememberPressing(onClick)) {
        if (on) {
            Icon(Icons.Rounded.ShuffleOn, stringResource(R.string.playback_shuffle_on), tint = MaterialTheme.colorScheme.primary)
        } else {
            Icon(Icons.Rounded.Shuffle, stringResource(R.string.playback_shuffle_off))
        }
    }
}

/** Repeat in its [mode], lit while it repeats; the Player controls and the queue both show it. */
@Composable
internal fun RepeatButton(
    mode: RepeatMode,
    onClick: () -> Unit,
) {
    IconButton(onClick = rememberPressing(onClick)) {
        when (mode) {
            RepeatMode.OFF -> Icon(Icons.Rounded.Repeat, stringResource(R.string.playback_repeat_off))
            RepeatMode.ALL ->
                Icon(Icons.Rounded.RepeatOn, stringResource(R.string.playback_repeat_all), tint = MaterialTheme.colorScheme.primary)
            RepeatMode.ONE ->
                Icon(Icons.Rounded.RepeatOneOn, stringResource(R.string.playback_repeat_one), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
