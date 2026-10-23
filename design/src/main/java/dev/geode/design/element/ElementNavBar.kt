package dev.geode.design.element

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.geode.design.theme.StoneIcon
import dev.geode.design.theme.StoneIconArt

/** One place on the bar: its label, which is also what accessibility reads, and its glyph. */
@Immutable
data class ElementNavItem(
    val label: String,
    val icon: StoneIcon,
)

/** The bar's sizes, the same in every Element. */
object ElementNavBarDefaults {
    /** The bar's height, above the system navigation. */
    val Height = 68.dp

    /** The least size of each place's touch area. */
    val TapTarget = 48.dp

    /** The mark behind the selected place's glyph. */
    val MarkWidth = 56.dp
    val MarkHeight = 30.dp

    val GlyphToLabel = 4.dp
    val SidePadding = 4.dp
}

/**
 * The bar outside Home, styled by the current Element ([LocalElementTokens]): the same places, in the
 * same positions, with the same labels in every Element. Each place takes an equal share of the width
 * and at least [ElementNavBarDefaults.TapTarget] each way. Until the Element's bar art lands, the bar
 * is the flat deep background colour.
 */
@Composable
fun ElementNavBar(
    items: List<ElementNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalElementTokens.current
    ElementSurface(tokens.colors.backgroundDeep, tokens.art.barSurface, modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(ElementNavBarDefaults.Height)
                .padding(horizontal = ElementNavBarDefaults.SidePadding)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                ElementNavPlace(item, index == selected, tokens) { onSelect(index) }
            }
        }
    }
}

@Composable
private fun RowScope.ElementNavPlace(
    item: ElementNavItem,
    selected: Boolean,
    tokens: ElementTokens,
    onClick: () -> Unit,
) {
    val colors = tokens.colors
    val select = tween<Color>(tokens.motion.selectMillis)
    val mark by animateColorAsState(if (selected) colors.active else Color.Transparent, select, label = "elementNavMark")
    val glyph by animateColorAsState(if (selected) colors.onActive else colors.textSecondary, select, label = "elementNavGlyph")
    val label by animateColorAsState(if (selected) colors.textPrimary else colors.textSecondary, select, label = "elementNavLabel")
    Column(
        Modifier
            .weight(1f)
            .defaultMinSize(minWidth = ElementNavBarDefaults.TapTarget, minHeight = ElementNavBarDefaults.TapTarget)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .defaultMinSize(minWidth = ElementNavBarDefaults.MarkWidth, minHeight = ElementNavBarDefaults.MarkHeight)
                .background(mark, tokens.shapes.barItem),
            contentAlignment = Alignment.Center,
        ) {
            // The label below already names the place, so the glyph adds nothing for accessibility.
            StoneIconArt(item.icon, contentDescription = null, tint = glyph)
        }
        Spacer(Modifier.height(ElementNavBarDefaults.GlyphToLabel))
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            color = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A surface in an Element: its [art] drawn edge to edge when the Element has it, otherwise the flat
 * [color]. The colour is always drawn underneath, so the surface stays opaque.
 */
@Composable
fun ElementSurface(
    color: Color,
    art: Int?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.background(color)) {
        if (art != null) {
            Image(
                painterResource(art),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }
        content()
    }
}
