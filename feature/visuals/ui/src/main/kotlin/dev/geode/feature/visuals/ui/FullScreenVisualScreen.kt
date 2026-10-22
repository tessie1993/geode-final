package dev.geode.feature.visuals.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.design.forest.ForestShapes
import dev.geode.design.forest.ForestSpacing
import dev.geode.engine.types.SceneId

/**
 * Full-screen visual mode (#43): the one live Visual fills the screen, and this is the App UI over it.
 * It has no background, so the Visual the app keeps running behind it shows through. A tap shows or
 * hides the controls: the Style's name, the Styles either side of it, and the way back. Stateless
 * (ADR-0003): it draws [model] and sends every tap to [onAction].
 */
@Composable
fun FullScreenVisualScreen(
    model: FullScreenModel,
    onAction: (FullScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toggle = stringResource(R.string.visuals_show_controls)
    Box(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = toggle }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onAction(FullScreenAction.ToggleControls)
            },
    ) {
        if (model.controlsShown) Controls(model, onAction)
    }
}

@Composable
private fun Controls(
    model: FullScreenModel,
    onAction: (FullScreenAction) -> Unit,
) {
    // A soft gradient, not glass: the Visual is a GL surface that cannot be blurred (design system).
    val scrim = MaterialTheme.colorScheme.scrim
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(scrim.copy(alpha = SCRIM_ALPHA), Color.Transparent)))
                .safeDrawingPadding()
                .padding(ForestSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                model.name.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FilledTonalIconButton(onClick = { onAction(FullScreenAction.Exit) }, shape = ForestShapes.Button) {
                Icon(Icons.Filled.FullscreenExit, contentDescription = stringResource(R.string.visuals_exit_full_screen))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, scrim.copy(alpha = SCRIM_ALPHA))))
                .safeDrawingPadding()
                .padding(ForestSpacing.Large),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StepButton(model.previous, onAction) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.visuals_previous_style))
            }
            StepButton(model.next, onAction) {
                Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.visuals_next_style))
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
        shape = ForestShapes.Button,
        content = icon,
    )
}

/** How dark the gradient behind the controls gets at the screen's edge. */
private const val SCRIM_ALPHA = 0.55f
