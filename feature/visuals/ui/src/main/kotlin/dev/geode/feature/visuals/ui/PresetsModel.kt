package dev.geode.feature.visuals.ui

import androidx.compose.runtime.Immutable
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.PresetFolderPath
import dev.geode.feature.visuals.api.PresetShelf
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState

/** Where the Presets browser is open: App UI only (ADR-0003). */
sealed interface PresetPlace {
    /** A folder of saved presets; [PresetFolderPath.TOP] is the browser's first page. */
    data class Saved(
        val folder: PresetFolderPath,
    ) : PresetPlace

    /** The built-in presets, one folder per Style. */
    data object BuiltIn : PresetPlace

    /** The built-in presets made for [style]. */
    data class BuiltInFor(
        val style: SceneId,
    ) : PresetPlace

    companion object {
        val TOP: PresetPlace = Saved(PresetFolderPath.TOP)
    }
}

/** One preset as the browser lists it: [name], and the Style it was made on ([styleName], when this version draws it). */
@Immutable
data class PresetItem(
    val id: PresetId,
    val name: String,
    val styleName: String?,
    /** It ships with Geode: it can be applied, not changed. */
    val builtIn: Boolean,
)

/** One folder as the browser lists it: [name], and how many presets and folders it holds ([count]). */
@Immutable
data class PresetFolderItem(
    val place: PresetPlace,
    val name: String,
    val count: Int,
)

/** One step of the way back to the first page. [name] is null for the built-in presets, which the screen names. */
@Immutable
data class Crumb(
    val place: PresetPlace,
    val name: String?,
)

/** A folder a saved preset can be moved to, named by its whole path; the top has an empty [name]. */
@Immutable
data class MoveTarget(
    val folder: PresetFolderPath,
    val name: String,
)

/** A short note after an import, App UI only. */
sealed interface PresetNotice {
    data class Imported(
        val name: String,
    ) : PresetNotice

    data class NotImported(
        val problem: ImportProblem,
    ) : PresetNotice

    data object ClipboardEmpty : PresetNotice
}

/**
 * What the Presets browser shows at [place]. On the first page: the built-in presets for the Style
 * shown now ([forCurrent]), the way into every built-in preset ([builtInCount] of them), and the saved
 * folders and presets at the top. In a folder: its [folders] and [presets]. [trail] leads back to the
 * first page; [moveTargets] and [savedNames] serve the Move and Rename dialogs.
 */
@Immutable
data class PresetsModel(
    val loading: Boolean,
    val place: PresetPlace,
    val trail: List<Crumb>,
    val currentName: String?,
    val forCurrent: List<PresetItem>,
    val builtInCount: Int,
    val folders: List<PresetFolderItem>,
    val presets: List<PresetItem>,
    val moveTargets: List<MoveTarget>,
    val savedNames: Set<String>,
    val notice: PresetNotice?,
) {
    /** Whether the open place holds saved presets, so folders can be made in it. */
    val editable: Boolean get() = place is PresetPlace.Saved
}

/** Everything the person can do in the Presets browser. */
sealed interface PresetsAction {
    /** App UI only: opens [place]. */
    data class Open(
        val place: PresetPlace,
    ) : PresetsAction

    data class Apply(
        val id: PresetId,
    ) : PresetsAction

    data class Rename(
        val id: PresetId,
        val name: String,
    ) : PresetsAction

    data class Move(
        val id: PresetId,
        val folder: PresetFolderPath,
    ) : PresetsAction

    data class Delete(
        val id: PresetId,
    ) : PresetsAction

    data class Share(
        val id: PresetId,
    ) : PresetsAction

    /** Makes a folder in the one open now. */
    data class CreateFolder(
        val name: String,
    ) : PresetsAction

    data class RenameFolder(
        val folder: PresetFolderPath,
        val name: String,
    ) : PresetsAction

    data class DeleteFolder(
        val folder: PresetFolderPath,
    ) : PresetsAction

    /** Import the preset link on the clipboard; the route reads it. */
    data object PasteLink : PresetsAction

    /** Import a preset file the person picks; the route opens the picker. */
    data object OpenFile : PresetsAction

    /** App UI only: the note after an import is put away. */
    data object DismissNotice : PresetsAction
}

/** The Command an action sends with [place] open; null for the actions the route handles itself. */
fun PresetsAction.toCommand(place: PresetPlace): VisualsCommand? =
    when (this) {
        is PresetsAction.Apply -> VisualsCommand.ApplyPreset(id)
        is PresetsAction.Rename -> VisualsCommand.RenamePreset(id, name)
        is PresetsAction.Move -> VisualsCommand.MovePreset(id, folder)
        is PresetsAction.Delete -> VisualsCommand.DeletePreset(id)
        is PresetsAction.Share -> VisualsCommand.SharePreset(id)
        is PresetsAction.CreateFolder -> (place as? PresetPlace.Saved)?.let { VisualsCommand.CreatePresetFolder(name, it.folder) }
        is PresetsAction.RenameFolder -> VisualsCommand.RenamePresetFolder(folder, name)
        is PresetsAction.DeleteFolder -> VisualsCommand.DeletePresetFolder(folder)
        is PresetsAction.Open, PresetsAction.PasteLink, PresetsAction.OpenFile, PresetsAction.DismissNotice -> null
    }

