package dev.geode.visuals

import android.content.Context
import dev.geode.engine.types.MilkdropStyles
import dev.geode.engine.types.SceneId
import dev.geode.render.scene.SceneIds
import java.io.File

/**
 * What the engine draws for a Style: the [scene] its renderer selects by and, for a MilkDrop preset's own
 * Style, the [milkPreset] file that scene plays (#305). Null for every other Style, and for a MilkDrop
 * preset Style whose name is not a plain file name in the MilkDrop folder.
 */
data class StyleTarget(
    val scene: SceneId,
    val milkPreset: File?,
)

/**
 * Turns the Style Customization shows into what the engine draws (#305): every MilkDrop preset is a
 * Style of its own ([MilkdropStyles]), drawn by the one MilkDrop scene with that preset loaded; every
 * other Style is its own scene. The live Visual, its Recordings and Renders all go through here.
 */
object StyleTargets {
    /** The folder under the app's files the MilkDrop presets, and their textures, are kept in. */
    private const val MILK_FOLDER = "milk"

    /** The app's MilkDrop folder. */
    fun milkFolder(context: Context): File = File(context.applicationContext.filesDir, MILK_FOLDER)

    /** What the engine draws for [style], with the MilkDrop presets in [milkFolder]. */
    fun of(
        style: SceneId,
        milkFolder: File,
    ): StyleTarget {
        val file = MilkdropStyles.file(style) ?: return StyleTarget(style, null)
        // Only a file of the folder itself: a name from a shared preset never reaches outside it.
        val inFolder = file.takeIf { File(it).name == it && it != "." && it != ".." }
        return StyleTarget(SceneIds.MILKDROP, inFolder?.let { File(milkFolder, it) })
    }

    /** The scene [style] is drawn by: the MilkDrop scene for a MilkDrop preset's Style, else its own. */
    fun sceneOf(style: SceneId): SceneId = if (MilkdropStyles.file(style) != null) SceneIds.MILKDROP else style
}
