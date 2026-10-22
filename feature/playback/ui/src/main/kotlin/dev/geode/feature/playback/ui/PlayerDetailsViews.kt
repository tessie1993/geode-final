package dev.geode.feature.playback.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.Lyrics
import dev.geode.feature.playback.api.LyricsSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** No drag is going on. */
private const val NOT_DRAGGING = -1f

/**
 * The seek bar (#147): a plain line, the part played in the accent colour, with the A-B loop shaded
 * and a playhead. Tracks are never analysed (owner, 2026-10-22), so it draws no waveform. Tapping or
 * dragging seeks. Stateless: while a drag goes on it reports how far along it is to [onDrag], null
 * once it is let go, and draws [dragged] in place of the position, so its caller can show the time
 * it would seek to, as the old Slider did.
 */
@Composable
internal fun LoopSeekBar(
    model: PlayerModel,
    enabled: Boolean,
    dragged: Float?,
    onDrag: (Float?) -> Unit,
    onSeek: (Millis) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = model.duration.value
    val played = dragged ?: fraction(model.position, model.duration)
    val seek = { at: Float -> onSeek(Millis((at.coerceIn(0f, 1f) * duration).toLong())) }
    val colors = SeekBarColors.current()
    val seekLabel = stringResource(R.string.playback_action_seek)
    Canvas(
        modifier
            .semantics {
                contentDescription = seekLabel
                progressBarRangeInfo = ProgressBarRangeInfo(played, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        seek(target)
                        true
                    }
                }
            }.then(if (enabled) Modifier.seekGestures(duration, seek) { onDrag(it.takeIf { drag -> drag >= 0f }) } else Modifier),
    ) {
        model.loop?.takeIf { duration > 0 }?.let { drawLoop(it, model.duration, colors) }
        drawSeekLine(played, colors)
        drawPlayhead(played, colors)
    }
}

private fun Modifier.seekGestures(
    duration: Long,
    seek: (Float) -> Unit,
    onDrag: (Float) -> Unit,
): Modifier {
    var at = NOT_DRAGGING
    return pointerInput(duration) { detectTapGestures { offset -> seek(offset.x / size.width) } }
        .pointerInput(duration) {
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    at = (offset.x / size.width).coerceIn(0f, 1f)
                    onDrag(at)
                },
                onDragEnd = {
                    if (at >= 0f) seek(at)
                    at = NOT_DRAGGING
                    onDrag(at)
                },
                onDragCancel = {
                    at = NOT_DRAGGING
                    onDrag(at)
                },
            ) { change, amount ->
                change.consume()
                at = (at + amount / size.width).coerceIn(0f, 1f)
                onDrag(at)
            }
        }
}

private class SeekBarColors(
    val played: Color,
    val unplayed: Color,
    val loop: Color,
    val playhead: Color,
) {
    companion object {
        @Composable
        fun current(): SeekBarColors {
            val scheme = MaterialTheme.colorScheme
            return SeekBarColors(
                played = scheme.primary,
                unplayed = scheme.onSurfaceVariant.copy(alpha = UNPLAYED_ALPHA),
                loop = scheme.secondary.copy(alpha = LOOP_ALPHA),
                playhead = scheme.onSurface.copy(alpha = PLAYHEAD_ALPHA),
            )
        }
    }
}

private fun DrawScope.drawLoop(
    loop: LoopSpan,
    duration: Millis,
    colors: SeekBarColors,
) {
    val from = fraction(loop.start, duration) * size.width
    val to = fraction(loop.end ?: duration, duration) * size.width
    drawRect(colors.loop, topLeft = Offset(from, 0f), size = Size((to - from).coerceAtLeast(PlayerDimens.playhead.toPx()), size.height))
}

private fun DrawScope.drawSeekLine(
    played: Float,
    colors: SeekBarColors,
) {
    val height = PlayerDimens.seekLine.toPx()
    val top = (size.height - height) / 2f
    drawRoundRect(colors.unplayed, Offset(0f, top), Size(size.width, height), CornerRadius(height / 2f))
    drawRoundRect(colors.played, Offset(0f, top), Size(size.width * played, height), CornerRadius(height / 2f))
}

