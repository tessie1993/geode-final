package dev.geode.feature.studio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.geode.design.glass.GlassButton
import dev.geode.design.glass.GlassSpacing
import dev.geode.design.glass.rememberPressing
import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.edit.AutoCut
import dev.geode.feature.studio.api.edit.ClipContent
import dev.geode.feature.studio.api.edit.ClipId
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.api.edit.KeyframeId
import dev.geode.feature.studio.api.edit.KeyframeTrack
import dev.geode.feature.studio.api.edit.LaneKind
import dev.geode.feature.studio.api.edit.Marker
import dev.geode.feature.studio.api.edit.MarkerId
import dev.geode.feature.studio.api.edit.Subtitles
import dev.geode.feature.studio.api.edit.TapInSession
import dev.geode.feature.studio.api.edit.TapResult
import dev.geode.feature.studio.api.edit.predecessorOf

/**
 * The Timeline (#318), moved from the old Studio into Studio's feature: lanes of footage, generated
 * Visual, text, overlays and sound, with transitions between clips, markers tapped in against the
 * music, auto-cut, captions from lyrics or subtitles, and clip values animated by keyframes. The cut is
 * Studio's; this screen only draws it and sends [EditCommand]s. What is selected, the zoom and the open
 * sheet are App UI state kept here. [notice] says how the last subtitle or chapter file went.
 */
