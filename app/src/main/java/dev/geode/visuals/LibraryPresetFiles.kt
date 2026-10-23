package dev.geode.visuals

import android.content.ContentResolver
import android.net.Uri
import dev.geode.engine.types.PresetId
import dev.geode.feature.customization.api.Preset
import dev.geode.feature.customization.handlers.PresetLibrary
import dev.geode.feature.customization.handlers.SavedEntry
import dev.geode.feature.visuals.api.PresetFolderPath
import dev.geode.feature.visuals.api.SavedPreset
import dev.geode.feature.visuals.handlers.PresetFiles
import dev.geode.feature.visuals.handlers.SavedPresets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The Presets browser's preset files (#144) over Customization's own [library] (#142), so there is one
 * reader and one format: a preset is named by its name, a folder by its path in the preset folder.
 * The work runs on [io]; [resolver] reads a document the person picked.
 *
 * No preset ships built in yet: the old built-in looks were sets of the old scene parameters, which
 * nothing maps onto a Customization (#194).
 */
class LibraryPresetFiles(
    private val library: PresetLibrary,
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher,
) : PresetFiles {
    override suspend fun builtIn(): List<Preset> = emptyList()

    override suspend fun saved(): SavedPresets =
        withContext(io) {
            SavedPresets(
                presets = library.entries().map(::savedPreset),
                folders = library.folders().map(::PresetFolderPath),
            )
        }

    override suspend fun rename(
        id: PresetId,
        name: String,
    ) = withContext(io) { library.rename(id, name) }

    override suspend fun move(
        id: PresetId,
        folder: PresetFolderPath,
    ) = withContext(io) { library.move(id, folder.value) }

    override suspend fun delete(id: PresetId) = withContext(io) { library.delete(id) }

    override suspend fun createFolder(folder: PresetFolderPath) = withContext(io) { library.createFolder(folder.value) }

    override suspend fun renameFolder(
        from: PresetFolderPath,
        to: PresetFolderPath,
    ) = withContext(io) { library.renameFolder(from.value, to.value) }

    override suspend fun deleteFolder(folder: PresetFolderPath) = withContext(io) { library.deleteFolder(folder.value) }

    override suspend fun text(id: PresetId): String = withContext(io) { library.text(id) }

    override suspend fun import(
        text: String,
        name: (String) -> String,
    ): SavedPreset? = withContext(io) { library.import(text, name)?.let(::savedPreset) }

    override suspend fun readDocument(uri: String): String =
        withContext(io) {
            val input = resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("could not open $uri")
            input.use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun savedPreset(entry: SavedEntry): SavedPreset = SavedPreset(PresetId(entry.name), entry.style, PresetFolderPath(entry.folder))
}
