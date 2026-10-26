package dev.geode.feature.studio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.geode.design.glass.GlassButton
import dev.geode.design.glass.GlassCard
import dev.geode.design.glass.GlassChip
import dev.geode.design.glass.GlassProgress
import dev.geode.design.glass.GlassSectionHeader
import dev.geode.design.glass.GlassSlider
import dev.geode.design.glass.GlassSpacing
import dev.geode.design.glass.GlassSwitchRow
import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.feature.studio.api.edit.ClipLook
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The clip editor (#318), moved from the old Studio into Studio's feature: one video cut, graded,
 * reframed, sped up or slowed down and captioned, previewed as it will export, then exported through
 * the render queue into the Project. Stateless (ADR-0003): every change is an [EditCommand].
 */
@Composable
fun ClipEditorScreen(
    model: ClipEditorModel,
    onAction: (EditAction) -> Unit,
    modifier: Modifier = Modifier,
    tools: EditorTools = NoEditorTools,
) {
    val edit = model.edit
    val change: (ClipEdit) -> Unit = { onAction(EditAction.Send(EditCommand.ChangeClip(it))) }
    LazyColumn(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(GlassSpacing.Medium),
        contentPadding = PaddingValues(horizontal = GlassSpacing.Large, vertical = GlassSpacing.Medium),
    ) {
        item { ClipEditorHeader(model, onAction, onReset = { change(ClipEdit()) }) }
        item { ClipPreviewCard(model, tools) }
        item { CutSection(model, tools, change) }
        item { LookSection(edit, onAction, change) }
        item { FrameSection(edit, change) }
        item { SoundSection(edit, change) }
        item { ExportSection(model.canExport, model.atDefaults, model.exports, onAction) }
    }
}

@Composable
private fun ClipEditorHeader(
    model: ClipEditorModel,
    onAction: (EditAction) -> Unit,
    onReset: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onAction(EditAction.Send(EditCommand.Close)) }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.edit_back))
        }
        Column(Modifier.weight(StudioDimens.FILL)) {
            Text(model.source.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(sourceFacts(model), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!model.atDefaults) TextButton(onClick = onReset) { Text(stringResource(R.string.edit_reset)) }
    }
}

/** "1920×1080 · 0:42". */
private fun sourceFacts(model: ClipEditorModel): String {
    val source = model.source
    val size = if (source.width > 0 && source.height > 0) "${source.width}×${source.height}" else null
    return listOfNotNull(size, source.length.clock()).joinToString(" · ")
}

@Composable
private fun ClipPreviewCard(
    model: ClipEditorModel,
    tools: EditorTools,
) {
    val ratio = model.edit.ratio
    val aspect =
        if (ratio != null) {
            frameRatio(ratio)
        } else if (model.source.width > 0 && model.source.height > 0) {
            model.source.width.toFloat() / model.source.height
        } else {
            WIDE
        }
    Column(verticalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
        tools.ClipPreview(
            model.source,
            model.edit,
            Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(MIN_ASPECT, MAX_ASPECT)).clip(MaterialTheme.shapes.medium),
        )
        EditNote(stringResource(R.string.edit_preview_hint))
    }
}

/** The shape [ratio] has, wide over high. */
private fun frameRatio(ratio: RenderAspect): Float = ratio.wide.toFloat() / ratio.high

private const val WIDE = 16f / 9f
private const val MIN_ASPECT = 0.3f
private const val MAX_ASPECT = 3.4f

@Composable
private fun EditSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        GlassSectionHeader(title)
        content()
    }
}

