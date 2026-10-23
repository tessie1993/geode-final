package dev.geode.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.geode.design.theme.LocalFontColor
import dev.geode.design.theme.LocalReducedMotion
import dev.geode.design.theme.StoneComponent
import dev.geode.design.theme.StoneIcon
import dev.geode.design.theme.StoneIconArt
import dev.geode.design.theme.StoneState
import dev.geode.design.theme.StoneSurfaceArt
import dev.geode.design.theme.accentTextColor
import dev.geode.design.theme.rememberStoneInteraction
import dev.geode.design.theme.rememberStoneState
import dev.geode.design.theme.stonePress

@Composable
fun CrystalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = rememberStoneInteraction()
    val state = rememberStoneState(interaction, enabled = enabled)
    val component =
        when {
            compact -> StoneComponent.COMPACT_BUTTON
            filled -> StoneComponent.PRIMARY_BUTTON
            else -> StoneComponent.SECONDARY_BUTTON
        }
    Box(
        modifier
            .stonePress(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .defaultMinSize(minHeight = if (compact) CrystalSize.CompactButtonMinHeight else CrystalSize.ButtonMinHeight),
    ) {
        StoneSurfaceArt(component, state, Modifier.matchParentSize())
        Row(
            Modifier
                .align(Alignment.Center)
                .padding(
                    horizontal = if (compact) CrystalSpacing.CompactButtonHorizontal else CrystalSpacing.ButtonHorizontal,
                    vertical = if (compact) CrystalSpacing.CompactButtonVertical else CrystalSpacing.ButtonVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val label = (LocalFontColor.current ?: cs.onSurface).copy(alpha = if (enabled) 1f else CrystalAlpha.DISABLED_CONTENT)
            CompositionLocalProvider(LocalContentColor provides label) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(letterSpacing = CrystalLetterSpacing.Button)) {
                    content()
                }
            }
        }
    }
}

