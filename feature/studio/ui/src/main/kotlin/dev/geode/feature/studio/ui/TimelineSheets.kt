package dev.geode.feature.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.geode.design.glass.GlassButton
import dev.geode.design.glass.GlassChip
import dev.geode.design.glass.GlassDialog
import dev.geode.design.glass.GlassSheet
import dev.geode.design.glass.GlassSlider
import dev.geode.design.glass.GlassSpacing
import dev.geode.design.glass.GlassSwitchRow
import dev.geode.design.glass.rememberPressing
import dev.geode.feature.studio.api.edit.AutoCut
import dev.geode.feature.studio.api.edit.AutoCutMiss
import dev.geode.feature.studio.api.edit.AutoCutResult
import dev.geode.feature.studio.api.edit.AutoCutSettings
import dev.geode.feature.studio.api.edit.BezierCurve
import dev.geode.feature.studio.api.edit.ClipParams
import dev.geode.feature.studio.api.edit.ClipTransition
import dev.geode.feature.studio.api.edit.EaseShape
import dev.geode.feature.studio.api.edit.EditError
import dev.geode.feature.studio.api.edit.Interpolation
import dev.geode.feature.studio.api.edit.Keyframe
import dev.geode.feature.studio.api.edit.LaneKind
import dev.geode.feature.studio.api.edit.ParamId
import dev.geode.feature.studio.api.edit.ParamValue
import dev.geode.feature.studio.api.edit.TransientEnvelope
import dev.geode.feature.studio.api.edit.TransientHit
import dev.geode.feature.studio.api.edit.TransientSource
import java.util.Locale

/** What the Timeline calls a value a track animates. */
@Composable
internal fun paramLabel(id: ParamId): String = PARAM_LABELS[id.value]?.let { stringResource(it) } ?: id.value.substringAfterLast('.')

private val PARAM_LABELS: Map<String, Int> =
    mapOf(
        "clip.brightness" to R.string.edit_param_brightness,
        "clip.contrast" to R.string.edit_param_contrast,
        "clip.saturation" to R.string.edit_param_saturation,
        "clip.hueDegrees" to R.string.edit_param_hue,
        "clip.speed" to R.string.edit_param_speed,
        "clip.rotationDegrees" to R.string.edit_param_rotation,
    )

/** Why the last change to the cut was refused, in words. */
@Composable
internal fun editErrorMessage(error: EditError): String =
    stringResource(
        when (error) {
            is EditError.LaneNotFound, is EditError.ClipNotFound -> R.string.edit_err_not_found
            is EditError.LaneLocked -> R.string.edit_err_locked
            is EditError.WrongLaneKind -> R.string.edit_err_wrong_lane
            is EditError.Overlaps -> R.string.edit_err_overlaps
            is EditError.NeedsSplit -> R.string.edit_err_needs_split
            EditError.TooShort -> R.string.edit_err_too_short
            EditError.OutsideClip -> R.string.edit_err_outside_clip
        },
    )

/** A lane kind's name. */
internal fun laneKindLabel(kind: LaneKind): Int =
    when (kind) {
        LaneKind.Visual -> R.string.edit_lane_visual
        LaneKind.Media -> R.string.edit_lane_media
        LaneKind.Text -> R.string.edit_lane_text
        LaneKind.Overlay -> R.string.edit_lane_overlay
        LaneKind.Audio -> R.string.edit_lane_audio
    }

/** Every lane kind, in the order the Timeline offers them. */
internal val LANE_KINDS: List<LaneKind> = listOf(LaneKind.Visual, LaneKind.Media, LaneKind.Text, LaneKind.Overlay, LaneKind.Audio)

