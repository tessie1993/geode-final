package dev.geode.ui.shortcuts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

/** How long the mouse rests before its pointer hides over the full-screen Visual. */
private const val POINTER_IDLE_MS = 2_500L

/** No pointer at all. */
private val HiddenPointer = PointerIcon(android.view.PointerIcon.TYPE_NULL)

/**
 * The mouse pointer over the full-screen Visual (#410): it hides once the mouse has rested a moment
 * and comes back the moment it moves. It only watches the pointer, never consumes it, so the Visual's
 * own touch and the controls over it work as before.
 */
@Composable
fun rememberIdlePointerHiding(): Modifier {
    var moves by remember { mutableIntStateOf(0) }
    var hidden by remember { mutableStateOf(false) }
    LaunchedEffect(moves) {
        hidden = false
        delay(POINTER_IDLE_MS)
        hidden = true
    }
    return Modifier
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val type = awaitPointerEvent(PointerEventPass.Initial).type
                    if (type == PointerEventType.Move || type == PointerEventType.Enter || type == PointerEventType.Press) moves++
                }
            }
        }.pointerHoverIcon(if (hidden) HiddenPointer else PointerIcon.Default, overrideDescendants = hidden)
}
