package dev.geode.feature.customization.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import dev.geode.design.forest.ForestShapes
import dev.geode.design.forest.ForestSpacing
import dev.geode.design.forest.LocalForestColors
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.ValueKind
import kotlin.math.abs
import kotlin.math.roundToInt

/** How many points a Modulator's curve is drawn with. */
private const val PICTURE_POINTS = 64

/** How many cycles of a wave its picture shows. */
private const val PICTURE_CYCLES = 2f

/** A quiet heading over a group of controls. */
@Composable
internal fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = LocalForestColors.current.textSecondary,
        modifier = modifier.padding(top = ForestSpacing.Medium, bottom = ForestSpacing.ExtraSmall),
    )
}

/** Chips that wrap onto as many lines as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ForestSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(ForestSpacing.ExtraSmall),
    ) { content() }
}

/** One chip that is either chosen or not; greyed out when not [enabled]. */
@Composable
internal fun ChoiceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shape = ForestShapes.Chip,
    )
}

/** How a slider's value reads: whole numbers for stepped values, two places otherwise. */
@Composable
private fun valueText(
    kind: ValueKind,
    value: Float,
): String =
    when (kind) {
        ValueKind.STEPPED -> stringResource(R.string.customize_whole_value, value.roundToInt())
        ValueKind.CONTINUOUS, ValueKind.SWITCH -> stringResource(R.string.customize_value, value)
    }

/**
 * One slider of the Advanced view. It follows the finger and sets the value when let go, so one drag
 * is one undo step. A switch for on/off properties. A long-press on its name offers "React to…". A
 * row the current Style does not use is greyed out and says so (#365). Its − and + take fine steps,
 * and coarse ones on a long-press (#392).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ValueSlider(
    row: SliderRow,
    label: String,
    onSet: (Float) -> Unit,
    onReactTo: () -> Unit,
) {
    var held by remember(row.value) { mutableFloatStateOf(row.value) }
    val colors = LocalForestColors.current
    val reactTo = stringResource(R.string.customize_react_to)
    val enabled = !row.ignored
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else IGNORED_ALPHA)
                .combinedClickable(onClick = {}, onLongClick = onReactTo, onLongClickLabel = reactTo),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (row.linked) {
                Icon(
                    Icons.Filled.Link,
                    contentDescription = stringResource(R.string.customize_linked),
                    tint = colors.accent,
                    modifier = Modifier.padding(end = ForestSpacing.Small).size(ForestSpacing.Large),
                )
            }
            if (row.kind == ValueKind.SWITCH) {
                Switch(checked = row.value > row.min, onCheckedChange = { onSet(if (it) row.max else row.min) }, enabled = enabled)
            } else {
                val nudge = { up: Boolean, coarse: Boolean ->
                    held = nudged(held, row.min, row.max, row.kind, up, coarse)
                    onSet(held)
                }
                NudgeButton(up = false, enabled = enabled) { nudge(false, it) }
                Text(valueText(row.kind, held), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                NudgeButton(up = true, enabled = enabled) { nudge(true, it) }
            }
        }
        if (row.kind != ValueKind.SWITCH) {
            Slider(
                value = held,
                onValueChange = { held = it },
                valueRange = row.min..row.max,
                steps = if (row.kind == ValueKind.STEPPED) ((row.max - row.min).roundToInt() - 1).coerceAtLeast(0) else 0,
                onValueChangeFinished = { onSet(held) },
                enabled = enabled,
            )
        }
        if (row.ignored) {
            Text(
                stringResource(R.string.customize_ignored),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * A row's − or + ([up]): a tap is a fine step and a long-press a coarse one (#392), each handed to
 * [onNudge] with whether it is coarse.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NudgeButton(
    up: Boolean,
    enabled: Boolean = true,
    onNudge: (coarse: Boolean) -> Unit,
) {
    val fine = stringResource(if (up) R.string.customize_step_up else R.string.customize_step_down)
    val coarse = stringResource(if (up) R.string.customize_step_up_coarse else R.string.customize_step_down_coarse)
    Box(
        Modifier
            .size(ForestSpacing.TapTarget)
            .combinedClickable(
                enabled = enabled,
                onClickLabel = fine,
                role = Role.Button,
                onLongClickLabel = coarse,
                onLongClick = { onNudge(true) },
                onClick = { onNudge(false) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (up) Icons.Filled.Add else Icons.Filled.Remove,
            contentDescription = fine,
            tint = LocalForestColors.current.textSecondary,
            modifier = Modifier.size(ForestSpacing.Large),
        )
    }
}

/** A row that is removed by swiping it away to either side. */
@Composable
internal fun SwipeToRemove(
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    var offset by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { CustomizeDimens.swipeThreshold.toPx() }
    val remove by rememberUpdatedState(onRemove)
    Box(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(offset.roundToInt(), 0) }
            .draggable(
                rememberDraggableState { offset += it },
                Orientation.Horizontal,
                onDragStopped = {
                    if (abs(offset) > threshold) remove()
                    offset = 0f
                },
            ),
    ) { content() }
}

