package dev.geode.feature.playback.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.design.glass.GlassMessage
import dev.geode.feature.playback.api.Track

/**
 * The queue (#35): every track in the order it plays, the current one marked, with shuffle and repeat.
 * A track is played by tapping it, moved up or down, or taken out; the current track stays. Stateless
 * (ADR-0003): it draws [model] and sends every tap to [onAction].
 */
@Composable
fun QueueScreen(
    model: QueueModel,
    onAction: (QueueAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            QueueHeader(model, onAction)
            HorizontalDivider()
            if (model.tracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlassMessage(
                        title = stringResource(R.string.playback_queue_empty),
                        body = stringResource(R.string.playback_queue_empty_hint),
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(model.tracks) { index, track ->
                        QueueRow(index, track, model, onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueHeader(
    model: QueueModel,
    onAction: (QueueAction) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PlayerDimens.gutter, vertical = PlayerDimens.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.playback_queue_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        ShuffleButton(model.shuffle) { onAction(QueueAction.ToggleShuffle) }
        RepeatButton(model.repeat) { onAction(QueueAction.CycleRepeat) }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    track: Track,
    model: QueueModel,
    onAction: (QueueAction) -> Unit,
) {
    val current = index == model.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onAction(QueueAction.Play(index)) }
            .padding(start = PlayerDimens.gutter, end = PlayerDimens.small, top = PlayerDimens.small, bottom = PlayerDimens.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PlayerDimens.miniGutter),
    ) {
        if (current) {
            Icon(
                Icons.Rounded.PlayArrow,
                stringResource(R.string.playback_now_playing),
                Modifier.size(PlayerDimens.miniIcon),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(PlayerDimens.miniIcon),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.Bold else null,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.playback_queue_track_detail, track.artist, formatTime(track.duration)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onAction(QueueAction.MoveUp(index)) }, enabled = index > 0) {
            Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.playback_action_move_up))
        }
        IconButton(onClick = { onAction(QueueAction.MoveDown(index)) }, enabled = index < model.tracks.lastIndex) {
            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.playback_action_move_down))
        }
        IconButton(onClick = { onAction(QueueAction.Remove(index)) }, enabled = !current) {
            Icon(Icons.Rounded.Close, stringResource(R.string.playback_action_remove))
        }
    }
}
