package dev.geode.visuals

import android.content.Context
import android.net.Uri
import dev.geode.data.PresetStore
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Preset
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.core.PresetUpgrade
import dev.geode.feature.customization.handlers.LegacyModulation
import dev.geode.feature.customization.handlers.PresetFormat
import dev.geode.feature.customization.handlers.PresetLibrary
import dev.geode.feature.customization.handlers.StoredPreset
import dev.geode.feature.visuals.api.PresetFolderPath
import dev.geode.feature.visuals.api.SavedPreset
import dev.geode.feature.visuals.handlers.PresetFiles
import dev.geode.feature.visuals.handlers.SavedPresets
import dev.geode.ui.BuiltInPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The presets the Presets browser (#144) reaches, over the same files and format Customization saves
 * and loads ([PresetLibrary], [PresetFormat]): the app's own preset folder, with its subfolders. A
 * preset an older Geode saved is upgraded as it is read; the built-in looks are the old app's,
 * upgraded the same way, so there is one format.
 */
class AppPresetFiles(
    private val context: Context,
    private val root: File,
    private val legacy: LegacyModulation,
    private val fallbackStyle: SceneId,
) : PresetFiles {
    override suspend fun builtIn(): List<Preset> =
        io {
            BuiltInPresets.ALL.mapNotNull { old -> runCatching { presetOf(PresetStore.toJson(old)) }.getOrNull() }
        }

    override suspend fun saved(): SavedPresets =
        io {
            val presets =
                files().mapNotNull { file ->
                    val preset = runCatching { presetOf(file.readText()) }.getOrNull() ?: return@mapNotNull null
                    SavedPreset(PresetId(preset.name), preset.style, folderOf(file))
                }
            val folders =
                if (!root.isDirectory) {
                    emptyList()
                } else {
                    root
                        .walkTopDown()
                        .filter { it.isDirectory && it != root }
                        .map { PresetFolderPath(it.relativeTo(root).invariantSeparatorsPath) }
                        .sortedBy { it.value }
                        .toList()
                }
            SavedPresets(presets, folders)
        }

    override suspend fun rename(
        id: PresetId,
        name: String,
    ) = io {
        val file = fileOf(id)
        val preset = presetOf(file.readText()).copy(name = name)
        write(File(file.parentFile, PresetLibrary.fileNameOf(name)), PresetFormat.encode(preset))
        if (!file.delete()) throw IOException("could not remove $file")
    }

    override suspend fun move(
        id: PresetId,
        folder: PresetFolderPath,
    ) = io {
        val file = fileOf(id)
        val target = File(folderFile(folder), file.name)
        if (target == file) return@io
        if (!folderFile(folder).let { it.isDirectory || it.mkdirs() } || !file.renameTo(target)) throw IOException("could not move $file")
    }

    override suspend fun delete(id: PresetId) =
        io {
            val file = fileOf(id)
            if (!file.delete()) throw IOException("could not delete $file")
        }

    override suspend fun createFolder(folder: PresetFolderPath) =
        io {
            val dir = folderFile(folder)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not make $dir")
        }

    override suspend fun renameFolder(
        from: PresetFolderPath,
        to: PresetFolderPath,
    ) = io {
        val target = folderFile(to)
        target.parentFile?.mkdirs()
        if (target.exists() || !folderFile(from).renameTo(target)) throw IOException("could not rename $from to $to")
    }

    override suspend fun deleteFolder(folder: PresetFolderPath) =
        io {
            val dir = folderFile(folder)
            if (folder.isTop || !dir.delete()) throw IOException("could not delete $dir")
        }

    override suspend fun text(id: PresetId): String = io { fileOf(id).readText() }

    override suspend fun import(
        text: String,
        name: (String) -> String,
    ): SavedPreset? =
        io {
            val read = runCatching { presetOf(text) }.getOrNull() ?: return@io null
            val preset = read.copy(name = name(read.name))
            write(File(root, PresetLibrary.fileNameOf(preset.name)), PresetFormat.encode(preset))
            SavedPreset(PresetId(preset.name), preset.style)
        }

    override suspend fun readDocument(uri: String): String =
        io {
            val input = context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException(uri)
            input.use { it.readBytes().toString(Charsets.UTF_8) }
        }

    /** The preset in [text], upgraded when an older Geode wrote it. Throws when it is not a preset. */
    private fun presetOf(text: String): Preset =
        when (val stored = PresetFormat.decode(text, fallbackStyle)) {
            is StoredPreset.Current -> stored.preset
            is StoredPreset.Legacy ->
                PresetUpgrade
                    .upgrade(stored.preset, legacy.lfos(), legacy.envelopes(), BuiltInStyles.catalogue, fallbackStyle)
                    .preset
        }

    private fun files(): List<File> =
        if (!root.isDirectory) {
            emptyList()
        } else {
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == JSON }
                .sorted()
                .toList()
        }

    private fun fileOf(id: PresetId): File {
        val wanted = PresetLibrary.fileNameOf(id.value)
        return files().firstOrNull { it.name == wanted } ?: throw FileNotFoundException("no preset named ${id.value}")
    }

    private fun folderOf(file: File): PresetFolderPath =
        PresetFolderPath(
            file.parentFile
                ?.relativeTo(root)
                ?.invariantSeparatorsPath
                .orEmpty(),
        )

    private fun folderFile(folder: PresetFolderPath): File {
        val dir = if (folder.isTop) root else File(root, folder.value)
        if (!dir.canonicalPath.startsWith(root.canonicalPath)) throw IOException("$folder is outside the preset folder")
        return dir
    }

    private fun write(
        target: File,
        text: String,
    ) {
        val folder = target.parentFile
        if (folder == null || !(folder.isDirectory || folder.mkdirs())) throw IOException("could not make the folder for $target")
        val partial = File(folder, target.name + PARTIAL)
        partial.writeText(text)
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("could not replace $target")
        }
    }

    private suspend fun <T> io(block: () -> T): T =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: JSONException) {
            throw IOException(e)
        }

    private companion object {
        const val JSON = "json"
        const val PARTIAL = ".partial"
    }
}
