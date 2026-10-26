package dev.geode.feature.visuals.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.design.glass.GlassBackdrop
import dev.geode.design.glass.GlassEdge
import dev.geode.design.glass.GlassFade
import dev.geode.design.glass.GlassKind
import dev.geode.design.glass.GlassSpacing
import dev.geode.design.glass.glassSurface
import dev.geode.engine.types.SceneId

/**
 * Full-screen visual mode (#43): the one live Visual fills the screen, and this is the App UI over it.
 * It has no background, so the Visual the app keeps running behind it shows through. The controls are
 * the Style's name, the Styles either side of it, and the way back, with whatever [actions] the app adds
 * set between the Styles on the bottom bar, wrapping to a second line when they do not fit.
 *
 * Every touch on the picture belongs to the Style (#290), so this layer handles no pointer input of
 * its own: the Visual's host turns a double tap into [FullScreenAction.ToggleControls], and touches
 * pass straight through to it. Accessibility services still reach the toggle, as this layer's click
 * action. Stateless (ADR-0003): it draws [model] and sends every tap to [onAction].
 */
@Composable
fun FullScreenVisualScreen(
    model: FullScreenModel,
    onAction: (FullScreenAction) -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val toggle = stringResource(R.string.visuals_show_controls)
    Box(
        modifier
            .fillMaxSize()
            .semantics {
                contentDescription = toggle
                onClick(label = toggle) {
                    onAction(FullScreenAction.ToggleControls)
                    true
                }
            },
    ) {
        // Glass over the Visual fades in and out, never slides (#270).
        GlassFade(model.controlsShown) { Controls(model, onAction, actions) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(
    model: FullScreenModel,
    onAction: (FullScreenAction) -> Unit,
    actions: @Composable () -> Unit,
) {
    // Bands of glass over the Visual: matte until the engine frosts it (#349).
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(
            Modifier
                .fillMaxWidth()
                .glassSurface(GlassKind.BAR, RectangleShape, GlassBackdrop.Visual, GlassEdge.NONE)
                .safeDrawingPadding()
                .padding(GlassSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                model.name.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FilledTonalIconButton(onClick = { onAction(FullScreenAction.Exit) }) {
                Icon(Icons.Rounded.FullscreenExit, contentDescription = stringResource(R.string.visuals_exit_full_screen))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .glassSurface(GlassKind.BAR, RectangleShape, GlassBackdrop.Visual, GlassEdge.TOP)
                .safeDrawingPadding()
                .padding(GlassSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton(model.previous, onAction) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = stringResource(R.string.visuals_previous_style))
            }
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
            ) {
                actions()
            }
            StepButton(model.next, onAction) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = stringResource(R.string.visuals_next_style))
            }
        }
    }
}

@Composable
private fun StepButton(
    style: SceneId?,
    onAction: (FullScreenAction) -> Unit,
    icon: @Composable () -> Unit,
) {
    FilledTonalIconButton(
        onClick = { style?.let { onAction(FullScreenAction.Choose(it)) } },
        enabled = style != null,
        content = icon,
    )
}
