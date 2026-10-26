package dev.geode.design.glass

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A glass card: frosted glass in the theme's large corner, holding [content] in a column. Cards rest on
 * a baked backdrop or on other glass; over the live Visual pass [GlassBackdrop.Visual]. With [onClick]
 * the whole card is one button.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    backdrop: GlassBackdrop = GlassBackdrop.Baked,
    contentPadding: PaddingValues = PaddingValues(GlassSpacing.Large),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(GlassSpacing.Medium),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .glassSurface(GlassKind.CARD, MaterialTheme.shapes.large, backdrop)
            .then(glassClickable(onClick, MaterialTheme.shapes.large))
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * A button on glass: [filled] in the accent for the main action, otherwise a quiet tonal button. It is
 * at least [GlassSize.TapTarget] tall; a [compact] one draws smaller but keeps the same touch area.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val padding = if (compact) CompactPadding else ButtonDefaults.ContentPadding
    val sized = modifier.defaultMinSize(minHeight = if (compact) CompactHeight else GlassSize.TapTarget)
    if (filled) {
        Button(onClick = onClick, modifier = sized, enabled = enabled, contentPadding = padding, content = content)
    } else {
        FilledTonalButton(onClick = onClick, modifier = sized, enabled = enabled, contentPadding = padding, content = content)
    }
}

/** A choice chip: outlined while off, filled with the selection colour while [selected]. */
@Composable
fun GlassChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        enabled = enabled,
        border = FilterChipDefaults.filterChipBorder(enabled, selected, borderColor = MaterialTheme.colorScheme.outline),
    )
}

/**
 * A list row: a [headline], an optional [supporting] line under it, and optional [leading] and
 * [trailing] parts. One line is [GlassSize.RowOneLine] tall, two are [GlassSize.RowTwoLines]. With
 * [onClick] the whole row is one button.
 */
@Composable
fun GlassListRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (supporting == null) GlassSize.RowOneLine else GlassSize.RowTwoLines)
            .then(glassClickable(onClick, RectangleShape))
            .padding(horizontal = GlassSpacing.Large, vertical = GlassSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Large),
    ) {
        leading?.invoke()
        RowText(headline, supporting, Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * [onClick] as a button on glass, or nothing without one. It is reached by the D-pad and the keyboard
 * like any button, and with focus it shows a ring of the accent in [shape] (#410).
 */
@Composable
private fun glassClickable(
    onClick: (() -> Unit)?,
    shape: Shape,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    if (onClick == null) return Modifier
    val ring = if (focused) Modifier.border(GlassTokens.FocusEdge, LocalGlassStyle.current.colors.accent, shape) else Modifier
    return Modifier
        .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
        .then(ring)
}

/** A row that turns a setting on or off: the whole row is the switch's touch area. */
@Composable
fun GlassSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (supporting == null) GlassSize.RowOneLine else GlassSize.RowTwoLines)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = GlassSpacing.Large, vertical = GlassSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Large),
    ) {
        RowText(label, supporting, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A slider on glass: the accent fills the part chosen, the selection colour the rest. */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
    )
}

/** The heading of a group of settings or a section of a page, in the accent. Read as a heading. */
@Composable
fun GlassSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RowText(
    headline: String,
    supporting: String?,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(headline, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A compact button's padding and drawn height; its touch area stays [GlassSize.TapTarget]. */
private val CompactPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
private val CompactHeight = 36.dp
