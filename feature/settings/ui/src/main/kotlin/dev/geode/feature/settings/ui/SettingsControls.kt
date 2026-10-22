package dev.geode.feature.settings.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent

/** Multiplies a fraction for showing it as a percentage. */
internal const val PERCENT = 100

/** A titled group of settings on a raised surface. */
@Composable
internal fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(SettingsDimens.groupCorner),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(SettingsDimens.groupPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.rowGap),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/** One choice out of [options], as a row of chips that scrolls when it does not fit. */
@Composable
internal fun <T> ChoiceRow(
    label: String?,
    options: List<T>,
    selected: T,
    name: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf(ChipSpan.NONE) }
    // The chosen chip is never left scrolled off the row: the row moves just enough to show it whole.
    LaunchedEffect(chosen, viewport) {
        if (viewport > 0) scroll.scrollTo(chosen.scrollToShow(scroll.value, viewport))
    }
    Column {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        Row(
            Modifier.fillMaxWidth().onSizeChanged { viewport = it.width }.horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(SettingsDimens.chipGap),
        ) {
            options.forEach { option ->
                val isChosen = option == selected
                FilterChip(
                    selected = isChosen,
                    onClick = { onSelect(option) },
                    label = { Text(name(option), style = MaterialTheme.typography.labelSmall) },
                    modifier =
                        if (isChosen) {
                            Modifier.onPlaced { chosen = ChipSpan(it.positionInParent().x.toInt(), it.size.width) }
                        } else {
                            Modifier
                        },
                )
            }
        }
    }
}

/** Where a chip sits along its row, in pixels from the row's start. */
internal data class ChipSpan(
    val start: Int,
    val width: Int,
) {
    /** The scroll offset that shows this chip whole, moving from [current] as little as possible. */
    fun scrollToShow(
        current: Int,
        viewport: Int,
    ): Int =
        when {
            start < current -> start
            start + width > current + viewport -> start + width - viewport
            else -> current
        }

    companion object {
        val NONE = ChipSpan(0, 0)
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
