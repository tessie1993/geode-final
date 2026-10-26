package dev.geode.feature.visuals.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact

/**
 * The Visuals tab's place (ADR-0003): the only part that touches the Visuals store. It reads the
 * store's state and turns the screen's actions into Commands. The search text and the folder open now
 * are App-UI-only state, kept across configuration changes; nothing else lives here. `:app` wires it
 * at the switch-over (#17), over the one live Visual it keeps running behind every place.
 *
 * Entering full-screen mode is [onEnterFullScreen]: a move to another place, which the app's
 * navigation makes. The music and the Visual belong to their app-scoped stores and carry on.
 */
@Composable
fun VisualsRoute(
    visuals: Visuals,
    onEnterFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by visuals.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    // A plain Long so the folder open now survives configuration changes and process death.
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(visuals) {
        visuals.events.collect { fact ->
            when (fact) {
                is VisualsFact.FolderCreated -> open = fact.id.value
                // The Presets browser's facts are its own route's.
                is VisualsFact.PresetShareReady, is VisualsFact.PresetImported, is VisualsFact.PresetNotImported -> Unit
            }
        }
    }
    val model = state.toTabModel(query, open?.let(::FolderId))
    VisualsTabScreen(
        model,
        onAction = { action ->
            when (action) {
                is VisualsAction.Search -> query = action.query
                is VisualsAction.OpenFolder -> open = action.folder?.value
                VisualsAction.EnterFullScreen -> onEnterFullScreen()
                else -> action.toCommand(model.openFolder)?.let(visuals::send)
            }
        },
        modifier,
    )
}

/**
 * Full-screen visual mode's place (ADR-0003). It reads the Visuals store for the Style shown and asks
 * it for the next or previous one. Whether the controls are shown is App-UI-only state, owned by `:app`
 * and passed in as [controlsShown], because the double tap that toggles it lands on the Visual's host
 * there; this screen's own toggle (its accessibility action) reports [onToggleControls]. While it is on
 * screen the system bars are hidden (a swipe brings them back for a moment), and the screen stays awake
 * when the person's Keep screen on setting ([keepScreenOn], read from Settings by `:app`) asks for it.
 * Back, or the leave button, is [onExit].
 */
@Composable
fun FullScreenVisualRoute(
    visuals: Visuals,
    controlsShown: Boolean,
    keepScreenOn: Boolean,
    onToggleControls: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by visuals.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onExit)
    SystemBarsHidden()
    ScreenAwake(keepScreenOn)
    FullScreenVisualScreen(
        state.toFullScreenModel(controlsShown),
        onAction = { action ->
            when (action) {
                FullScreenAction.ToggleControls -> onToggleControls()
                is FullScreenAction.Choose -> visuals.send(VisualsCommand.Choose(action.style))
                FullScreenAction.Exit -> onExit()
            }
        },
        modifier,
    )
}

/**
 * The MilkDrop presets and textures' place (#145, ADR-0003): it reads the Visuals store and owns the two
 * system pickers. Whatever the person picks goes to the store as one import, by content uri; the store
 * reads, checks and copies each file, and its report comes back in the state. `:app` wires it at the
 * switch-over (#17), reached from the Visuals tab. [onReload] loads the preset the Visual shows again
 * from its file (#382), which only the engine can do; without it, the screen offers no reload.
 */
@Composable
fun MilkdropRoute(
    visuals: Visuals,
    modifier: Modifier = Modifier,
    onReload: (() -> Unit)? = null,
) {
    val state by visuals.state.collectAsStateWithLifecycle()

    fun importPicked(
        kind: MilkdropKind,
        picked: List<Uri>,
    ) {
        if (picked.isNotEmpty()) visuals.send(VisualsCommand.ImportMilkdrop(kind, picked.map(Uri::toString)))
    }
    val presetPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importPicked(MilkdropKind.PRESETS, it) }
    val texturePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importPicked(MilkdropKind.TEXTURES, it) }
    MilkdropScreen(
        state.toMilkdropModel().copy(canReload = onReload != null),
        onAction = { action ->
            when (action) {
                is MilkdropAction.Pick ->
                    when (action.kind) {
                        MilkdropKind.PRESETS -> presetPicker.launch(PRESET_TYPES)
                        MilkdropKind.TEXTURES -> texturePicker.launch(TEXTURE_TYPES)
                    }
                MilkdropAction.Reload -> onReload?.invoke()
                MilkdropAction.DismissReport, is MilkdropAction.Play, is MilkdropAction.Delete -> action.toCommand()?.let(visuals::send)
            }
        },
        modifier,
    )
}

/** A `.milk` file has no type of its own, so the preset picker offers every file; the store checks each one. */
private val PRESET_TYPES = arrayOf("*/*")

/** Images, and files a provider cannot type: TGA and DDS often come as plain binary. */
private val TEXTURE_TYPES = arrayOf("image/*", "application/octet-stream")

/** Hides the status and navigation bars while this is composed; a swipe shows them for a moment. */
@Composable
private fun SystemBarsHidden() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Keeps the screen awake while this is composed, when [awake]; puts back what was there after. */
@Composable
private fun ScreenAwake(awake: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, awake) {
        val before = view.keepScreenOn
        if (awake) view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
