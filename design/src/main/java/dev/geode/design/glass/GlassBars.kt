package dev.geode.design.glass

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A top bar: an optional [navigation] button, the page's [title] (read as a heading) and its
 * [actions]. It rests on the page's backdrop; give it a [backdrop] to draw it on glass, for content that
 * scrolls under it.
 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    backdrop: GlassBackdrop? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (backdrop != null) Modifier.glassSurface(GlassKind.BAR, RectangleShape, backdrop, GlassEdge.NONE) else Modifier)
            .defaultMinSize(minHeight = GlassSize.TopBarHeight)
            .padding(horizontal = GlassSpacing.ExtraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigation != null) navigation() else Spacer(Modifier.width(GlassSpacing.Medium))
        Text(
            title,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = GlassSpacing.Small)
                    .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/**
 * The bottom navigation bar on one slab of glass, with whatever sits [above] it (the mini-player) on the
 * same slab. Every place keeps its label and at least a 48 dp touch area; the selected one sits on a
 * pill of the selection colour. Over content that scrolls under it, pass a [GlassBackdrop.Live].
 */
@Composable
fun GlassNavigationBar(
    items: List<GlassNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop = GlassBackdrop.Baked,
    above: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().glassSurface(GlassKind.BAR, RectangleShape, backdrop, GlassEdge.TOP)) {
        above?.invoke()
        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
            items.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    // The label already names the place, so the icon adds nothing for accessibility.
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

/** The wide-screen twin of [GlassNavigationBar]: the same places, order and selection, down the side. */
@Composable
fun GlassNavigationRail(
    items: List<GlassNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop = GlassBackdrop.Baked,
) {
    NavigationRail(
        modifier = modifier.fillMaxHeight().glassSurface(GlassKind.BAR, RectangleShape, backdrop, GlassEdge.NONE),
        containerColor = Color.Transparent,
    ) {
        items.forEachIndexed { index, item ->
            NavigationRailItem(
                selected = index == selected,
                onClick = { onSelect(index) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