@Composable
private fun CutSection(
    model: ClipEditorModel,
    tools: EditorTools,
    change: (ClipEdit) -> Unit,
) {
    val edit = model.edit
    val length = model.lengthMs
    EditSection(stringResource(R.string.edit_section_cut)) {
        Row(Modifier.fillMaxWidth().height(FILMSTRIP_HEIGHT), horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Hairline)) {
            repeat(FILMSTRIP_FRAMES) { i ->
                tools.Frame(
                    model.source.file,
                    length * i / FILMSTRIP_FRAMES,
                    Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.small),
                )
            }
        }
        RangeSlider(
            value = edit.startMs.toFloat()..model.outMs.toFloat(),
            onValueChange = { range ->
                change(
                    edit.copy(
                        startMs = range.start.toLong().coerceIn(0L, length),
                        endMs = if (range.endInclusive >= length - 1) 0L else range.endInclusive.toLong(),
                    ),
                )
            },
            valueRange = 0f..length.toFloat(),
        )
        val trim =
            stringResource(
                R.string.edit_trim_summary,
                millisClock(edit.startMs),
                millisClock(model.outMs),
                millisClock(edit.trimmedMs(length)),
            )
        val plays = if (edit.speed != 1f) stringResource(R.string.edit_trim_plays, millisClock(edit.outputMs(length))) else ""
        Text(trim + plays, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private const val FILMSTRIP_FRAMES = 6
private val FILMSTRIP_HEIGHT = 56.dp

@Composable
private fun LookSection(
    edit: ClipEdit,
    onAction: (EditAction) -> Unit,
    change: (ClipEdit) -> Unit,
) {
    EditSection(stringResource(R.string.edit_section_look)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
        ) {
            ClipLook.entries.forEach { look ->
                GlassChip(
                    selected = edit.look == look,
                    onClick = { change(look.applyTo(edit).copy(look = look)) },
                    label = stringResource(lookLabel(look)),
                )
            }
        }
        ValueRow(stringResource(R.string.edit_brightness), edit.brightness, BRIGHTNESS) { change(edit.copy(brightness = it)) }
        ValueRow(stringResource(R.string.edit_contrast), edit.contrast, CONTRAST) { change(edit.copy(contrast = it)) }
        ValueRow(stringResource(R.string.edit_saturation), edit.saturation, SATURATION, unit = "%") { change(edit.copy(saturation = it)) }
        ValueRow(stringResource(R.string.edit_hue_shift), edit.hueDegrees, DEGREES, unit = "°") { change(edit.copy(hueDegrees = it)) }
        EditNote(stringResource(R.string.edit_look_explainer))
        Row(horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
            GlassButton(filled = false, onClick = { onAction(EditAction.PickLut) }) { Text(stringResource(R.string.edit_lut_pick)) }
            if (edit.lutUri !=
                null
            ) {
                TextButton(onClick = { change(edit.copy(lutUri = null)) }) { Text(stringResource(R.string.edit_lut_clear)) }
            }
        }
        EditNote(
            edit.lutUri?.let { stringResource(R.string.edit_lut_loaded, fileLabel(it)) } ?: stringResource(R.string.edit_lut_explainer),
        )
        ValueRow(stringResource(R.string.edit_gamma_red), edit.gammaRed, GAMMA, decimals = 2) { change(edit.copy(gammaRed = it)) }
        ValueRow(stringResource(R.string.edit_gamma_green), edit.gammaGreen, GAMMA, decimals = 2) { change(edit.copy(gammaGreen = it)) }
        ValueRow(stringResource(R.string.edit_gamma_blue), edit.gammaBlue, GAMMA, decimals = 2) { change(edit.copy(gammaBlue = it)) }
    }
}

private fun lookLabel(look: ClipLook): Int =
    when (look) {
        ClipLook.NONE -> R.string.edit_look_none
        ClipLook.PUNCH -> R.string.edit_look_punch
        ClipLook.BLEACH -> R.string.edit_look_bleach
        ClipLook.NEON -> R.string.edit_look_neon
        ClipLook.NIGHT -> R.string.edit_look_night
        ClipLook.MONO -> R.string.edit_look_mono
        ClipLook.INVERT -> R.string.edit_look_invert
    }

