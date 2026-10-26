package dev.geode.feature.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.edit.Clip
import dev.geode.feature.studio.api.edit.ClipContent
import dev.geode.feature.studio.api.edit.ClipEdge
import dev.geode.feature.studio.api.edit.ClipId
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.api.edit.KeyframeId
import dev.geode.feature.studio.api.edit.KeyframeTrack
import dev.geode.feature.studio.api.edit.Lane
import dev.geode.feature.studio.api.edit.LaneKind
import dev.geode.feature.studio.api.edit.Marker
import dev.geode.feature.studio.api.edit.MarkerId
import dev.geode.feature.studio.api.edit.MarkerSet
import dev.geode.feature.studio.api.edit.SnapContext
import dev.geode.feature.studio.api.edit.SnapMode
import dev.geode.feature.studio.api.edit.SnapTarget
import dev.geode.feature.studio.api.edit.snap
import java.util.Locale
import kotlin.math.abs

/** Maps the Timeline's milliseconds to pixels inside its scrolling lanes. */
@Immutable
data class TimelineScale(
    val pxPerMs: Float,
    val contentMs: Long,
) {
    fun xOf(ms: Long): Float = ms * pxPerMs

    fun msOf(x: Float): Long = (x / pxPerMs).toLong().coerceIn(0L, contentMs)

    fun widthOf(ms: Long): Float = ms * pxPerMs

    val contentPx: Float get() = xOf(contentMs)

    /** How many milliseconds a finger-sized nudge covers; the snap radius follows the zoom. */
    fun msOf(
        dp: Dp,
        density: Float,
    ): Long = (dp.value * density / pxPerMs).toLong().coerceAtLeast(1L)

    companion object {
        const val MIN_PX_PER_MS = 0.005f
        const val MAX_PX_PER_MS = 1.2f
        const val DEFAULT_PX_PER_MS = 0.04f
    }
}

/** The Timeline's row heights, fixed so the header column and the lanes stay lined up. */
internal object TimelineDimens {
    val headerWidth: Dp = 76.dp
    val laneHeight: Dp = 52.dp
    val rulerHeight: Dp = 28.dp
    val markerLaneHeight: Dp = 30.dp
    val keyLaneHeight: Dp = 34.dp
    val gap: Dp = 8.dp
}

