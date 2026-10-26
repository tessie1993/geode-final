package dev.geode.design.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A bottom sheet of glass, with a drag handle. Over a page, pass the page's source as a
 * [GlassBackdrop.Live] so the sheet frosts it; by default it is matte, since it may open over anything.
 * Swiping it down, tapping outside or Back closes it through [onDismissRequest]. It slides up, as
 * Material's sheets do, so over the live Visual use [GlassVisualSheet], which fades.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop = GlassBackdrop.Unknown,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = shape,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .glassSurface(GlassKind.SHEET, shape, backdrop, GlassEdge.TOP)
                .navigationBarsPadding(),
        ) {
            SheetHandle()
            content()
        }
    }
}

/**
 * A sheet over the live Visual (#355): the sheet glass and handle of [GlassSheet], drawn in place at the
 * bottom of its parent instead of in a window of its own, so it comes and goes with a fade
 * ([GlassCrossfade]) and never slides (#270). It tells the engine where it covers the Visual
 * ([VisualFrost]). A tap on the dimmed Visual above it closes it through [onDismissRequest], read out as
 * [closeLabel]; Back is its caller's, as for any page.
 */
@Composable
fun GlassVisualSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    closeLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
    Box(modifier.fillMaxSize()) {
        Spacer(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = closeLabel,
                    onClick = onDismissRequest,
                ),
        )
        // Never taller than the screen below the status bar, with a tap's height of Visual left above it.
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = GlassSize.TapTarget)) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .glassSurface(GlassKind.SHEET, shape, GlassBackdrop.Visual, GlassEdge.TOP)
                    .navigationBarsPadding(),
            ) {
                SheetHandle()
                content()
            }
        }
    }
}

/** The drag handle at the top of a sheet. */
@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = GlassSpacing.Medium), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(GlassSize.HandleWidth, GlassSize.HandleHeight)
                .background(MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

/**
 * A dialog on matte glass, since it opens over anything: Material's alert dialog with the theme's
 * glass, its large corner and the same slots.
 */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val style = LocalGlassStyle.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = style.colors.glass.copy(alpha = GlassKind.SHEET.matteAlpha),
    )
}

/** How far the live Visual is dimmed behind a [GlassVisualSheet], as Material dims a page behind its sheets. */
private const val SCRIM_ALPHA = 0.32f
