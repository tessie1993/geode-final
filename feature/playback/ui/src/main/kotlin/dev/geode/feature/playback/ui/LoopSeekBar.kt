package dev.geode.feature.playback.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
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
import dev.geode.design.element.LocalElementTokens
import dev.geode.engine.types.Millis

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
            val colors = LocalElementTokens.current.colors
            return SeekBarColors(
                played = colors.active,
                unplayed = colors.textSecondary.copy(alpha = UNPLAYED_ALPHA),
                loop = colors.accent.copy(alpha = LOOP_ALPHA),
                playhead = colors.textPrimary.copy(alpha = PLAYHEAD_ALPHA),
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
