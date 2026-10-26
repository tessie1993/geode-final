package dev.geode.feature.playback.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.AbLoop
import dev.geode.feature.playback.api.Bookmark
import dev.geode.feature.playback.api.MAX_CROSSFADE
import dev.geode.feature.playback.api.QueueEnd
import dev.geode.feature.playback.api.SleepTimer
import kotlin.math.roundToLong

/**
 * The listening tools (#35): the sleep timer, crossfade and gapless, what happens when the queue
 * ends, the A-B loop and the bookmarks. Stateless (ADR-0003): it draws [model] and sends every
 * choice to [onAction].
 */
@Composable
fun ListeningScreen(
    model: ListeningModel,
    onAction: (ListeningAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PlayerDimens.gutter),
            verticalArrangement = Arrangement.spacedBy(PlayerDimens.gap),
        ) {
            Text(stringResource(R.string.playback_tools_title), style = MaterialTheme.typography.titleLarge)
            SleepSection(model.sleep, onAction)
            JoinSection(model, onAction)
            QueueEndSection(model.queueEnd, onAction)
            LoopSection(model, onAction)
            BookmarkSection(model, onAction)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SleepSection(
    sleep: SleepTimer,
    onAction: (ListeningAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PlayerDimens.small)) {
        Heading(stringResource(R.string.playback_sleep_title))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(PlayerDimens.small)) {
            FilterChip(
                selected = sleep == SleepTimer.Off,
                onClick = { onAction(ListeningAction.CancelSleep) },
                label = { Text(stringResource(R.string.playback_sleep_off)) },
            )
            SLEEP_CHOICES.forEach { minutes ->
                FilterChip(
                    selected = false,
                    onClick = { onAction(ListeningAction.StartSleep(Millis(minutes * MILLIS_PER_MINUTE))) },
                    label = { Text(stringResource(R.string.playback_sleep_minutes, minutes)) },
                )
            }
            FilterChip(
                selected = sleep == SleepTimer.AfterTrack,
                onClick = { onAction(ListeningAction.SleepAfterTrack) },
                label = { Text(stringResource(R.string.playback_sleep_end_of_track)) },
            )
        }
        val status =
            when (sleep) {
                SleepTimer.Off -> null
                is SleepTimer.Counting -> stringResource(R.string.playback_sleep_pausing_in, formatTime(sleep.remaining))
                SleepTimer.AfterTrack -> stringResource(R.string.playback_sleep_pausing_after)
            }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun JoinSection(
    model: ListeningModel,
    onAction: (ListeningAction) -> Unit,
) {
    // Where the thumb is while it is dragged, in seconds: App UI state only (ADR-0003).
    var dragged by remember { mutableStateOf<Float?>(null) }
    val seconds = dragged ?: (model.crossfade.value / MILLIS_PER_SECOND_LONG).toFloat()
    val crossfadeLabel = stringResource(R.string.playback_crossfade_title)
    Column(verticalArrangement = Arrangement.spacedBy(PlayerDimens.small)) {
        Heading(crossfadeLabel)
        Text(
            if (seconds.roundToLong() == 0L) {
                stringResource(R.string.playback_crossfade_off)
            } else {
                stringResource(R.string.playback_crossfade_seconds, seconds.roundToLong())
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = seconds,
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let { onAction(ListeningAction.SetCrossfade(Millis(it.roundToLong() * MILLIS_PER_SECOND_LONG))) }
                dragged = null
            },
            valueRange = 0f..CROSSFADE_SECONDS,
            steps = CROSSFADE_SECONDS.toInt() - 1,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = crossfadeLabel },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.playback_gapless), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = model.gapless, onCheckedChange = { onAction(ListeningAction.SetGapless(it)) })
        }
    }
}

@Composable
private fun QueueEndSection(
    end: QueueEnd,
    onAction: (ListeningAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PlayerDimens.small)) {
        Heading(stringResource(R.string.playback_queue_end_title))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(PlayerDimens.small)) {
            FilterChip(
                selected = end == QueueEnd.STOP,
                onClick = { onAction(ListeningAction.SetQueueEnd(QueueEnd.STOP)) },
                label = { Text(stringResource(R.string.playback_queue_end_stop)) },
            )
            FilterChip(
                selected = end == QueueEnd.SIMILAR,
                onClick = { onAction(ListeningAction.SetQueueEnd(QueueEnd.SIMILAR)) },
                label = { Text(stringResource(R.string.playback_queue_end_similar)) },
            )
        }
    }
}

@Composable
private fun LoopSection(
    model: ListeningModel,
    onAction: (ListeningAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PlayerDimens.small)) {
        Heading(stringResource(R.string.playback_loop_title))
        val loop = model.loop
        Text(
            when (loop) {
                AbLoop.Off -> stringResource(R.string.playback_loop_off)
                is AbLoop.Starting -> stringResource(R.string.playback_loop_starting, formatTime(loop.start))
                is AbLoop.On -> stringResource(R.string.playback_loop_on, formatTime(loop.start), formatTime(loop.end))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PlayerDimens.small)) {
            OutlinedButton(onClick = { onAction(ListeningAction.MarkLoop) }, enabled = model.trackOpen) {
                Text(
                    when (loop) {
                        AbLoop.Off -> stringResource(R.string.playback_loop_set_a)
                        is AbLoop.Starting -> stringResource(R.string.playback_loop_set_b)
                        is AbLoop.On -> stringResource(R.string.playback_loop_clear)
                    },
                )
            }
            if (loop is AbLoop.Starting) {
                TextButton(onClick = { onAction(ListeningAction.ClearLoop) }) { Text(stringResource(R.string.playback_loop_clear)) }
            }
        }
    }
}

@Composable
private fun BookmarkSection(
    model: ListeningModel,
    onAction: (ListeningAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PlayerDimens.small)) {
        Heading(stringResource(R.string.playback_bookmarks_title))
        OutlinedButton(onClick = { onAction(ListeningAction.AddBookmark) }, enabled = model.trackOpen) {
            Icon(Icons.Rounded.BookmarkAdd, contentDescription = null)
            Text(stringResource(R.string.playback_bookmarks_add), Modifier.padding(start = PlayerDimens.small))
        }
        if (model.bookmarks.isEmpty()) {
            Text(
                stringResource(R.string.playback_bookmarks_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        model.bookmarks.forEach { BookmarkRow(it, onAction) }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onAction: (ListeningAction) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onAction(ListeningAction.GoToBookmark(bookmark.id)) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PlayerDimens.miniGutter),
    ) {
        Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(bookmark.track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.playback_queue_track_detail, bookmark.track.artist, formatTime(bookmark.position)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onAction(ListeningAction.RemoveBookmark(bookmark.id)) }) {
            Icon(Icons.Rounded.Delete, stringResource(R.string.playback_action_remove_bookmark))
        }
    }
}

/** The crossfade slider's range, in whole seconds. */
private val CROSSFADE_SECONDS = (MAX_CROSSFADE.value / MILLIS_PER_SECOND_LONG).toFloat()
