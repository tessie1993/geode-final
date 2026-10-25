package dev.geode.feature.visuals.handlers

import dev.geode.engine.types.PresetId
import dev.geode.feature.customization.api.Preset
import dev.geode.feature.visuals.api.PresetFolderPath
import dev.geode.feature.visuals.api.SavedPreset

/** The saved presets and every folder of them, empty ones included. */
data class SavedPresets(
    val presets: List<SavedPreset>,
    val folders: List<PresetFolderPath>,
)

/**
 * The presets on the phone, as the Presets browser (#144) reaches them. The preset files belong to
 * Customization, which saves and loads them (#142: versioned JSON in the preset folder Settings chose,
 * named by the preset's name, an older Geode's upgraded as they are opened); `:app` implements this
 * over that same library when it wires the feature in (#17), so there is one reader and one format.
 *
 * A folder is a folder inside the preset folder, named by its path. Every call may block on IO and
 * throws when it cannot do what was asked; the handlers turn that into a named failure.
 */
interface PresetFiles {
    /** The presets that ship with Geode, in the current format, each named by its id. */
    suspend fun builtIn(): List<Preset>

    /** Every saved preset, with the Style it was made on and its folder, and every folder. */
    suspend fun saved(): SavedPresets

    /** Saves the preset [id] under [name] instead, in the same folder, and removes the old file. */
    suspend fun rename(
        id: PresetId,
        name: String,
    )

    suspend fun move(
        id: PresetId,
        folder: PresetFolderPath,
    )

    /** Deletes the preset's file, and its copy in the mirror folder when there is one. */
    suspend fun delete(id: PresetId)

    suspend fun createFolder(folder: PresetFolderPath)

    /** Renames the folder [from] to [to], with everything in it. */
    suspend fun renameFolder(
        from: PresetFolderPath,
        to: PresetFolderPath,
    )

    /** Deletes the empty folder. */
    suspend fun deleteFolder(folder: PresetFolderPath)

    /** The text of the saved preset's file, as it is shared. */
    suspend fun text(id: PresetId): String

    /**
     * Saves the preset file [text] at the top of the preset folder, whatever version it is in (an older
     * one is upgraded), under the name [name] gives for the name it carries. Null when [text] is not a
     * preset at all.
     */
    suspend fun import(
        text: String,
        name: (String) -> String,
    ): SavedPreset?

    /** The text of the document at [uri], which the person picked. */
    suspend fun readDocument(uri: String): String
}
