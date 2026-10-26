package dev.geode.design.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A bottom sheet of glass, with a drag handle. Over a page, pass the page's source as a
 * [GlassBackdrop.Live] so the sheet frosts it; over the live Visual pass [GlassBackdrop.Visual]; by
 * default it is matte, since it may open over anything. Swiping it down, tapping outside or Back closes
 * it through [onDismissRequest].
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
            Box(Modifier.fillMaxWidth().padding(vertical = GlassSpacing.Medium), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(GlassSize.HandleWidth, GlassSize.HandleHeight)
                        .background(MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
            content()
        }
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