@Composable
fun TimelineScreen(
    model: TimelineModel,
    onAction: (EditAction) -> Unit,
    modifier: Modifier = Modifier,
    tools: EditorTools = NoEditorTools,
    notice: String? = null,
) {
    val project = model.project
    val send: (EditCommand) -> Unit = { onAction(EditAction.Send(it)) }
    var pxPerMs by rememberSaveable { mutableFloatStateOf(TimelineScale.DEFAULT_PX_PER_MS) }
    var selectedClip by remember { mutableStateOf<ClipId?>(null) }
    var selectedMarker by remember { mutableStateOf<MarkerId?>(null) }
    var selectedKey by remember { mutableStateOf<KeyframeId?>(null) }
    var sheet by remember { mutableStateOf<TimelineSheet?>(null) }
    var tapSession by remember { mutableStateOf<TapInSession?>(null) }
    val scale = TimelineScale(pxPerMs, maxOf(project.timeline.durationMs, MIN_CONTENT_MS) + CONTENT_MARGIN_MS)
    val laneNames = LANE_KINDS.associateWith { stringResource(laneKindLabel(it)) }
    val newLaneName = { kind: LaneKind -> "${laneNames[kind]} ${project.timeline.lanes.count { it.kind == kind } + 1}" }

    Column(
        modifier.fillMaxSize().padding(horizontal = GlassSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
    ) {
        TimelineHeader(
            model,
            send,
            onZoom = { pxPerMs = (pxPerMs * it).coerceIn(TimelineScale.MIN_PX_PER_MS, TimelineScale.MAX_PX_PER_MS) },
        )
        ExportRows(model.exports)
        TimelineToolbar(
            tapSession = tapSession,
            hasLyrics = tools.lyricCues() != null,
            onAddLane = { kind -> send(EditCommand.AddLane(kind, newLaneName(kind))) },
            onAddMarker = { send(EditCommand.AddMarkers(listOf(Marker(MarkerId(""), model.playheadMs)))) },
            onTapStart = { tapSession = TapInSession() },
            onTap = { tapSession = tapSession?.let { tapped(it, tools.playbackPositionMs() ?: model.playheadMs) } },
            onTapUndo = { tapSession = tapSession?.undoLast() },
            onTapDone = {
                tapSession?.taps?.takeIf { it.isNotEmpty() }?.let { send(EditCommand.AddMarkers(it)) }
                tapSession = null
            },
            onTapCancel = { tapSession = null },
            onAutoCut = { sheet = TimelineSheet.Hits },
            onLyricCaptions = {
                val cues = tools.lyricCues().orEmpty()
                val clips = Subtitles.clipsFrom(cues) { ClipId("") }
                send(EditCommand.AddClips(LaneKind.Text, newLaneName(LaneKind.Text), clips, ownLane = true))
            },
            onAction = onAction,
            onChapters = { sheet = TimelineSheet.Chapters },
        )
        SelectionToolbar(
            project = project,
            playheadMs = model.playheadMs,
            clip = selectedClip,
            marker = selectedMarker,
            key = selectedKey,
            send = send,
            onSheet = { sheet = it },
            onCleared = {
                selectedClip = null
                selectedMarker = null
                selectedKey = null
            },
        )
        SelectedKey(project, selectedKey, send)
        model.refused?.let {
            Text(
                editErrorMessage(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        notice?.let { EditNote(it) }
        if (project.timeline.lanes.isEmpty()) EditNote(stringResource(R.string.edit_empty))
        Lanes(
            project = project,
            scale = scale,
            playheadMs = model.playheadMs,
            keyTracks = project.keyframes.tracksFor(null) + (selectedClip?.let(project.keyframes::tracksFor) ?: emptyList()),
            selection =
                LaneSelection(
                    selectedClip,
                    selectedMarker,
                    selectedKey,
                    onClip = { selectedClip = it },
                    onMarker = { selectedMarker = it },
                    onKey = { selectedKey = it },
                ),
            onAction = onAction,
            onAddClip = { lane ->
                when (lane.kind) {
                    LaneKind.Visual -> send(EditCommand.AddClip(lane.id, ClipContent.Scene(tools.currentStyle()), SCENE_MS))
                    LaneKind.Media -> onAction(EditAction.PickMedia(lane.id, MediaPick.VIDEO))
                    LaneKind.Text -> sheet = TimelineSheet.Text(lane.id)
                    LaneKind.Overlay -> onAction(EditAction.PickMedia(lane.id, MediaPick.OVERLAY))
                    LaneKind.Audio -> onAction(EditAction.PickMedia(lane.id, MediaPick.AUDIO))
                }
            },
            onAddStill = { lane -> onAction(EditAction.PickMedia(lane.id, MediaPick.STILL)) },
        )
    }
    TimelineSheetHost(sheet, project, tools, send, onAction, newLaneName) { sheet = null }
}

/** A tap made with the music at [atMs]; a bounce inside the debounce window places nothing. */
private fun tapped(
    session: TapInSession,
    atMs: Long,
): TapInSession =
    when (val result = session.tap(MarkerId("tap-${session.count + 1}"), atMs)) {
        is TapResult.Placed -> result.session
        is TapResult.Debounced -> result.session
    }

@Composable
private fun TimelineSheetHost(
    sheet: TimelineSheet?,
    project: EditorProject,
    tools: EditorTools,
    send: (EditCommand) -> Unit,
    onAction: (EditAction) -> Unit,
    newLaneName: (LaneKind) -> String,
    onDone: () -> Unit,
) {
    when (sheet) {
        null -> Unit
        TimelineSheet.Hits ->
            AutoCutSheet(
                envelopeFor = tools::transientEnvelope,
                onMarkers = { hits ->
                    send(EditCommand.AddMarkers(AutoCut.markersFrom(hits, idFor = { MarkerId("") })))
                    onDone()
                },
                onClips = { hits, untilMs ->
                    val style = tools.currentStyle()
                    val clips = AutoCut.clipsFrom(hits, untilMs, { ClipId("") }) { _, _, _ -> ClipContent.Scene(style) }
                    send(EditCommand.AddClips(LaneKind.Visual, newLaneName(LaneKind.Visual), clips, ownLane = false))
                    onDone()
                },
                onDismiss = onDone,
            )
        TimelineSheet.Chapters ->
            ChapterFileDialog(
                onPick = { file ->
                    onDone()
                    onAction(EditAction.ExportChapters(file))
                },
                onDismiss = onDone,
            )
        is TimelineSheet.Text ->
            TextClipDialog(
                onConfirm = { text ->
                    send(EditCommand.AddClip(sheet.lane, ClipContent.Text(text), TEXT_MS))
                    onDone()
                },
                onDismiss = onDone,
            )
        is TimelineSheet.Transition ->
            TransitionSheet(
                names = tools.transitions,
                current = project.timeline.clip(sheet.clip)?.transition,
                onPick = { picked ->
                    send(EditCommand.SetTransition(sheet.clip, picked))
                    onDone()
                },
                onDismiss = onDone,
            )
        is TimelineSheet.Track ->
            AddTrackSheet(
                onPick = { param ->
                    send(EditCommand.AddTrack(param, sheet.clip))
                    onDone()
                },
                onDismiss = onDone,
            )
    }
}

/** Back, the playhead, undo and redo, zoom, and Export. */
@Composable
private fun TimelineHeader(
    model: TimelineModel,
    send: (EditCommand) -> Unit,
    onZoom: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GlassSpacing.ExtraSmall),
    ) {
        IconButton(
            onClick =
                rememberPressing {
                    send(EditCommand.Close)
                },
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.edit_back)) }
        Text(stringResource(R.string.edit_playhead, clockLabel(model.playheadMs)), style = MaterialTheme.typography.labelMedium)
        SmallButton(stringResource(R.string.edit_undo), enabled = model.canUndo) { send(EditCommand.Undo) }
        SmallButton(stringResource(R.string.edit_redo), enabled = model.canRedo) { send(EditCommand.Redo) }
        SmallButton(stringResource(R.string.edit_zoom_out)) { onZoom(1f / ZOOM_STEP) }
        SmallButton(stringResource(R.string.edit_zoom_in)) { onZoom(ZOOM_STEP) }
        GlassButton(compact = true, onClick = { send(EditCommand.Export()) }) { Text(stringResource(R.string.edit_export)) }
    }
}

