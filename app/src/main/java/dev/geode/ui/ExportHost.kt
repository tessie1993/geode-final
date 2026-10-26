package dev.geode.ui

import android.content.Intent
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.geode.R
import dev.geode.design.glass.GlassButton
import dev.geode.design.glass.GlassChip
import dev.geode.design.glass.GlassKind
import dev.geode.design.glass.GlassSectionHeader
import dev.geode.design.glass.GlassSlider
import dev.geode.design.glass.GlassSpacing
import dev.geode.design.glass.glassSurface
import dev.geode.export.ClipEdit
import dev.geode.export.ClipLook
import dev.geode.export.CubeLut
import dev.geode.export.ExportAspect
import dev.geode.export.ExportQuality
import dev.geode.export.ExportRatio
import dev.geode.export.StudioClip
import dev.geode.ui.studio.TimelineEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The old Studio's export entry, kept until Studio's own export lands (#17): edit a picked video in
 * the clip editor, or open the full timeline editor. The clip library itself is gone, replaced by the
 * Studio feature's Projects and videos.
 */
@Composable
fun ExportRoute(viewModel: StudioViewModel = geodeViewModel()) {
    val studio by viewModel.studio.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    var timelineOpen by rememberSaveable { mutableStateOf(false) }
    var editingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<StudioClip?>(null) }

    if (timelineOpen) {
        TimelineEditor(state = editor, exportPhase = studio.phase, actions = viewModel, onClose = { timelineOpen = false })
        return
    }
    LaunchedEffect(editingUri) {
        val wanted: String? = editingUri
        when {
            wanted == null -> editing = null
            editing?.uri != wanted -> viewModel.describeStudioClip(Uri.parse(wanted)) { editing = it }
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.describeStudioClip(uri) {
                    editing = it
                    editingUri = it.uri
                }
            }
        }

    val clip = editing
    if (clip == null) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassSectionHeader(stringResource(R.string.app_name))
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.studio_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(onClick = { picker.launch(arrayOf("video/*")) }) { Text(stringResource(R.string.studio_open_video)) }
                GlassButton(filled = false, onClick = { timelineOpen = true }) { Text(stringResource(R.string.editor_open)) }
            }
        }
    } else {
        ClipEditor(
            clip = clip,
            studio = studio,
            onExport = { c, e -> viewModel.startStudioExport(c, e) },
            onExportToDestination = { c, e, destination -> viewModel.startStudioExport(c, e, destination) },
            onCancelExport = viewModel::cancelStudioExport,
            onClearResult = viewModel::clearStudioResult,
            onClose = {
                viewModel.clearStudioResult()
                editingUri = null
            },
        )
    }
}

@UnstableApi
@Composable
private fun ClipPreview(
    clip: StudioClip,
    edit: ClipEdit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var playing by remember(clip.uri) { mutableStateOf(true) }
    val player =
        remember(clip.uri) {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                playWhenReady = true
            }
        }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player, edit) {
        delay(REBUILD_SETTLE_MS)
        val lut = edit.lutUri?.let { uri -> withContext(Dispatchers.IO) { CubeLut.load(context, uri) } }
        player.setVideoEffects(edit.videoEffects(lut))
        player.setMediaItems(
            listOf(
                MediaItem
                    .Builder()
                    .setUri(clip.uri)
                    .setClippingConfiguration(edit.clipping())
                    .build(),
            ),
        )
        player.playbackParameters = PlaybackParameters(edit.speed)
        player.prepare()
    }
    LaunchedEffect(player, playing) {
        player.playWhenReady = playing
    }

    val aspect =
        edit.ratio?.let { r ->
            ExportAspect.of(edit.quality, r).let { it.width.toFloat() / it.height }
        } ?: if (clip.width > 0 && clip.height > 0) {
            clip.width.toFloat() / clip.height
        } else {
            16f / 9f
        }
    val pauseHint = stringResource(R.string.studio_preview_toggle)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.3f, 3.4f))
            .clip(
                androidx.compose.foundation.shape
                    .RoundedCornerShape(8.dp),
            ).background(Color.Black)
            .clickable { playing = !playing },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { player.setVideoSurfaceView(it) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!playing) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = pauseHint,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.zIndex(1f).size(44.dp),
            )
        }
    }
}