/** A time as `12.3s` under a minute, `m:ss` from then on. */
fun clockLabel(ms: Long): String {
    val totalSeconds = ms / MILLIS_PER_SECOND
    val tenths = (ms % MILLIS_PER_SECOND) / MILLIS_PER_TENTH
    return if (ms < MILLIS_PER_MINUTE) {
        String.format(Locale.ROOT, "%d.%ds", totalSeconds, tenths)
    } else {
        String.format(Locale.ROOT, "%d:%02d", totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_TENTH = 100L
private const val MILLIS_PER_MINUTE = 60_000L
private const val SECONDS_PER_MINUTE = 60L

/** The selection the Timeline's lanes show, and how they hand it back. */
internal class LaneSelection(
    val clip: ClipId?,
    val marker: MarkerId?,
    val key: KeyframeId?,
    val onClip: (ClipId?) -> Unit,
    val onMarker: (MarkerId?) -> Unit,
    val onKey: (KeyframeId?) -> Unit,
)

/** The header column beside the scrolling lanes: ruler, markers, each lane, and the selection's key tracks. */
@Composable
internal fun Lanes(
    project: EditorProject,
    scale: TimelineScale,
    playheadMs: Long,
    keyTracks: List<KeyframeTrack>,
    selection: LaneSelection,
    onAction: (EditAction) -> Unit,
    onAddClip: (Lane) -> Unit,
    onAddStill: (Lane) -> Unit,
) {
    val send: (EditCommand) -> Unit = { onAction(EditAction.Send(it)) }
    Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Column(Modifier.width(TimelineDimens.headerWidth)) {
            Spacer(Modifier.height(TimelineDimens.rulerHeight))
            Box(Modifier.height(TimelineDimens.markerLaneHeight), contentAlignment = Alignment.CenterStart) {
                Text(stringResource(R.string.edit_markers), style = MaterialTheme.typography.labelSmall)
            }
            project.timeline.lanes.forEach { lane ->
                LaneHeader(lane, send, onAddClip = { onAddClip(lane) }, onAddStill = { onAddStill(lane) })
            }
            keyTracks.forEach { track ->
                Box(Modifier.height(TimelineDimens.keyLaneHeight), contentAlignment = Alignment.CenterStart) {
                    Text(
                        paramLabel(track.paramId),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Column {
                TimeRuler(scale, onScrub = { send(EditCommand.MovePlayhead(it)) })
                LaneContentBox(scale, TimelineDimens.markerLaneHeight) {
                    MarkerLane(
                        markers = project.markers,
                        scale = scale,
                        selected = selection.marker,
                        snapContext = project.snapContext(playheadMs),
                        onSelect = selection.onMarker,
                        onAdd = { ms -> send(EditCommand.AddMarkers(listOf(Marker(MarkerId(""), ms)))) },
                        onMove = { id, ms -> send(EditCommand.MoveMarker(id, ms)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                project.timeline.lanes.forEach { lane ->
                    LaneContentBox(scale, TimelineDimens.laneHeight) {
                        ClipStrip(
                            lane = lane,
                            scale = scale,
                            selected = selection.clip,
                            snapContext = { project.snapContext(playheadMs, excludeClip = it) },
                            onSelect = selection.onClip,
                            onCommand = send,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                keyTracks.forEach { track ->
                    LaneContentBox(scale, TimelineDimens.keyLaneHeight) {
                        KeyframeLane(
                            track = track,
                            scale = scale,
                            selected = selection.key,
                            snapContext = project.snapContext(playheadMs),
                            onSelect = selection.onKey,
                            onAdd = { ms -> send(EditCommand.AddKey(track.paramId, track.clipId, ms)) },
                            onMove = { id, ms -> send(EditCommand.MoveKey(track.paramId, track.clipId, id, ms)) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Playhead(scale, playheadMs, Modifier.matchParentSize())
        }
    }
}

@Composable
private fun LaneHeader(
    lane: Lane,
    send: (EditCommand) -> Unit,
    onAddClip: () -> Unit,
    onAddStill: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.height(TimelineDimens.laneHeight), verticalArrangement = Arrangement.Center) {
        Text(lane.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(TimelineDimens.gap)) {
            HeaderToggle(stringResource(R.string.edit_mute_short), lane.muted) {
                send(EditCommand.SetLane(lane.id, muted = !lane.muted, locked = lane.locked))
            }
            HeaderToggle(stringResource(R.string.edit_lock_short), lane.locked) {
                send(EditCommand.SetLane(lane.id, muted = lane.muted, locked = !lane.locked))
            }
            Box {
                HeaderToggle(stringResource(R.string.edit_add_short), false) {
                    if (lane.kind == LaneKind.Media) menu = true else onAddClip()
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_add_video)) },
                        onClick = {
                            menu = false
                            onAddClip()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_add_still)) },
                        onClick = {
                            menu = false
                            onAddStill()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderToggle(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** The time ruler with its labels; a tap or a drag on it moves the playhead. */
@Composable
private fun TimeRuler(
    scale: TimelineScale,
    onScrub: (Long) -> Unit,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current.density
    Canvas(
        Modifier
            .width(with(LocalDensity.current) { scale.contentPx.toDp() })
            .height(TimelineDimens.rulerHeight)
            .pointerInput(scale) {
                detectTapGestures { offset -> onScrub(scale.msOf(offset.x)) }
            }.pointerInput(scale) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onScrub(scale.msOf(change.position.x))
                }
            },
    ) {
        val stepMs = rulerStepMs(scale)
        val textPaint =
            android.graphics.Paint().apply {
                color = labelColor.toArgb()
                textSize = RULER_TEXT * density
                isAntiAlias = true
            }
        var ms = 0L
        while (ms <= scale.contentMs) {
            val x = scale.xOf(ms)
            val major = ms % (stepMs * MAJOR_EVERY) == 0L
            drawLine(
                labelColor.copy(alpha = if (major) MAJOR_ALPHA else MINOR_ALPHA),
                Offset(x, size.height),
                Offset(x, size.height - if (major) MAJOR_TICK * density else MINOR_TICK * density),
                strokeWidth = 1f,
            )
            if (major) {
                drawContext.canvas.nativeCanvas.drawText(
                    clockLabel(ms),
                    x + LABEL_INSET * density,
                    RULER_BASELINE * density,
                    textPaint,
                )
            }
            ms += stepMs
        }
    }
}

/** A tick every 0.2 s up to every 5 minutes, whichever keeps the ticks at least 24 px apart. */
private fun rulerStepMs(scale: TimelineScale): Long = RULER_STEPS.firstOrNull { scale.widthOf(it) >= MIN_TICK_PX } ?: RULER_STEPS.last()

private val RULER_STEPS = listOf(200L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 300_000L)
private const val MIN_TICK_PX = 24f
private const val MAJOR_EVERY = 5L
private const val MAJOR_ALPHA = 0.9f
private const val MINOR_ALPHA = 0.4f
private const val MAJOR_TICK = 12f
private const val MINOR_TICK = 6f
private const val RULER_TEXT = 10f
private const val RULER_BASELINE = 11f
private const val LABEL_INSET = 3f

/** The playhead's line over the lanes at the current time. */
@Composable
private fun Playhead(
    scale: TimelineScale,
    playheadMs: Long,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().fillMaxHeight()) {
        val x = scale.xOf(playheadMs)
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = PLAYHEAD_WIDTH)
        drawLine(color, Offset(x - PLAYHEAD_CAP, 0f), Offset(x + PLAYHEAD_CAP, 0f), strokeWidth = PLAYHEAD_CAP_WIDTH)
    }
}

private const val PLAYHEAD_WIDTH = 2f
private const val PLAYHEAD_CAP = 6f
private const val PLAYHEAD_CAP_WIDTH = 4f

/** Pads a lane's content to the full scrolling width, so every lane scrolls together. */
@Composable
private fun LaneContentBox(
    scale: TimelineScale,
    height: Dp,
    content: @Composable () -> Unit,
) {
    Box(Modifier.width(with(LocalDensity.current) { scale.contentPx.toDp() }).height(height)) { content() }
}

/** What a drag on a clip is doing: sliding it or pulling one of its edges. */
private enum class DragMode {
    MOVE,
    TRIM_START,
    TRIM_END,
}

private data class ClipDrag(
    val clipId: ClipId,
    val mode: DragMode,
    val originStartMs: Long,
    val originEndMs: Long,
    val deltaMs: Long = 0L,
)

/**
 * One lane's clips. A tap selects, a double tap splits at the tap, a drag on the body moves and a drag
 * inside the edge zone trims; the drag shows as it goes and is sent, snapped, as one change.
 */
@Composable
private fun ClipStrip(
    lane: Lane,
    scale: TimelineScale,
    selected: ClipId?,
    snapContext: (ClipId) -> SnapContext,
    onSelect: (ClipId?) -> Unit,
    onCommand: (EditCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val edgeZonePx = EDGE_ZONE * density
    var drag by remember(lane.id) { mutableStateOf<ClipDrag?>(null) }
    val colors = ClipColors.fromTheme()

    fun clipAt(x: Float): Clip? = lane.clips.firstOrNull { x >= scale.xOf(it.startMs) && x < scale.xOf(it.endMs) }

    fun modeAt(
        clip: Clip,
        x: Float,
    ): DragMode =
        when {
            x - scale.xOf(clip.startMs) <= edgeZonePx -> DragMode.TRIM_START
            scale.xOf(clip.endMs) - x <= edgeZonePx -> DragMode.TRIM_END
            else -> DragMode.MOVE
        }

    Canvas(
        modifier
            .pointerInput(lane, scale) {
                detectTapGestures(
                    onTap = { offset -> onSelect(clipAt(offset.x)?.id) },
                    onDoubleTap = { offset -> clipAt(offset.x)?.let { onCommand(EditCommand.SplitClip(it.id, scale.msOf(offset.x))) } },
                )
            }.pointerInput(lane, scale) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val clip = clipAt(offset.x)
                        if (clip != null && !lane.locked) {
                            onSelect(clip.id)
                            drag = ClipDrag(clip.id, modeAt(clip, offset.x), clip.startMs, clip.endMs)
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag = drag?.let { it.copy(deltaMs = it.deltaMs + (amount.x / scale.pxPerMs).toLong()) }
                    },
                    onDragCancel = { drag = null },
                    onDragEnd = {
                        drag?.let { onCommand(dropped(it, snapModeFor(scale, density), snapContext(it.clipId))) }
                        drag = null
                    },
                )
            },
    ) {
        for (clip in lane.clips) {
            val (startMs, endMs) = previewSpan(clip, drag?.takeIf { it.clipId == clip.id })
            drawClip(clip, scale.xOf(startMs), scale.widthOf(endMs - startMs), clip.id == selected, lane.muted, colors, density)
        }
    }
}

private fun snapModeFor(
    scale: TimelineScale,
    density: Float,
): SnapMode = SnapMode.Magnetic(setOf(SnapTarget.PLAYHEAD, SnapTarget.MARKERS, SnapTarget.CLIP_EDGES), scale.msOf(SNAP_RADIUS, density))

/** The change a finished drag asks for, snapped. */
private fun dropped(
    drag: ClipDrag,
    snap: SnapMode,
    context: SnapContext,
): EditCommand =
    when (drag.mode) {
        DragMode.MOVE -> EditCommand.MoveClip(drag.clipId, snap.snap((drag.originStartMs + drag.deltaMs).coerceAtLeast(0L), context))
        DragMode.TRIM_START -> EditCommand.TrimClip(drag.clipId, ClipEdge.START, snap.snap(drag.originStartMs + drag.deltaMs, context))
        DragMode.TRIM_END -> EditCommand.TrimClip(drag.clipId, ClipEdge.END, snap.snap(drag.originEndMs + drag.deltaMs, context))
    }

private fun previewSpan(
    clip: Clip,
    drag: ClipDrag?,
): Pair<Long, Long> =
    when (drag?.mode) {
        null -> clip.startMs to clip.endMs
        DragMode.MOVE -> (drag.originStartMs + drag.deltaMs).coerceAtLeast(0L).let { it to it + clip.durationMs }
        DragMode.TRIM_START -> (drag.originStartMs + drag.deltaMs).coerceIn(0L, drag.originEndMs - 1) to drag.originEndMs
        DragMode.TRIM_END -> drag.originStartMs to (drag.originEndMs + drag.deltaMs).coerceAtLeast(drag.originStartMs + 1)
    }

private fun DrawScope.drawClip(
    clip: Clip,
    x: Float,
    width: Float,
    selected: Boolean,
    muted: Boolean,
    colors: ClipColors,
    density: Float,
) {
    val fill = colors.fillFor(clip.content).copy(alpha = if (clip.enabled && !muted) CLIP_ALPHA else CLIP_OFF_ALPHA)
    val top = CLIP_INSET * density
    val size = Size(width.coerceAtLeast(2f), this.size.height - top * 2)
    val corner = CornerRadius(CLIP_CORNER * density)
    drawRoundRect(fill, Offset(x, top), size, corner)
    if (clip.transition != null) {
        drawRect(colors.outline.copy(alpha = BADGE_ALPHA), Offset(x, top), Size(TRANSITION_BADGE * density, size.height))
    }
    if (selected) drawRoundRect(colors.outline, Offset(x, top), size, corner, style = Stroke(2f * density))
    if (width > MIN_LABEL_WIDTH * density) {
        val paint =
            android.graphics.Paint().apply {
                color = colors.label.toArgb()
                textSize = CLIP_TEXT * density
                isAntiAlias = true
            }
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.clipRect(x, top, x + width, top + size.height)
        canvas.drawText(
            clip.label.ifBlank { defaultLabel(clip.content) },
            x + LABEL_PAD * density,
            top + size.height / 2 + LABEL_DROP * density,
            paint,
        )
        canvas.restore()
    }
}

private fun defaultLabel(content: ClipContent): String =
    when (content) {
        is ClipContent.Scene -> content.sceneId
        is ClipContent.Video -> fileLabel(content.uri)
        is ClipContent.Still -> fileLabel(content.uri)
        is ClipContent.Text -> content.text
        is ClipContent.Overlay -> fileLabel(content.uri)
        is ClipContent.Audio -> fileLabel(content.uri)
    }

/** The last part of a content uri, readable enough to tell clips apart. */
internal fun fileLabel(uri: String): String = uri.substringAfterLast('/').substringAfterLast(':')

private class ClipColors(
    val scene: Color,
    val media: Color,
    val textFill: Color,
    val overlay: Color,
    val audio: Color,
    val outline: Color,
    val label: Color,
) {
    fun fillFor(content: ClipContent): Color =
        when (content) {
            is ClipContent.Scene -> scene
            is ClipContent.Video, is ClipContent.Still -> media
            is ClipContent.Text -> textFill
            is ClipContent.Overlay -> overlay
            is ClipContent.Audio -> audio
        }

    companion object {
        @Composable
        fun fromTheme(): ClipColors {
            val cs = MaterialTheme.colorScheme
            return ClipColors(
                scene = cs.primary,
                media = cs.tertiary,
                textFill = cs.secondary,
                overlay = cs.secondaryContainer,
                audio = cs.tertiaryContainer,
                outline = cs.onSurface,
                label = cs.onPrimary,
            )
        }
    }
}

private const val EDGE_ZONE = 14f
private const val TRANSITION_BADGE = 5f
private const val CLIP_ALPHA = 0.85f
private const val CLIP_OFF_ALPHA = 0.35f
private const val BADGE_ALPHA = 0.6f
private const val CLIP_INSET = 4f
private const val CLIP_CORNER = 6f
private const val CLIP_TEXT = 11f
private const val MIN_LABEL_WIDTH = 24f
private const val LABEL_PAD = 6f
private const val LABEL_DROP = 4f
private val SNAP_RADIUS = 10.dp

/** A drag of a marker or a key along its lane. */
private data class PointDrag<T>(
    val id: T,
    val originMs: Long,
    val deltaMs: Long = 0L,
) {
    val atMs: Long get() = (originMs + deltaMs).coerceAtLeast(0L)
}

/**
 * Every marker as a flag. A tap on empty lane adds one there, a tap on a flag selects it, and a drag
 * moves it, snapping to the playhead and clip edges but never to other markers.
 */
@Composable
private fun MarkerLane(
    markers: MarkerSet,
    scale: TimelineScale,
    selected: MarkerId?,
    snapContext: SnapContext,
    onSelect: (MarkerId?) -> Unit,
    onAdd: (Long) -> Unit,
    onMove: (MarkerId, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val hitPx = HIT * density
    var drag by remember { mutableStateOf<PointDrag<MarkerId>?>(null) }
    val outline = MaterialTheme.colorScheme.onSurface

    fun markerAt(x: Float): Marker? =
        markers.markers.minByOrNull { abs(scale.xOf(it.atMs) - x) }?.takeIf {
            abs(scale.xOf(it.atMs) - x) <=
                hitPx
        }

    Canvas(
        modifier
            .pointerInput(markers, scale) {
                detectTapGestures { offset ->
                    val hit = markerAt(offset.x)
                    if (hit == null) onAdd(scale.msOf(offset.x)) else onSelect(hit.id)
                }
            }.pointerInput(markers, scale) {
                detectDragGestures(
                    onDragStart = { offset ->
                        markerAt(offset.x)?.let { hit ->
                            onSelect(hit.id)
                            drag = PointDrag(hit.id, hit.atMs)
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag = drag?.let { it.copy(deltaMs = it.deltaMs + (amount.x / scale.pxPerMs).toLong()) }
                    },
                    onDragCancel = { drag = null },
                    onDragEnd = {
                        drag?.let {
                            val snap =
                                SnapMode.Magnetic(
                                    setOf(SnapTarget.PLAYHEAD, SnapTarget.CLIP_EDGES),
                                    scale.msOf(SNAP_RADIUS, density),
                                )
                            onMove(it.id, snap.snap(it.atMs, snapContext))
                        }
                        drag = null
                    },
                )
            },
    ) {
        for (marker in markers.markers) {
            val active = drag?.takeIf { it.id == marker.id }
            drawFlag(scale.xOf(active?.atMs ?: marker.atMs), Color(marker.colour.argb), marker.id == selected, outline, density)
        }
    }
}

private fun DrawScope.drawFlag(
    x: Float,
    color: Color,
    selected: Boolean,
    outline: Color,
    density: Float,
) {
    val flag =
        Path().apply {
            moveTo(x, 0f)
            lineTo(x + FLAG_WIDTH * density, FLAG_HEIGHT / 2 * density)
            lineTo(x, FLAG_HEIGHT * density)
            close()
        }
    drawPath(flag, color)
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = if (selected) SELECTED_LINE else LINE)
    if (selected) drawPath(flag, outline, style = Stroke(LINE * density))
}

private const val HIT = 12f
private const val FLAG_WIDTH = 9f
private const val FLAG_HEIGHT = 10f
private const val LINE = 1.5f
private const val SELECTED_LINE = 3f

/**
 * One track's keys as diamonds. A tap selects one, a tap on empty lane adds a key holding the track's
 * value there, and a drag slides a key, snapping.
 */
@Composable
private fun KeyframeLane(
    track: KeyframeTrack,
    scale: TimelineScale,
    selected: KeyframeId?,
    snapContext: SnapContext,
    onSelect: (KeyframeId?) -> Unit,
    onAdd: (Long) -> Unit,
    onMove: (KeyframeId, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val hitPx = HIT * density
    var drag by remember(track.paramId, track.clipId) { mutableStateOf<PointDrag<KeyframeId>?>(null) }
    val fill = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.onSurface
    val line = MaterialTheme.colorScheme.onSurfaceVariant

    fun keyAt(x: Float) = track.keys.minByOrNull { abs(scale.xOf(it.atMs) - x) }?.takeIf { abs(scale.xOf(it.atMs) - x) <= hitPx }

    Canvas(
        modifier
            .pointerInput(track, scale) {
                detectTapGestures { offset ->
                    val hit = keyAt(offset.x)
                    if (hit == null) onAdd(scale.msOf(offset.x)) else onSelect(hit.id)
                }
            }.pointerInput(track, scale) {
                detectDragGestures(
                    onDragStart = { offset ->
                        keyAt(offset.x)?.let { hit ->
                            onSelect(hit.id)
                            drag = PointDrag(hit.id, hit.atMs)
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag = drag?.let { it.copy(deltaMs = it.deltaMs + (amount.x / scale.pxPerMs).toLong()) }
                    },
                    onDragCancel = { drag = null },
                    onDragEnd = {
                        drag?.let { onMove(it.id, snapModeFor(scale, density).snap(it.atMs, snapContext)) }
                        drag = null
                    },
                )
            },
    ) {
        val midY = size.height / 2
        drawLine(line.copy(alpha = MINOR_ALPHA), Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)
        for (key in track.keys) {
            val x = scale.xOf(drag?.takeIf { it.id == key.id }?.atMs ?: key.atMs)
            val r = DIAMOND * density
            val diamond =
                Path().apply {
                    moveTo(x, midY - r)
                    lineTo(x + r, midY)
                    lineTo(x, midY + r)
                    lineTo(x - r, midY)
                    close()
                }
            drawPath(diamond, fill.copy(alpha = if (track.enabled) 1f else CLIP_OFF_ALPHA))
            if (key.id == selected) drawPath(diamond, outline, style = Stroke(2f * density))
        }
    }
}

private const val DIAMOND = 6f