/** Lanes, markers, tap-in, auto-cut, captions and the sidecar files. */
@Composable
private fun TimelineToolbar(
    tapSession: TapInSession?,
    hasLyrics: Boolean,
    onAddLane: (LaneKind) -> Unit,
    onAddMarker: () -> Unit,
    onTapStart: () -> Unit,
    onTap: () -> Unit,
    onTapUndo: () -> Unit,
    onTapDone: () -> Unit,
    onTapCancel: () -> Unit,
    onAutoCut: () -> Unit,
    onLyricCaptions: () -> Unit,
    onAction: (EditAction) -> Unit,
    onChapters: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
        if (tapSession == null) {
            LANE_KINDS.forEach { kind ->
                SmallButton(stringResource(R.string.edit_add_lane, stringResource(laneKindLabel(kind)))) { onAddLane(kind) }
            }
            SmallButton(stringResource(R.string.edit_add_marker), onClick = onAddMarker)
            SmallButton(stringResource(R.string.edit_tap_in), onClick = onTapStart)
            SmallButton(stringResource(R.string.edit_auto_cut), onClick = onAutoCut)
            if (hasLyrics) SmallButton(stringResource(R.string.edit_lyric_captions), onClick = onLyricCaptions)
            SmallButton(stringResource(R.string.edit_import_srt)) { onAction(EditAction.ImportSrt) }
            SmallButton(stringResource(R.string.edit_export_srt)) { onAction(EditAction.ExportSrt) }
            SmallButton(stringResource(R.string.edit_export_chapters), onClick = onChapters)
        } else {
            GlassButton(compact = true, onClick = onTap) { Text(stringResource(R.string.edit_tap)) }
            Text(
                stringResource(R.string.edit_tap_count, tapSession.count),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            SmallButton(stringResource(R.string.edit_tap_undo), enabled = tapSession.count > 0, onClick = onTapUndo)
            SmallButton(stringResource(R.string.edit_tap_done), onClick = onTapDone)
            SmallButton(stringResource(R.string.edit_cancel), onClick = onTapCancel)
        }
    }
}

/** What can be done to the selected clip, marker or key, and the way to animate the whole cut. */
@Composable
private fun SelectionToolbar(
    project: EditorProject,
    playheadMs: Long,
    clip: ClipId?,
    marker: MarkerId?,
    key: KeyframeId?,
    send: (EditCommand) -> Unit,
    onSheet: (TimelineSheet) -> Unit,
    onCleared: () -> Unit,
) {
    val selected = clip?.let(project.timeline::clip)
    val lane = selected?.let { project.timeline.laneOf(it.id) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
        SmallButton(stringResource(R.string.edit_animate_cut)) { onSheet(TimelineSheet.Track(null)) }
        if (selected != null && lane != null) {
            SmallButton(stringResource(R.string.edit_animate_clip)) { onSheet(TimelineSheet.Track(selected.id)) }
            if (lane.kind == LaneKind.Media && lane.predecessorOf(selected) != null) {
                SmallButton(stringResource(R.string.edit_transition_ellipsis)) { onSheet(TimelineSheet.Transition(selected.id)) }
            }
            SmallButton(stringResource(R.string.edit_split)) { send(EditCommand.SplitClip(selected.id, playheadMs)) }
            SmallButton(stringResource(R.string.edit_delete)) {
                send(EditCommand.DeleteClip(selected.id))
                onCleared()
            }
            SmallButton(stringResource(R.string.edit_ripple_delete)) {
                send(EditCommand.DeleteClip(selected.id, ripple = true))
                onCleared()
            }
            SmallButton(stringResource(R.string.edit_duplicate)) { send(EditCommand.DuplicateClip(selected.id)) }
            SmallButton(stringResource(if (selected.enabled) R.string.edit_disable else R.string.edit_enable)) {
                send(EditCommand.EnableClip(selected.id, !selected.enabled))
            }
        }
        if (marker != null) {
            SmallButton(stringResource(R.string.edit_delete_marker)) {
                send(EditCommand.DeleteMarker(marker))
                onCleared()
            }
        }
        if (key != null) {
            SmallButton(stringResource(R.string.edit_delete_key)) {
                send(EditCommand.DeleteKey(key))
                onCleared()
            }
        }
    }
}

/** The selected key's curve and value, while one is selected. */
@Composable
private fun SelectedKey(
    project: EditorProject,
    key: KeyframeId?,
    send: (EditCommand) -> Unit,
) {
    if (key == null) return
    val track: KeyframeTrack = project.keyframes.tracks.firstOrNull { it.key(key) != null } ?: return
    val shown = track.key(key) ?: return
    KeyEditor(shown, track.paramId) { changed -> send(EditCommand.ChangeKey(track.paramId, track.clipId, changed)) }
}

@Composable
private fun SmallButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    GlassButton(compact = true, filled = false, enabled = enabled, onClick = onClick) { Text(label) }
}

private const val ZOOM_STEP = 1.5f
private const val MIN_CONTENT_MS = 60_000L
private const val CONTENT_MARGIN_MS = 15_000L
private const val SCENE_MS = 4_000L
private const val TEXT_MS = 3_000L