private fun DrawScope.drawPlayhead(
    played: Float,
    colors: SeekBarColors,
) {
    val width = PlayerDimens.playhead.toPx()
    val x = (size.width * played).coerceIn(width / 2f, maxOf(width / 2f, size.width - width / 2f))
    drawRoundRect(colors.playhead, Offset(x - width / 2f, 0f), Size(width, size.height), CornerRadius(width / 2f))
}

/**
 * The track's lyrics (#147), as the old player showed them: timed lyrics follow the music, the line
 * being sung picked out, and tapping a line plays from there; plain lyrics are shown whole. While the
 * user scrolls they stop following, and follow again a few seconds after. With no lyrics it says
 * where they can come from.
 */
@Composable
internal fun LyricsPanel(
    model: PlayerModel,
    onSeek: (Millis) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lyrics = model.lyrics
    if (lyrics == null) {
        NoLyrics(modifier)
        return
    }
    val lines =
        when (lyrics) {
            is Lyrics.Synced -> lyrics.lines.map { it.text to it.at }
            is Lyrics.Plain -> lyrics.lines.map { it to null }
        }
    val current = model.lyricLine
    val listState = rememberLazyListState()
    val follows = rememberFollowsMusic(listState)
    LaunchedEffect(current, follows.value) {
        if (follows.value && current >= 0) listState.animateScrollToItem(current, scrollOffset = -LYRICS_LEAD_PX)
    }
    LazyColumn(
        modifier,
        state = listState,
        contentPadding = PaddingValues(horizontal = PlayerDimens.gutter, vertical = PlayerDimens.lyricsPaddingY),
        verticalArrangement = Arrangement.spacedBy(PlayerDimens.lyricsGap),
    ) {
        itemsIndexed(lines) { index, (text, at) ->
            LyricLine(text, index, current, at, onSeek = { position ->
                follows.value = true
                onSeek(position)
            })
        }
        item { LyricsSourceNote(lyrics) }
    }
}

@Composable
private fun LyricLine(
    text: String,
    index: Int,
    current: Int,
    at: Millis?,
    onSeek: (Millis) -> Unit,
) {
    val sung = index == current
    val onSurface = MaterialTheme.colorScheme.onSurface
    Text(
        text,
        Modifier.fillMaxWidth().then(if (at != null) Modifier.clickable { onSeek(at) } else Modifier),
        style =
            if (sung) {
                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            } else {
                MaterialTheme.typography.bodyMedium
            },
        color =
            when {
                sung -> MaterialTheme.colorScheme.primary
                at == null -> onSurface.copy(alpha = PLAIN_ALPHA)
                index < current -> onSurface.copy(alpha = SUNG_ALPHA)
                else -> onSurface.copy(alpha = COMING_ALPHA)
            },
    )
}

@Composable
private fun LyricsSourceNote(lyrics: Lyrics) {
    val source =
        stringResource(
            when (lyrics.source) {
                LyricsSource.LRC_FILE -> R.string.playback_lyrics_from_lrc
                LyricsSource.TRACK_TAGS -> R.string.playback_lyrics_from_tags
            },
        )
    val note =
        when (lyrics) {
            is Lyrics.Synced -> stringResource(R.string.playback_lyrics_source_timed, source)
            is Lyrics.Plain -> stringResource(R.string.playback_lyrics_source_untimed, source)
        }
    Text(
        note,
        Modifier.padding(top = PlayerDimens.gap),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NoLyrics(modifier: Modifier) {
    Column(modifier.padding(PlayerDimens.gutter), verticalArrangement = Arrangement.spacedBy(PlayerDimens.small)) {
        Text(stringResource(R.string.playback_lyrics_none), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.playback_lyrics_none_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Whether the lyrics follow the music: not while the user drags them, and again a while after. App UI state only. */
@Composable
private fun rememberFollowsMusic(listState: LazyListState): MutableState<Boolean> {
    val follows = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collectLatest { interaction ->
            if (interaction is DragInteraction.Start) {
                follows.value = false
            } else {
                delay(LYRICS_FOLLOW_AGAIN.value)
                follows.value = true
            }
        }
    }
    return follows
}
