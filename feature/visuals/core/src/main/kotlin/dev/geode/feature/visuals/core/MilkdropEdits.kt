package dev.geode.feature.visuals.core

import dev.geode.engine.types.MilkdropStyles
import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.kernel.Next
import dev.geode.kernel.Perform

/**
 * Deleting the person's own MilkDrop presets and textures (#360). Only a file the shelf lists can go, and
 * never a preset Geode ships. A preset is a Style of its own (#305), so deleting one also takes it out of
 * the Favourites, Recent and every folder, and, when the Visual shows it, moves the Visual on to the
 * Style listed after it (before it, for the last). The shelf changes once the folder says the file is
 * gone ([VisualsEvent.MilkdropDeleted]); a delete that fails is reported and changes nothing.
 */
internal object MilkdropEdits {
    fun delete(
        state: VisualsState,
        command: VisualsCommand.DeleteMilkdrop,
    ): Next<VisualsState, VisualsEffect> {
        val shelf = state.milkdrop ?: return Next(state)
        if (shelf.importing) return Next(state)
        val deletable =
            when (command.kind) {
                MilkdropKind.PRESETS -> shelf.presets.any { it.file == command.file && !it.builtIn }
                MilkdropKind.TEXTURES -> command.file in shelf.textures
            }
        if (!deletable) return Next(state)
        val work = Perform(VisualsWork.DeleteMilkdrop(command.kind, command.file))
        if (command.kind == MilkdropKind.TEXTURES) return Next(state, listOf(work))
        val style = MilkdropStyles.of(command.file)
        val moveOn = if (state.current == style) neighbour(state, style)?.let { Perform(VisualsWork.ChooseStyle(it)) } else null
        val library = state.library
        val pruned = library?.without(style)
        val save = if (pruned != null && pruned != library) Perform(VisualsWork.SaveLibrary(pruned)) else null
        return Next(state.copy(library = pruned ?: library), listOfNotNull(moveOn, work, save))
    }

    /** The Style listed after [style], or before it when it is the last; null when it is the only one. */
    private fun neighbour(
        state: VisualsState,
        style: SceneId,
    ): SceneId? {
        val index = state.styles.indexOfFirst { it.id == style }
        if (index < 0) return null
        return (state.styles.getOrNull(index + 1) ?: state.styles.getOrNull(index - 1))?.id
    }

    private fun StyleLibrary.without(style: SceneId): StyleLibrary =
        copy(
            favourites = favourites - style,
            recent = recent - style,
            folders = folders.map { folder -> if (style in folder.styles) folder.copy(styles = folder.styles - style) else folder },
        )
}