@Composable
fun CrystalPlayButton(
    icon: StoneIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = rememberStoneInteraction()
    val state = rememberStoneState(interaction, enabled = enabled)
    Box(
        modifier
            .size(CrystalSize.PlayButton)
            .stonePress(interaction)
            .then(if (enabled) Modifier.softGlow(cs.primary, CrystalGlow.PlayButton, CrystalGlow.PLAY_BUTTON_STRENGTH) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StoneSurfaceArt(StoneComponent.ICON_BUTTON, state, Modifier.matchParentSize())
        StoneIconArt(
            icon,
            contentDescription,
            tint = (LocalFontColor.current ?: cs.onSurface).copy(alpha = if (enabled) 1f else CrystalAlpha.DISABLED_CONTENT),
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CrystalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = rememberStoneInteraction()
    val thumbState = rememberStoneState(interaction, enabled = enabled)
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        interactionSource = interaction,
        thumb = {
            StoneSurfaceArt(StoneComponent.SLIDER_THUMB, thumbState, Modifier.size(CrystalSize.SliderThumb))
        },
        track = {
            val span = valueRange.endInclusive - valueRange.start
            val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
            val dim = if (enabled) 1f else CrystalAlpha.DISABLED_SLIDER
            Box(Modifier.fillMaxWidth().height(CrystalSize.SliderTrack)) {
                StoneSurfaceArt(
                    StoneComponent.SLIDER_TRACK,
                    if (enabled) StoneState.DEFAULT else StoneState.DISABLED,
                    Modifier.matchParentSize(),
                )
                Canvas(Modifier.matchParentSize()) {
                    val y = size.height / 2f
                    if (steps > 0) {
                        for (i in 1..steps) {
                            val x = size.width * i / (steps + 1)
                            drawCircle(
                                cs.onSurface.copy(alpha = CrystalAlpha.SLIDER_TICK * dim),
                                radius = CrystalSize.SliderTickRadius.toPx(),
                                center = Offset(x, y),
                            )
                        }
                    }
                    if (fraction > 0f) {
                        val endX = size.width * fraction
                        drawLine(
                            brush =
                                Brush.horizontalGradient(
                                    listOf(cs.secondary, cs.primary),
                                    endX = endX.coerceAtLeast(1f),
                                ),
                            start = Offset(0f, y),
                            end = Offset(endX, y),
                            strokeWidth = CrystalSize.SliderFillStroke.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = cs.primary.copy(alpha = CrystalAlpha.SLIDER_FILL_GLOW * dim),
                            start = Offset(0f, y),
                            end = Offset(endX, y),
                            strokeWidth = CrystalSize.SliderFillGlowStroke.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        },
    )
}

data class CrystalNavItem(
    val label: String,
    val icon: StoneIcon,
)

@Composable
fun CrystalNavBar(
    items: List<CrystalNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    opacity: Float,
) {
    val cs = MaterialTheme.colorScheme
    val barAlpha = (CrystalAlpha.NAV_BAR_MIN + CrystalAlpha.NAV_BAR_RANGE * opacity).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().graphicsLayer { alpha = barAlpha }) {
        StoneSurfaceArt(StoneComponent.NAVIGATION_BAR, StoneState.DEFAULT, Modifier.matchParentSize())
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(CrystalSize.NavBarHeight)
                .selectableGroup(),
        ) {
            val selectedLabel = accentTextColor()
            // With motion reduced the highlight snaps to the new tab instead of easing over.
            val reduced = LocalReducedMotion.current
            val colorSpec: AnimationSpec<Color> = if (reduced) snap() else spring()
            val gemSpec: AnimationSpec<Float> = if (reduced) snap() else spring()
            items.forEachIndexed { i, item ->
                val sel = i == selected
                val tint by animateColorAsState(
                    if (sel) cs.primary else cs.onSurfaceVariant.copy(alpha = CrystalAlpha.UNSELECTED_CONTENT),
                    animationSpec = colorSpec,
                    label = "crystalNavTint",
                )
                val labelTint by animateColorAsState(
                    if (sel) selectedLabel else cs.onSurfaceVariant.copy(alpha = CrystalAlpha.UNSELECTED_CONTENT),
                    animationSpec = colorSpec,
                    label = "crystalNavLabel",
                )
                val gemAlpha by animateFloatAsState(if (sel) 1f else 0f, gemSpec, label = "crystalNavGem")
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = sel, role = Role.Tab, onClick = { onSelect(i) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(CrystalSize.Gem).graphicsLayer { alpha = gemAlpha }) {
                        CrystalGem(cs.primary, size = CrystalSize.Gem)
                    }
                    Spacer(Modifier.height(CrystalSpacing.GemToLabel))
                    StoneIconArt(item.icon, item.label, tint = tint)
                    Spacer(Modifier.height(CrystalSpacing.IconToLabel))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = CrystalLetterSpacing.NavLabel),
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = labelTint,
                    )
                }
            }
        }
    }
}

@Composable
fun CrystalTabs(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    ScrollableTabRow(
        selectedTabIndex = selected,
        modifier = modifier,
        edgePadding = CrystalSpacing.TabEdge,
        containerColor = Color.Transparent,
        indicator = { },
        divider = {
            Box(Modifier.fillMaxWidth().height(CrystalSize.Hairline).luminousHairline(cs.primary.copy(alpha = CrystalAlpha.TAB_DIVIDER)))
        },
    ) {
        titles.forEachIndexed { i, title ->
            val sel = i == selected
            Tab(
                selected = sel,
                onClick = { onSelect(i) },
                selectedContentColor = accentTextColor(),
                unselectedContentColor = cs.onSurfaceVariant.copy(alpha = CrystalAlpha.UNSELECTED_TAB),
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Spacer(Modifier.height(CrystalSpacing.GemToLabel))
                        val gemAlpha by animateFloatAsState(if (sel) 1f else 0f, label = "crystalTabGem")
                        Box(Modifier.size(CrystalSize.TabGem).graphicsLayer { alpha = gemAlpha }) {
                            CrystalGem(cs.primary, size = CrystalSize.TabGem)
                        }
                    }
                },
            )
        }
    }
}

@Composable
fun CrystalSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(CrystalSpacing.SegmentGap)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            val interaction = rememberStoneInteraction()
            val state = rememberStoneState(interaction, selected = sel)
            Box(
                Modifier
                    .weight(1f)
                    .stonePress(interaction)
                    .defaultMinSize(minHeight = CrystalSize.SegmentMinHeight)
                    .selectable(
                        selected = sel,
                        role = Role.RadioButton,
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(i) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                StoneSurfaceArt(StoneComponent.CHIP, state, Modifier.matchParentSize())
                Text(
                    label,
                    Modifier.padding(horizontal = CrystalSpacing.SegmentHorizontal, vertical = CrystalSpacing.SegmentVertical),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color =
                        (LocalFontColor.current ?: cs.onSurface)
                            .copy(alpha = if (sel) 1f else CrystalAlpha.UNSELECTED_CONTENT),
                )
            }
        }
    }
}
