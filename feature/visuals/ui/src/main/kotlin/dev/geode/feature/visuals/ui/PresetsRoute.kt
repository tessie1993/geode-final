package dev.geode.feature.visuals.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.feature.visuals.api.PresetShare
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact

/**
 * The Presets browser's place (ADR-0003, #144): the only part that touches the Visuals store for it.
 * It lists the presets again each time it opens, so presets saved in Customize show up, reads the
 * clipboard for a pasted link and opens the system's file picker for a preset file. Which folder is
 * open and the note after an import are App-UI-only state. `:app` wires it at the switch-over (#17),
 * reached from the Visuals tab beside Customize's preset chips.
 *
 * [onShare] hands a preset to the share sheet: a [PresetShare.Link] as text, a [PresetShare.File] as
 * its file, which `:app` offers through its file provider (the app stays offline). Back goes up a
 * folder, and from the first page is [onBack].
 */
@Composable
fun PresetsRoute(
    visuals: Visuals,
    onShare: (PresetShare) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by visuals.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The place as a plain string, so it survives configuration changes and process death.
    var placeKey by rememberSaveable { mutableStateOf(PresetPlace.TOP.key()) }
    var notice by remember { mutableStateOf<PresetNotice?>(null) }
    val share by rememberUpdatedState(onShare)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { visuals.send(VisualsCommand.ImportPresetFile(it.toString())) }
        }
    LaunchedEffect(visuals) {
        visuals.send(VisualsCommand.ReloadPresets)
        visuals.events.collect { fact ->
            when (fact) {
                is VisualsFact.PresetShareReady -> share(fact.share)
                is VisualsFact.PresetImported -> notice = PresetNotice.Imported(fact.id.value)
                is VisualsFact.PresetNotImported -> notice = PresetNotice.NotImported(fact.problem)
                is VisualsFact.FolderCreated -> Unit
            }
        }
    }
    val model = state.toPresetsModel(presetPlaceOf(placeKey), notice)
    BackHandler { model.place.up()?.let { placeKey = it.key() } ?: onBack() }
    PresetsScreen(
        model,
        onAction = { action ->
            when (action) {
                is PresetsAction.Open -> placeKey = action.place.key()
                PresetsAction.PasteLink -> {
                    val text = clipboardText(context)
                    if (text.isNullOrBlank()) notice = PresetNotice.ClipboardEmpty else visuals.send(VisualsCommand.ImportPresetLink(text))
                }
                PresetsAction.OpenFile -> picker.launch(arrayOf(PRESET_TYPE))
                PresetsAction.DismissNotice -> notice = null
                else -> action.toCommand(model.place)?.let(visuals::send)
            }
        },
        modifier,
    )
}

/** The text on the clipboard, if any. */
private fun clipboardText(context: Context): String? =
    context
        .getSystemService(ClipboardManager::class.java)
        ?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

/** Preset files are JSON, but other apps label them loosely, so any file may be picked. */
private const val PRESET_TYPE = "*/*"