// ----------------------------------------------------------------- clip editor

@Composable
private fun ClipEditor(
    clip: StudioClip,
    studio: StudioUiState,
    onExport: (StudioClip, ClipEdit) -> Unit,
    onExportToDestination: (StudioClip, ClipEdit, Uri) -> Unit,
    onCancelExport: () -> Unit,
    onClearResult: () -> Unit,
    onClose: () -> Unit,
) {
    var edit by remember(clip.uri) { mutableStateOf(ClipEdit()) }
    val duration = clip.durationMs.coerceAtLeast(1L)
    val dismiss = rememberPredictiveDismiss(onDismiss = onClose)
    val destinationPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
            if (uri != null) onExportToDestination(clip, edit, uri)
        }

    LazyColumn(
        Modifier.fillMaxSize().dismissTransform(dismiss).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            ClipEditorHeader(
                clip = clip,
                resettable = !edit.isIdentity(duration),
                onReset = { edit = ClipEdit() },
                onClose = onClose,
            )
        }
        item { ClipEditorPreview(clip = clip, edit = edit) }
        item { ClipCutSection(clip = clip, edit = edit, duration = duration, onEdit = { edit = it }) }
        item { ClipLookSection(edit = edit, onEdit = { edit = it }) }
        item { ClipFrameSection(edit = edit, onEdit = { edit = it }) }
        item { ClipSoundSection(edit = edit, onEdit = { edit = it }) }
        item {
            ClipRenderSection(
                phase = studio.phase,
                canExport = edit.trimmedMs(duration) > 0,
                atDefaults = edit.isIdentity(duration),
                onExport = { onExport(clip, edit) },
                onExportToDestination = {
                    destinationPicker.launch("geode_studio_${System.currentTimeMillis()}.mp4")
                },
                onCancelExport = onCancelExport,
                onClearResult = onClearResult,
            )
        }
    }
}

