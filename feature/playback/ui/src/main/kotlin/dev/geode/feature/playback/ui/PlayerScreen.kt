package dev.geode.feature.playback.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.design.glass.rememberPressing
import dev.geode.feature.playback.api.PlaybackFailure

/**
 * The full player (#34): what is playing, where it is, the transport, and the Player controls' options:
 * shuffle, repeat, the favourite heart, the queue and the listening tools (#17). Stateless (ADR-0003):
 * it draws [model] and sends every tap to [onAction]. [favourite] is whether the playing song is a
 * favourite, Library's to say; the heart is hidden while it is null.
 * The A-B loop is shaded on the seek bar (#147).
 */
@Composable
fun PlayerScreen(
    model: PlayerModel,
    onAction: (PlayerAction) -> Unit,
    modifier: Modifier = Modifier,
    favourite: Boolean? = null,
) {
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(PlayerDimens.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PlayerDimens.gap, Alignment.CenterVertically),
        ) {
            Artwork(Modifier.widthIn(max = PlayerDimens.artworkMaxWidth).fillMaxWidth())
            Titles(model)
            Timeline(model, onAction)
            Transport(model, onAction)
            Options(model, favourite, onAction)
            Status(model)
        }
    }
}

@Composable
private fun Artwork(modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(PlayerDimens.artworkCorner))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(PlayerDimens.artworkIcon),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun Titles(model: PlayerModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            model.track?.title ?: stringResource(R.string.playback_nothing_playing),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            model.track?.artist ?: stringResource(R.string.playback_nothing_playing_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Timeline(
    model: PlayerModel,
    onAction: (PlayerAction) -> Unit,
) {
    val seekable = model.status == PlayerStatus.PLAYING || model.status == PlayerStatus.PAUSED || model.status == PlayerStatus.BUFFERING
    // How far along a drag of the seek bar is, while one goes on: App UI state only (ADR-0003).
    var dragged by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth()) {
        // A plain seek bar with the A-B loop shaded on it (#147).
        LoopSeekBar(
            model,
            enabled = seekable,
            dragged = dragged,
            onDrag = { dragged = it },
            onSeek = { onAction(PlayerAction.SeekTo(it)) },
            modifier = Modifier.fillMaxWidth().height(PlayerDimens.seekBarHeight),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(model.timeShown(dragged)), style = MaterialTheme.typography.labelMedium)
            Text(formatTime(model.duration), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Transport(
    model: PlayerModel,
    onAction: (PlayerAction) -> Unit,
) {
    val hasTrack = model.track != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PlayerDimens.gap),
    ) {
        IconButton(onClick = rememberPressing { onAction(PlayerAction.Previous) }, enabled = hasTrack && model.canGoPrevious) {
            Icon(
                Icons.Rounded.SkipPrevious,
                stringResource(R.string.playback_action_previous),
                Modifier.size(PlayerDimens.sideIcon),
            )
        }
        FilledIconButton(
            onClick = rememberPressing { onAction(PlayerAction.PlayPause) },
            enabled = hasTrack,
            modifier = Modifier.size(PlayerDimens.mainButton),
        ) {
            PlayPauseIcon(model.meantToPlay, Modifier.size(PlayerDimens.mainIcon))
        }
        IconButton(onClick = rememberPressing { onAction(PlayerAction.Next) }, enabled = hasTrack && model.canGoNext) {
            Icon(Icons.Rounded.SkipNext, stringResource(R.string.playback_action_next), Modifier.size(PlayerDimens.sideIcon))
        }
    }
}

@Composable
private fun Options(
    model: PlayerModel,
    favourite: Boolean?,
    onAction: (PlayerAction) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShuffleButton(model.shuffle) { onAction(PlayerAction.ToggleShuffle) }
        RepeatButton(model.repeat) { onAction(PlayerAction.CycleRepeat) }
        if (favourite != null) {
            IconButton(onClick = rememberPressing { onAction(PlayerAction.ToggleFavourite) }, enabled = model.track != null) {
                if (favourite) {
                    Icon(
                        Icons.Rounded.Favorite,
                        stringResource(R.string.playback_action_unfavourite),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(Icons.Rounded.FavoriteBorder, stringResource(R.string.playback_action_favourite))
                }
            }
        }
        IconButton(onClick = rememberPressing { onAction(PlayerAction.OpenQueue) }) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.playback_action_open_queue))
        }
        IconButton(onClick = rememberPressing { onAction(PlayerAction.OpenTools) }) {
            Icon(Icons.Rounded.Tune, stringResource(R.string.playback_action_open_tools))
        }
    }
}

@Composable
private fun PlayPauseIcon(
    meantToPlay: Boolean,
    modifier: Modifier,
) {
    if (meantToPlay) {
        Icon(Icons.Rounded.Pause, stringResource(R.string.playback_action_pause), modifier)
    } else {
        Icon(Icons.Rounded.PlayArrow, stringResource(R.string.playback_action_play), modifier)
    }
}

@Composable
private fun Status(model: PlayerModel) {
    val text =
        when (model.status) {
            PlayerStatus.LOADING -> stringResource(R.string.playback_opening)
            PlayerStatus.BUFFERING -> stringResource(R.string.playback_buffering)
            PlayerStatus.ENDED -> stringResource(R.string.playback_ended)
            PlayerStatus.ERROR -> failureText(model.failure)
            PlayerStatus.IDLE, PlayerStatus.PLAYING, PlayerStatus.PAUSED -> null
        }
    // Always laid out, so the controls do not jump when a status comes and goes.
    Text(
        text.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (model.status == PlayerStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun failureText(failure: PlaybackFailure?): String? =
    when (failure) {
        PlaybackFailure.ENGINE_NOT_RESPONDING -> stringResource(R.string.playback_error_engine)
        null -> null
    }

/**
 * The mini-player (#34): the current song, play/pause and next, and a thin progress line. Tapping it
 * sends [PlayerAction.Expand]. Draws nothing while nothing is playing.
 */
@Composable
fun MiniPlayer(
    model: PlayerModel,
    onAction: (PlayerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = model.track ?: return
    val openLabel = stringResource(R.string.playback_action_open_player)
    // Clear: the mini-player sits on the glass the shell gives it (the Bar's slab, Home's card).
    Surface(modifier.fillMaxWidth(), color = Color.Transparent) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = openLabel) { onAction(PlayerAction.Expand) },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = PlayerDimens.miniGutter, vertical = PlayerDimens.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(PlayerDimens.miniIcon),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(PlayerDimens.miniGutter))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = rememberPressing { onAction(PlayerAction.PlayPause) }) {
                    PlayPauseIcon(model.meantToPlay, Modifier.size(PlayerDimens.miniIcon))
                }
                IconButton(onClick = rememberPressing { onAction(PlayerAction.Next) }, enabled = model.canGoNext) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.playback_action_next), Modifier.size(PlayerDimens.miniIcon))
                }
            }
            LinearProgressIndicator(
                progress = { fraction(model.position, model.duration) },
                modifier = Modifier.fillMaxWidth().height(PlayerDimens.miniProgress),
            )
        }
    }
}