/** A thin bar showing [level] 0..1 of its width. */
@Composable
internal fun Meter(
    level: Float,
    modifier: Modifier = Modifier,
) {
    val colors = LocalForestColors.current
    Canvas(modifier.fillMaxWidth().height(CustomizeDimens.meter)) {
        drawRect(colors.textSecondary.copy(alpha = QUIET_ALPHA))
        drawRect(colors.highlight, size = Size(size.width * level.coerceIn(0f, 1f), size.height))
    }
}

/**
 * One Macro knob of the basic view: its ring shows the set value, and an inner ring its live value as
 * the music moves it. Drag up or down to turn it; a long-press opens its editor.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MacroKnobView(
    knob: MacroKnob,
    driver: String?,
    onSet: (Float) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var held by remember(knob.value) { mutableFloatStateOf(knob.value) }
    val set by rememberUpdatedState(onSet)
    val colors = LocalForestColors.current
    val percent = (held * 100).roundToInt()
    val description = stringResource(R.string.customize_macro_knob, knob.name, percent)
    Column(modifier.alpha(if (knob.ignored) IGNORED_ALPHA else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(CustomizeDimens.knob)
                .pointerInput(knob.index, knob.value) {
                    detectVerticalDragGestures(onDragEnd = { set(held) }) { change, dragged ->
                        change.consume()
                        held = (held - dragged / (size.height * 2f)).coerceIn(0f, 1f)
                    }
                }.combinedClickable(onClick = {}, onLongClick = onEdit)
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(held, 0f..1f)
                    setProgress { value ->
                        set(value.coerceIn(0f, 1f))
                        true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = CustomizeDimens.knobStroke.toPx()
                ring(colors.textSecondary.copy(alpha = QUIET_ALPHA), 1f, stroke, inset = stroke / 2)
                ring(colors.accent, held, stroke, inset = stroke / 2)
                knob.live?.let { live ->
                    val thin = CustomizeDimens.liveStroke.toPx()
                    ring(colors.highlight, live, thin, inset = stroke + thin)
                }
            }
            Text(stringResource(R.string.customize_percent, percent), style = MaterialTheme.typography.titleMedium)
        }
        Text(knob.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (knob.ignored) {
            Text(
                stringResource(R.string.customize_macro_ignored),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (driver != null) {
            Text(
                driver,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onAccent,
                maxLines = 1,
                modifier =
                    Modifier
                        .padding(top = ForestSpacing.Hairline)
                        .background(colors.accent, ForestShapes.Chip)
                        .padding(horizontal = ForestSpacing.Small, vertical = ForestSpacing.Hairline),
            )
        }
    }
}

/** The knob's open ring, filled to [fraction]. */
private fun DrawScope.ring(
    color: Color,
    fraction: Float,
    stroke: Float,
    inset: Float,
) {
    drawArc(
        color = color,
        startAngle = KNOB_START,
        sweepAngle = KNOB_SWEEP * fraction.coerceIn(0f, 1f),
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(size.width - 2 * inset, size.height - 2 * inset),
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
}

/** What a Modulator does over time, drawn from its settings (#12). */
@Composable
internal fun ModulatorPicture(
    shape: ModulatorShape,
    modifier: Modifier = Modifier,
) {
    val colors = LocalForestColors.current
    Canvas(modifier.fillMaxWidth().height(CustomizeDimens.picture)) {
        val stroke = CustomizeDimens.pictureStroke.toPx()
        when (shape) {
            is ModulatorShape.StepSequencer -> {
                val steps = shape.steps
                if (steps.isNotEmpty()) {
                    val width = size.width / steps.size
                    steps.forEachIndexed { index, value ->
                        val height = size.height * value.coerceIn(0f, 1f)
                        drawRect(
                            colors.accent,
                            topLeft = Offset(index * width + stroke, size.height - height),
                            size = Size(width - 2 * stroke, height),
                        )
                    }
                }
            }
            else -> {
                val value: (Float) -> Float =
                    when (shape) {
                        is ModulatorShape.Lfo -> { time -> waveAt(shape.wave, time * PICTURE_CYCLES) }
                        is ModulatorShape.HitEnvelope -> { time -> envelopeAt(shape.attack, shape.release, time) }
                        is ModulatorShape.RandomDrift -> ::driftAt
                        is ModulatorShape.StepSequencer -> { _ -> 0f }
                    }
                val path = Path()
                for (point in 0..PICTURE_POINTS) {
                    val time = point / PICTURE_POINTS.toFloat()
                    val x = time * size.width
                    val y = stroke + (1f - value(time)) * (size.height - 2 * stroke)
                    if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, colors.accent, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}