@Composable
private fun ClipEditorHeader(
    clip: StudioClip,
    resettable: Boolean,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(clip.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                clip.summary(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (resettable) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.studio_reset)) }
        }
        TextButton(onClick = onClose) { Text(stringResource(R.string.action_back)) }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun ClipEditorPreview(
    clip: StudioClip,
    edit: ClipEdit,
) {
    ClipPreview(clip, edit)
    Text(
        stringResource(R.string.studio_preview_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ClipCutSection(
    clip: StudioClip,
    edit: ClipEdit,
    duration: Long,
    onEdit: (ClipEdit) -> Unit,
) {
    val outEnd = if (edit.endMs > 0) edit.endMs else duration
    StudioSection(stringResource(R.string.studio_section_cut)) {
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(FILMSTRIP_FRAMES) { i ->
                VideoFrame(
                    clip.uri,
                    atMs = duration * i / FILMSTRIP_FRAMES,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    corner = 4.dp,
                )
            }
        }
        RangeSlider(
            value = edit.startMs.toFloat()..outEnd.toFloat(),
            onValueChange = { range ->
                onEdit(
                    edit.copy(
                        startMs = range.start.toLong().coerceIn(0L, duration),
                        endMs = if (range.endInclusive >= duration - 1) 0L else range.endInclusive.toLong(),
                    ),
                )
            },
            valueRange = 0f..duration.toFloat(),
        )
        ClipTrimSummary(edit = edit, duration = duration, outEnd = outEnd)
    }
}

@Composable
private fun ClipTrimSummary(
    edit: ClipEdit,
    duration: Long,
    outEnd: Long,
) {
    Text(
        stringResource(
            R.string.studio_trim_summary,
            clock(edit.startMs),
            clock(outEnd),
            clock(edit.trimmedMs(duration)),
        ) +
            if (edit.speed != 1f) {
                stringResource(R.string.studio_trim_renders, clock(edit.outputMs(duration)))
            } else {
                ""
            },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ClipLookSection(
    edit: ClipEdit,
    onEdit: (ClipEdit) -> Unit,
) {
    val context = LocalContext.current
    val lutPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                onEdit(edit.copy(lutUri = uri.toString()))
            }
        }
    StudioSection(stringResource(R.string.studio_section_look)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClipLook.entries.forEach { look ->
                StudioChip(look.label, selected = edit.look == look) {
                    onEdit(look.applyTo(edit).copy(look = look))
                }
            }
        }
        StudioSlider(stringResource(R.string.studio_brightness), edit.brightness, -0.5f..0.5f) {
            onEdit(edit.copy(brightness = it))
        }
        StudioSlider(stringResource(R.string.studio_contrast), edit.contrast, -0.6f..0.6f) {
            onEdit(edit.copy(contrast = it))
        }
        StudioSlider(
            stringResource(R.string.studio_saturation),
            edit.saturation,
            -100f..100f,
            unit = "%",
        ) { onEdit(edit.copy(saturation = it)) }
        StudioSlider(
            stringResource(R.string.studio_hue_shift),
            edit.hueDegrees,
            -180f..180f,
            unit = "°",
        ) { onEdit(edit.copy(hueDegrees = it)) }
        Text(
            stringResource(R.string.studio_look_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassButton(filled = false, onClick = { lutPicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.studio_lut_pick)) }
            if (edit.lutUri != null) {
                TextButton(onClick = { onEdit(edit.copy(lutUri = null)) }) { Text(stringResource(R.string.studio_lut_clear)) }
            }
        }
        Text(
            edit.lutUri?.let { stringResource(R.string.studio_lut_loaded, it.substringAfterLast('/').substringAfterLast(':')) }
                ?: stringResource(R.string.studio_lut_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StudioSlider(stringResource(R.string.studio_gamma_red), edit.gammaRed, GAMMA_RANGE, decimals = 2) {
            onEdit(edit.copy(gammaRed = it))
        }
        StudioSlider(stringResource(R.string.studio_gamma_green), edit.gammaGreen, GAMMA_RANGE, decimals = 2) {
            onEdit(edit.copy(gammaGreen = it))
        }
        StudioSlider(stringResource(R.string.studio_gamma_blue), edit.gammaBlue, GAMMA_RANGE, decimals = 2) {
            onEdit(edit.copy(gammaBlue = it))
        }
    }
}

private val GAMMA_RANGE = 0.5f..2f

@Composable
private fun ClipFrameSection(
    edit: ClipEdit,
    onEdit: (ClipEdit) -> Unit,
) {
    StudioSection(stringResource(R.string.studio_section_frame)) {
        StudioSlider(stringResource(R.string.studio_speed), edit.speed, 0.25f..4f, unit = "×", decimals = 2) {
            onEdit(edit.copy(speed = it))
        }
        StudioSlider(stringResource(R.string.studio_rotate), edit.rotationDegrees, -180f..180f, unit = "°") {
            onEdit(edit.copy(rotationDegrees = it))
        }
        Text(stringResource(R.string.studio_reframe), style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StudioChip(stringResource(R.string.studio_as_shot), selected = edit.ratio == null) { onEdit(edit.copy(ratio = null)) }
            ExportRatio.entries.forEach { r ->
                StudioChip(r.label, selected = edit.ratio == r) { onEdit(edit.copy(ratio = r)) }
            }
        }
        if (edit.ratio != null) {
            ClipQualityPicker(edit = edit, onEdit = onEdit)
        }
    }
}

@Composable
private fun ClipQualityPicker(
    edit: ClipEdit,
    onEdit: (ClipEdit) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExportQuality.entries.forEach { q ->
            StudioChip("${q.shortSide}p", selected = edit.quality == q) { onEdit(edit.copy(quality = q)) }
        }
    }
    Text(
        stringResource(R.string.studio_reframe_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ClipSoundSection(
    edit: ClipEdit,
    onEdit: (ClipEdit) -> Unit,
) {
    StudioSection(stringResource(R.string.studio_section_sound)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.studio_mute), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = edit.mute, onCheckedChange = { onEdit(edit.copy(mute = it)) })
        }
        OutlinedTextField(
            value = edit.caption,
            onValueChange = { onEdit(edit.copy(caption = it)) },
            label = { Text(stringResource(R.string.studio_caption)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.studio_caption_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClipRenderSection(
    phase: ExportPhase,
    canExport: Boolean,
    atDefaults: Boolean,
    onExport: () -> Unit,
    onExportToDestination: () -> Unit,
    onCancelExport: () -> Unit,
    onClearResult: () -> Unit,
) {
    StudioSection(stringResource(R.string.studio_section_render)) {
        when (phase) {
            is ExportPhase.Running -> ClipEditorRunning(phase.progress, onCancelExport)
            is ExportPhase.Done -> ClipEditorDone(phase.resultUri, onClearResult)
            ExportPhase.Idle, ExportPhase.Loading, is ExportPhase.Failed ->
                ClipEditorIdle(
                    message = phase.errorOrNull,
                    canExport = canExport,
                    atDefaults = atDefaults,
                    onExport = onExport,
                    onExportToDestination = onExportToDestination,
                )
        }
    }
}

@Composable
private fun ClipEditorRunning(
    progress: Float,
    onCancel: () -> Unit,
) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.studio_rendering, (progress * 100).roundToInt()),
        style = MaterialTheme.typography.labelMedium,
    )
    GlassButton(filled = false, onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
}

@Composable
private fun ClipEditorDone(
    resultUri: Uri,
    onClearResult: () -> Unit,
) {
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.studio_share_chooser)
    Text(stringResource(R.string.studio_saved), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassButton(
            onClick = { context.shareVideo(resultUri, chooserTitle) },
        ) { Text(stringResource(R.string.studio_send_ellipsis)) }
        GlassButton(
            filled = false,
            onClick = { context.viewVideo(resultUri) },
        ) { Text(stringResource(R.string.studio_play)) }
        TextButton(onClick = onClearResult) { Text(stringResource(R.string.studio_edit_again)) }
    }
    Text(
        stringResource(R.string.studio_send_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ClipEditorIdle(
    message: String?,
    canExport: Boolean,
    atDefaults: Boolean,
    onExport: () -> Unit,
    onExportToDestination: () -> Unit,
) {
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (atDefaults) {
        Text(
            stringResource(R.string.studio_nothing_changed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassButton(enabled = canExport, onClick = onExport) {
            Text(stringResource(R.string.studio_render))
        }
        TextButton(enabled = canExport, onClick = onExportToDestination) {
            Text(stringResource(R.string.export_render_to_folder))
        }
    }
    Text(
        stringResource(R.string.studio_renders_new_file),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val FILMSTRIP_FRAMES = 6

@Composable
private fun StudioSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .glassSurface(GlassKind.CARD, MaterialTheme.shapes.large)
            .padding(GlassSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(GlassSpacing.Medium),
    ) {
        GlassSectionHeader(title)
        content()
    }
}

@Composable
private fun StudioSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = "",
    decimals: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.studio_slider_value, label, "%.${decimals}f".format(value), unit),
            style = MaterialTheme.typography.labelMedium,
        )
        GlassSlider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun StudioChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    GlassChip(selected = selected, onClick = onClick, label = label)
}

private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

private fun android.content.Context.shareVideo(
    uri: Uri,
    chooserTitle: String,
) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(Intent.createChooser(send, chooserTitle)) }
}

private fun android.content.Context.viewVideo(uri: Uri) {
    val view =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(view) }
}

private const val REBUILD_SETTLE_MS = 250L