/**
 * The Presets browser's model with [place] open and [notice] showing. A place that no longer exists
 * (a folder renamed or deleted elsewhere) shows the first page.
 */
fun VisualsState.toPresetsModel(
    place: PresetPlace,
    notice: PresetNotice? = null,
): PresetsModel {
    val shelf = presets ?: PresetShelf()
    val names = styles.associate { it.id to it.name }
    val open = if (shelf.has(place)) place else PresetPlace.TOP

    fun saved(folder: PresetFolderPath) = shelf.savedIn(folder).map { PresetItem(it.id, it.id.value, names[it.style], builtIn = false) }

    fun builtIn(style: SceneId) = shelf.builtInFor(style).map { PresetItem(it.id, it.id.value, names[style], builtIn = true) }

    fun folder(path: PresetFolderPath) =
        PresetFolderItem(
            PresetPlace.Saved(path),
            path.name,
            shelf.savedIn(path).size + shelf.foldersIn(path).size,
        )

    val atTop = open == PresetPlace.TOP
    return PresetsModel(
        loading = presets == null,
        place = open,
        trail = trail(open, names),
        currentName = current?.let { names[it] },
        forCurrent = if (atTop) current?.let(::builtIn).orEmpty() else emptyList(),
        builtInCount = if (atTop) shelf.builtIn.size else 0,
        folders =
            when (open) {
                is PresetPlace.Saved -> shelf.foldersIn(open.folder).map(::folder)
                PresetPlace.BuiltIn ->
                    shelf.builtIn
                        .groupBy { it.style }
                        .map { (style, list) -> PresetFolderItem(PresetPlace.BuiltInFor(style), names[style] ?: style.value, list.size) }
                        .sortedBy { it.name.lowercase() }
                is PresetPlace.BuiltInFor -> emptyList()
            },
        presets =
            when (open) {
                is PresetPlace.Saved -> saved(open.folder)
                PresetPlace.BuiltIn -> emptyList()
                is PresetPlace.BuiltInFor -> builtIn(open.style)
            },
        moveTargets = (listOf(PresetFolderPath.TOP) + shelf.folders).map { MoveTarget(it, it.value.replace("/", " / ")) },
        savedNames = shelf.saved.map { it.id.value }.toSet(),
        notice = notice,
    )
}

/** Whether [place] can be shown: a listed folder, or built-in presets that exist. */
private fun PresetShelf.has(place: PresetPlace): Boolean =
    when (place) {
        is PresetPlace.Saved -> place.folder.isTop || place.folder in folders
        PresetPlace.BuiltIn -> builtIn.isNotEmpty()
        is PresetPlace.BuiltInFor -> builtInFor(place.style).isNotEmpty()
    }

private fun trail(
    place: PresetPlace,
    names: Map<SceneId, String>,
): List<Crumb> =
    when (place) {
        is PresetPlace.Saved ->
            generateSequence(place.folder) { it.parent }
                .takeWhile { !it.isTop }
                .map { Crumb(PresetPlace.Saved(it), it.name) }
                .toList()
                .reversed()
        PresetPlace.BuiltIn -> listOf(Crumb(PresetPlace.BuiltIn, null))
        is PresetPlace.BuiltInFor -> listOf(Crumb(PresetPlace.BuiltIn, null), Crumb(place, names[place.style] ?: place.style.value))
    }

/** The place Back leads to from this one; null on the first page. */
fun PresetPlace.up(): PresetPlace? =
    when (this) {
        is PresetPlace.Saved -> folder.parent?.let(PresetPlace::Saved)
        PresetPlace.BuiltIn -> PresetPlace.TOP
        is PresetPlace.BuiltInFor -> PresetPlace.BuiltIn
    }

/** [place] as a string that survives configuration changes and process death. */
internal fun PresetPlace.key(): String =
    when (this) {
        is PresetPlace.Saved -> SAVED + folder.value
        PresetPlace.BuiltIn -> BUILT_IN
        is PresetPlace.BuiltInFor -> BUILT_IN_FOR + style.value
    }

/** The place a [key] names; the first page for anything else. */
internal fun presetPlaceOf(key: String): PresetPlace =
    when {
        key == BUILT_IN -> PresetPlace.BuiltIn
        key.startsWith(BUILT_IN_FOR) -> PresetPlace.BuiltInFor(SceneId(key.removePrefix(BUILT_IN_FOR)))
        key.startsWith(SAVED) -> PresetPlace.Saved(PresetFolderPath(key.removePrefix(SAVED)))
        else -> PresetPlace.TOP
    }

private const val SAVED = "saved:"
private const val BUILT_IN = "built-in"
private const val BUILT_IN_FOR = "built-in:"
