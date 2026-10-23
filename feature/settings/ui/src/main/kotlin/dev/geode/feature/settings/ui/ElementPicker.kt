package dev.geode.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.design.element.ElementSkins
import dev.geode.design.element.ElementTokens
import dev.geode.feature.settings.api.Element

private const val TILES_PER_ROW = 2

/** The look :design gives [element]. The one place Settings' Elements meet their tokens. */
fun Element.tokens(): ElementTokens =
    when (this) {
        Element.WATER -> ElementSkins.Water
        Element.WOOD -> ElementSkins.Wood
        Element.STONE -> ElementSkins.Stone
        Element.BIOLUMINESCENCE -> ElementSkins.Bioluminescence
    }

/**
 * The Element picker in Settings > Theme: one tile per first-wave Element, each drawn in that
 * Element's own colours and shape so the choice shows what it gives. Picking one applies at once.
 */
@Composable
internal fun ElementPicker(
    selected: Element,
    onSelect: (Element) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SettingsDimens.rowGap)) {
        Text(stringResource(R.string.look_element), style = MaterialTheme.typography.labelMedium)
        // Two by two, so every name fits whole at the largest text size.
        Column(
            Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.chipGap),
        ) {
            Element.entries.chunked(TILES_PER_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(SettingsDimens.chipGap)) {
                    row.forEach { element ->
                        ElementTile(element, element == selected, Modifier.weight(1f)) { onSelect(element) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ElementTile(
    element: Element,
    chosen: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val look = element.tokens()
    val colors = look.colors
    Box(
        modifier
            .defaultMinSize(minHeight = SettingsDimens.elementTileHeight)
            .clip(look.shapes.card)
            .background(colors.background)
            .border(
                if (chosen) SettingsDimens.elementChosenBorder else SettingsDimens.elementBorder,
                if (chosen) colors.active else colors.divider,
                look.shapes.card,
            ).selectable(selected = chosen, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.padding(SettingsDimens.elementTilePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SettingsDimens.rowGap),
        ) {
            Box(
                Modifier
                    .size(SettingsDimens.elementSwatch)
                    .background(if (chosen) colors.active else colors.secondary, look.shapes.button),
            )
            Text(
                elementName(element),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