/** Picks the GL Transition a clip opens with, of [names], and how long it runs; None cuts. */
@Composable
internal fun TransitionSheet(
    names: List<String>,
    current: ClipTransition?,
    onPick: (ClipTransition?) -> Unit,
    onDismiss: () -> Unit,
) {
    var durationMs by remember { mutableLongStateOf(current?.boundedDurationMs ?: ClipTransition.DEFAULT_TRANSITION_MS) }
    GlassSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = SHEET_PADDING, vertical = GlassSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
        ) {
            Text(stringResource(R.string.edit_transition), style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
            ) {
                ClipTransition.DURATION_CHOICES_MS.forEach { choice ->
                    GlassChip(
                        selected = choice == durationMs,
                        onClick = { durationMs = choice },
                        label = stringResource(R.string.edit_transition_seconds, choice / MILLIS_PER_SECOND),
                    )
                }
            }
            TextButton(onClick = rememberPressing { onPick(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.edit_transition_none), modifier = Modifier.fillMaxWidth())
            }
            LazyColumn {
                items(names, key = { it }) { name ->
                    TextButton(
                        onClick = rememberPressing { onPick(ClipTransition(name, durationMs)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (name == current?.id) stringResource(R.string.edit_transition_current, name) else name,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000f
private val SHEET_PADDING = 20.dp

/** Picks the clip value a new track animates. */
@Composable
internal fun AddTrackSheet(
    onPick: (ParamId) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = SHEET_PADDING, vertical = GlassSpacing.Small)) {
            Text(stringResource(R.string.edit_curve_add_track), style = MaterialTheme.typography.titleMedium)
            LazyColumn {
                items(ClipParams.all, key = { it.id.value }) { param ->
                    TextButton(onClick = rememberPressing { onPick(param.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(paramLabel(param.id), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** The curve, handles and value of one key on [param]'s track. Every change is a new [Keyframe]. */
@Composable
internal fun KeyEditor(
    key: Keyframe,
    param: ParamId,
    onChange: (Keyframe) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
        Text(
            stringResource(R.string.edit_curve_key_at, paramLabel(param), clockLabel(key.atMs)),
            style = MaterialTheme.typography.labelMedium,
        )
        InterpolationChips(key.interpolation) { onChange(key.copy(interpolation = it)) }
        (key.interpolation as? Interpolation.Custom)?.let { custom ->
            BezierHandles(custom.curve) { onChange(key.copy(interpolation = Interpolation.Custom(it))) }
        }
        ValueEditor(key.value, param) { onChange(key.copy(value = it)) }
    }
}

@Composable
private fun InterpolationChips(
    current: Interpolation,
    onPick: (Interpolation) -> Unit,
) {
    val custom = (current as? Interpolation.Custom)?.curve ?: (current as? Interpolation.Ease)?.shape?.curve ?: EaseShape.IN_OUT.curve
    val choices: List<Pair<Int, Interpolation>> =
        listOf(
            R.string.edit_curve_hold to Interpolation.Hold,
            R.string.edit_curve_linear to Interpolation.Linear,
            R.string.edit_curve_ease_in to Interpolation.Ease(EaseShape.IN),
            R.string.edit_curve_ease_out to Interpolation.Ease(EaseShape.OUT),
            R.string.edit_curve_ease_in_out to Interpolation.Ease(EaseShape.IN_OUT),
            R.string.edit_curve_smooth to Interpolation.Ease(EaseShape.SMOOTH),
            R.string.edit_curve_custom to Interpolation.Custom(custom),
        )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
        choices.forEach { (label, interpolation) ->
            val selected = if (interpolation is Interpolation.Custom) current is Interpolation.Custom else interpolation == current
            GlassChip(selected = selected, onClick = { onPick(interpolation) }, label = stringResource(label))
        }
    }
}

/** The bezier's two handles, to drag; the curve is drawn with the same [BezierCurve.eval] the export uses. */
@Composable
private fun BezierHandles(
    curve: BezierCurve,
    onCurve: (BezierCurve) -> Unit,
) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val handle = MaterialTheme.colorScheme.secondary
    Canvas(
        Modifier
            .size(CURVE_SIZE)
            .padding(GlassSpacing.ExtraSmall)
            .pointerInput(curve) {
                var first = true
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val one = Offset(curve.c1x * w, (1f - curve.c1y) * h)
                        val two = Offset(curve.c2x * w, (1f - curve.c2y) * h)
                        first = (offset - one).getDistance() <= (offset - two).getDistance()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val x = (change.position.x / size.width).coerceIn(0f, 1f)
                        val y = (1f - change.position.y / size.height).coerceIn(HANDLE_LOW, HANDLE_HIGH)
                        onCurve(if (first) curve.copy(c1x = x, c1y = y) else curve.copy(c2x = x, c2y = y))
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        drawLine(grid.copy(alpha = GRID_ALPHA), Offset(0f, h), Offset(w, 0f), strokeWidth = 1f)
        val path =
            Path().apply {
                moveTo(0f, h)
                for (i in 1..CURVE_SAMPLES) {
                    val x = i / CURVE_SAMPLES.toFloat()
                    lineTo(x * w, (1f - curve.eval(x)) * h)
                }
            }
        drawPath(path, line, style = Stroke(CURVE_STROKE))
        val one = Offset(curve.c1x * w, (1f - curve.c1y) * h)
        val two = Offset(curve.c2x * w, (1f - curve.c2y) * h)
        drawLine(handle, Offset(0f, h), one, strokeWidth = 2f)
        drawLine(handle, Offset(w, 0f), two, strokeWidth = 2f)
        drawCircle(handle, radius = HANDLE_RADIUS, center = one)
        drawCircle(handle, radius = HANDLE_RADIUS, center = two)
    }
}

private val CURVE_SIZE = 180.dp
private const val CURVE_SAMPLES = 48
private const val CURVE_STROKE = 3f
private const val HANDLE_RADIUS = 8f
private const val HANDLE_LOW = -0.5f
private const val HANDLE_HIGH = 1.5f
private const val GRID_ALPHA = 0.5f
private const val VECTOR_RANGE = 2f

@Composable
private fun ValueEditor(
    value: ParamValue,
    param: ParamId,
    onChange: (ParamValue) -> Unit,
) {
    when (value) {
        is ParamValue.Scalar -> {
            val known = ClipParams.find(param)
            val min = known?.min ?: minOf(0f, value.value)
            val max = known?.max ?: maxOf(1f, value.value)
            ValueSlider(
                stringResource(R.string.edit_curve_value, decimal(value.value)),
                value.value,
                min..max,
            ) { onChange(ParamValue.Scalar(it)) }
        }
        is ParamValue.Vector2 -> {
            ValueSlider("X  ${decimal(value.x)}", value.x, -VECTOR_RANGE..VECTOR_RANGE) { onChange(value.copy(x = it)) }
            ValueSlider("Y  ${decimal(value.y)}", value.y, -VECTOR_RANGE..VECTOR_RANGE) { onChange(value.copy(y = it)) }
        }
        is ParamValue.Colour -> {
            ValueSlider("R  ${decimal(value.r)}", value.r, 0f..1f) { onChange(value.copy(r = it)) }
            ValueSlider("G  ${decimal(value.g)}", value.g, 0f..1f) { onChange(value.copy(g = it)) }
            ValueSlider("B  ${decimal(value.b)}", value.b, 0f..1f) { onChange(value.copy(b = it)) }
            ValueSlider("A  ${decimal(value.a)}", value.a, 0f..1f) { onChange(value.copy(a = it)) }
        }
        is ParamValue.Toggle -> GlassSwitchRow(stringResource(R.string.edit_curve_toggle), value.on, { onChange(ParamValue.Toggle(it)) })
        is ParamValue.Choice ->
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
            ) {
                repeat(value.index + 2) { index ->
                    GlassChip(selected = value.index == index, onClick = { onChange(ParamValue.Choice(index)) }, label = index.toString())
                }
            }
    }
}

private fun decimal(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        GlassSlider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

/** Asks for a text clip's words; [onConfirm] gets them trimmed. */
@Composable
internal fun TextClipDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_text_title)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = rememberPressing { onConfirm(text.trim()) },
            ) { Text(stringResource(R.string.edit_save)) }
        },
        dismissButton = { TextButton(onClick = rememberPressing(onDismiss)) { Text(stringResource(R.string.edit_cancel)) } },
    )
}

/** Which chapter file to write from the markers, before the system's own save sheet opens. */
@Composable
internal fun ChapterFileDialog(
    onPick: (ChapterFile) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_chapters_title)) },
        text = {
            Column {
                ChapterFile.entries.forEach { file ->
                    TextButton(onClick = rememberPressing { onPick(file) }) { Text(stringResource(chapterLabel(file))) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = rememberPressing(onDismiss)) { Text(stringResource(R.string.edit_cancel)) } },
    )
}

private fun chapterLabel(file: ChapterFile): Int =
    when (file) {
        ChapterFile.DESCRIPTION -> R.string.edit_chapters_description
        ChapterFile.WEB_VTT -> R.string.edit_chapters_webvtt
        ChapterFile.FFMETADATA -> R.string.edit_chapters_ffmetadata
    }

/** Runs [AutoCut] over the playing song's hits and hands the accepted ones back as markers or as clips. */
@Composable
internal fun AutoCutSheet(
    envelopeFor: (TransientSource) -> TransientEnvelope?,
    onMarkers: (List<TransientHit>) -> Unit,
    onClips: (List<TransientHit>, untilMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var settings by remember { mutableStateOf(AutoCutSettings()) }
    var result by remember { mutableStateOf<AutoCutResult?>(null) }
    var envelope by remember { mutableStateOf<TransientEnvelope?>(null) }
    var missingAnalysis by remember { mutableStateOf(false) }

    fun detect() {
        val found = envelopeFor(settings.source)
        envelope = found
        missingAnalysis = found == null
        result = found?.let { AutoCut.detect(it, settings) }
    }

    GlassSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = SHEET_PADDING, vertical = GlassSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(GlassSpacing.Medium),
        ) {
            Text(stringResource(R.string.edit_autocut_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.edit_autocut_source), style = MaterialTheme.typography.labelMedium)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
            ) {
                TransientSource.entries.forEach { source ->
                    GlassChip(
                        selected = settings.source == source,
                        onClick = { settings = settings.copy(source = source) },
                        label = stringResource(sourceLabel(source)),
                    )
                }
            }
            Text(
                stringResource(R.string.edit_autocut_sensitivity, (settings.sensitivity * PERCENT).toInt()),
                style = MaterialTheme.typography.labelMedium,
            )
            GlassSlider(value = settings.sensitivity, onValueChange = { settings = settings.copy(sensitivity = it) })
            Text(stringResource(R.string.edit_autocut_spacing, settings.minSpacingMs), style = MaterialTheme.typography.labelMedium)
            GlassSlider(
                value = settings.minSpacingMs.toFloat(),
                onValueChange = { settings = settings.copy(minSpacingMs = (it / SPACING_STEP).toLong() * SPACING_STEP.toLong()) },
                valueRange = SPACING_RANGE,
            )
            GlassButton(onClick = ::detect) { Text(stringResource(R.string.edit_autocut_detect)) }
            AutoCutOutcome(result, missingAnalysis, onMarkers) { hits -> onClips(hits, envelope?.durationMs ?: 0L) }
            Text(
                stringResource(R.string.edit_autocut_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AutoCutOutcome(
    result: AutoCutResult?,
    missingAnalysis: Boolean,
    onMarkers: (List<TransientHit>) -> Unit,
    onClips: (List<TransientHit>) -> Unit,
) {
    when (result) {
        null -> if (missingAnalysis) Outcome(stringResource(R.string.edit_autocut_no_analysis), error = true)
        is AutoCutResult.NoCuts -> Outcome(stringResource(missLabel(result.reason)), error = true)
        is AutoCutResult.Suggested -> {
            Outcome(pluralStringResource(R.plurals.edit_autocut_found, result.hits.size, result.hits.size), error = false)
            Row(horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
                GlassButton(onClick = { onMarkers(result.hits) }) { Text(stringResource(R.string.edit_autocut_as_markers)) }
                GlassButton(filled = false, onClick = { onClips(result.hits) }) { Text(stringResource(R.string.edit_autocut_as_clips)) }
            }
        }
    }
}

@Composable
private fun Outcome(
    text: String,
    error: Boolean,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

private fun sourceLabel(source: TransientSource): Int =
    when (source) {
        TransientSource.TRANSIENT -> R.string.edit_autocut_source_transient
        TransientSource.SPECTRAL_FLUX -> R.string.edit_autocut_source_flux
        TransientSource.ONSET -> R.string.edit_autocut_source_onset
        TransientSource.KICK -> R.string.edit_autocut_source_kick
        TransientSource.SNARE -> R.string.edit_autocut_source_snare
        TransientSource.HAT -> R.string.edit_autocut_source_hat
    }

private fun missLabel(miss: AutoCutMiss): Int =
    when (miss) {
        AutoCutMiss.EmptyEnvelope -> R.string.edit_autocut_miss_empty
        AutoCutMiss.NoTransients -> R.string.edit_autocut_miss_none
        is AutoCutMiss.WindowTooShort -> R.string.edit_autocut_miss_window
    }

private const val PERCENT = 100
private const val SPACING_STEP = 10f
private val SPACING_RANGE = 60f..2_000f
