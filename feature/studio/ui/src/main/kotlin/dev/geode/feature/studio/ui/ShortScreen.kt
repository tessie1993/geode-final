package dev.geode.feature.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.geode.design.glass.GlassButton
import dev.geode.design.glass.GlassCard
import dev.geode.design.glass.GlassChip
import dev.geode.design.glass.GlassSectionHeader
import dev.geode.design.glass.GlassSlider
import dev.geode.design.glass.GlassSpacing
import dev.geode.design.glass.GlassSwitchRow
import dev.geode.design.glass.rememberPressing
import dev.geode.design.glass.rememberRangeTicking
import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.ProjectId

/**
 * Make Short (#328): a section of a Render or Recording under a minute, framed at 9:16 by sliding the
 * crop, or drawn again at 9:16 when it is a Render that can be, then made into the Project chosen
 * through the render queue. Stateless (ADR-0003): every change is an [EditCommand].
 */
@Composable
fun ShortScreen(
    model: ShortModel,
    onAction: (EditAction) -> Unit,
    modifier: Modifier = Modifier,
    tools: EditorTools = NoEditorTools,
) {
    var project by remember(model.home) { mutableStateOf(model.home) }
    val edit = model.edit
    val change: (Long, Long, Float, Float, Boolean) -> Unit = { start, end, x, y, redraw ->
        onAction(EditAction.Send(EditCommand.ChangeShort(start, end, x, y, redraw)))
    }
    LazyColumn(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(GlassSpacing.Medium),
        contentPadding = PaddingValues(horizontal = GlassSpacing.Large, vertical = GlassSpacing.Medium),
    ) {
        item { ShortHeader(model, onAction) }
        item { ShortPreview(model, tools) }
        item {
            ShortSection(model) { start, end -> change(start, end, edit.cropX, edit.cropY, model.redraw) }
        }
        if (model.canRedraw) {
            item {
                ShortCard(stringResource(R.string.short_redraw_title)) {
                    GlassSwitchRow(
                        label = stringResource(R.string.short_redraw),
                        checked = model.redraw,
                        onCheckedChange = { change(edit.startMs, edit.endMs, edit.cropX, edit.cropY, it) },
                        supporting = stringResource(R.string.short_redraw_hint),
                    )
                }
            }
        }
        if (!model.redraw) {
            item { ShortFrame(model) { x, y -> change(edit.startMs, edit.endMs, x, y, false) } }
        }
        item { ShortProject(model, project) { project = it } }
        item {
            ShortCard(stringResource(R.string.short_make)) {
                GlassButton(enabled = model.canExport, onClick = { onAction(EditAction.Send(EditCommand.Export(project))) }) {
                    Text(stringResource(R.string.short_make))
                }
                EditNote(stringResource(R.string.short_make_note))
                ExportRows(model.exports)
            }
        }
    }
}

@Composable
private fun ShortHeader(
    model: ShortModel,
    onAction: (EditAction) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = rememberPressing { onAction(EditAction.Send(EditCommand.Close)) }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.edit_back))
        }
        Column(Modifier.weight(StudioDimens.FILL)) {
            Text(stringResource(R.string.short_title), style = MaterialTheme.typography.titleMedium)
            Text(
                model.source.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ShortCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        GlassSectionHeader(title)
        content()
    }
}

/** The section's first frame, with the 9:16 frame the Short keeps drawn over it unless it is drawn again. */
@Composable
private fun ShortPreview(
    model: ShortModel,
    tools: EditorTools,
) {
    val source = model.source
    val aspect = if (source.width > 0 && source.height > 0) source.width.toFloat() / source.height else WIDE
    val shade = MaterialTheme.colorScheme.scrim.copy(alpha = SHADE_ALPHA)
    val outline = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(MIN_ASPECT, MAX_ASPECT)).clip(MaterialTheme.shapes.medium)) {
            tools.Frame(source.file, model.edit.startMs, Modifier.fillMaxSize())
            if (!model.redraw) {
                Canvas(Modifier.fillMaxSize()) {
                    val (offset, kept) = keptFrame(size, model)
                    drawRect(shade, Offset.Zero, Size(size.width, offset.y))
                    drawRect(shade, Offset(0f, offset.y + kept.height), Size(size.width, size.height - offset.y - kept.height))
                    drawRect(shade, Offset(0f, offset.y), Size(offset.x, kept.height))
                    drawRect(shade, Offset(offset.x + kept.width, offset.y), Size(size.width - offset.x - kept.width, kept.height))
                    drawRect(outline, offset, kept, style = Stroke(OUTLINE.toPx()))
                }
            }
        }
        EditNote(stringResource(R.string.short_explainer))
    }
}

/** Where the 9:16 frame sits in a picture drawn [size] big, and how big it is. */
private fun keptFrame(
    size: Size,
    model: ShortModel,
): Pair<Offset, Size> =
    if (model.slidesAcross) {
        val width = size.height * NINE / SIXTEEN
        Offset((size.width - width) * model.edit.cropX, 0f) to Size(width, size.height)
    } else {
        val height = size.width * SIXTEEN / NINE
        Offset(0f, (size.height - height) * model.edit.cropY) to Size(size.width, height)
    }

@Composable
private fun ShortSection(
    model: ShortModel,
    onSection: (Long, Long) -> Unit,
) {
    val edit = model.edit
    ShortCard(stringResource(R.string.short_section)) {
        RangeSlider(
            value = edit.startMs.toFloat()..edit.endMs.toFloat(),
            onValueChange =
                rememberRangeTicking(edit.startMs.toFloat()..edit.endMs.toFloat(), 0f..model.lengthMs.toFloat()) { range ->
                    val start = range.start.toLong()
                    shortSection(start, range.endInclusive.toLong(), model.lengthMs, startMoved = start != edit.startMs)
                        ?.let { (from, to) -> onSection(from, to) }
                },
            valueRange = 0f..model.lengthMs.toFloat(),
        )
        Text(
            stringResource(
                R.string.short_section_summary,
                Millis(edit.startMs).clock(),
                Millis(edit.endMs).clock(),
                Millis(model.sectionMs).clock(),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        EditNote(stringResource(R.string.short_limit))
    }
}

@Composable
private fun ShortFrame(
    model: ShortModel,
    onMove: (Float, Float) -> Unit,
) {
    val edit = model.edit
    ShortCard(stringResource(R.string.short_crop)) {
        if (model.slidesAcross) {
            Text(stringResource(R.string.short_crop_across), style = MaterialTheme.typography.labelMedium)
            GlassSlider(value = edit.cropX, onValueChange = { onMove(it, edit.cropY) })
        } else {
            Text(stringResource(R.string.short_crop_down), style = MaterialTheme.typography.labelMedium)
            GlassSlider(value = edit.cropY, onValueChange = { onMove(edit.cropX, it) })
        }
    }
}

@Composable
private fun ShortProject(
    model: ShortModel,
    chosen: ProjectId?,
    onChoose: (ProjectId) -> Unit,
) {
    ShortCard(stringResource(R.string.short_project)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
        ) {
            model.projects.forEach { project ->
                GlassChip(selected = project.id == chosen, onClick = { onChoose(project.id) }, label = project.name)
            }
        }
    }
}

private const val NINE = 9f
private const val SIXTEEN = 16f
private const val WIDE = 16f / 9f
private const val MIN_ASPECT = 0.3f
private const val MAX_ASPECT = 3.4f
private const val SHADE_ALPHA = 0.55f
private val OUTLINE = 2.dp