private val BRIGHTNESS = -0.5f..0.5f
private val CONTRAST = -0.6f..0.6f
private val SATURATION = -100f..100f
private val DEGREES = -180f..180f
private val GAMMA = 0.5f..2f
private val SPEED = 0.25f..4f

@Composable
private fun FrameSection(
    edit: ClipEdit,
    change: (ClipEdit) -> Unit,
) {
    EditSection(stringResource(R.string.edit_section_frame)) {
        ValueRow(stringResource(R.string.edit_speed), edit.speed, SPEED, unit = "×", decimals = 2) { change(edit.copy(speed = it)) }
        ValueRow(
            stringResource(R.string.edit_rotate),
            edit.rotationDegrees,
            DEGREES,
            unit = "°",
        ) { change(edit.copy(rotationDegrees = it)) }
        Text(stringResource(R.string.edit_reframe), style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
        ) {
            GlassChip(
                selected = edit.ratio == null,
                onClick = { change(edit.copy(ratio = null)) },
                label = stringResource(R.string.edit_as_shot),
            )
            RenderAspect.entries.forEach { shape ->
                GlassChip(
                    selected = edit.ratio == shape,
                    onClick = { change(edit.copy(ratio = shape)) },
                    label = "${shape.wide}:${shape.high}",
                )
            }
        }
        if (edit.ratio != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
                CLIP_SIZES.forEach { size ->
                    GlassChip(
                        selected = edit.quality == size,
                        onClick = { change(edit.copy(quality = size)) },
                        label = "${size.shortSide}p",
                    )
                }
            }
            EditNote(stringResource(R.string.edit_reframe_explainer))
        }
    }
}

/** The sizes the clip editor reframes to, as it always offered them. */
private val CLIP_SIZES = listOf(RenderSize.HD720, RenderSize.FHD1080, RenderSize.UHD4K)

@Composable
private fun SoundSection(
    edit: ClipEdit,
    change: (ClipEdit) -> Unit,
) {
    EditSection(stringResource(R.string.edit_section_sound)) {
        GlassSwitchRow(stringResource(R.string.edit_mute), edit.mute, { change(edit.copy(mute = it)) })
        OutlinedTextField(
            value = edit.caption,
            onValueChange = { change(edit.copy(caption = it)) },
            label = { Text(stringResource(R.string.edit_caption)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        EditNote(stringResource(R.string.edit_caption_explainer))
    }
}

/** Export, and the Edits on their way through the render queue. */
@Composable
internal fun ExportSection(
    canExport: Boolean,
    atDefaults: Boolean,
    exports: List<EditExportRow>,
    onAction: (EditAction) -> Unit,
) {
    EditSection(stringResource(R.string.edit_export)) {
        if (atDefaults) EditNote(stringResource(R.string.edit_nothing_changed))
        GlassButton(enabled = canExport, onClick = { onAction(EditAction.Send(EditCommand.Export())) }) {
            Text(stringResource(R.string.edit_export))
        }
        EditNote(stringResource(R.string.edit_export_note))
        ExportRows(exports)
    }
}

/** Each Edit in the render queue: how far it got, or that it waits. */
@Composable
internal fun ExportRows(exports: List<EditExportRow>) {
    exports.forEach { row ->
        val progress = row.progress
        if (progress == null) {
            EditNote(stringResource(R.string.edit_export_waiting, row.name))
        } else {
            Text(
                stringResource(R.string.edit_exporting, row.name, (progress * PERCENT).roundToInt()),
                style = MaterialTheme.typography.labelMedium,
            )
            GlassProgress(progress)
        }
    }
}

private const val PERCENT = 100

@Composable
private fun ValueRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = "",
    decimals: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.edit_slider_value, label, String.format(Locale.ROOT, "%.${decimals}f", value), unit),
            style = MaterialTheme.typography.labelMedium,
        )
        GlassSlider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
internal fun EditNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A length in milliseconds as `m:ss`. */
private fun millisClock(ms: Long): String = Millis(ms).clock()
