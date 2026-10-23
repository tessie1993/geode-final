package dev.geode.feature.customization.handlers

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.geode.feature.settings.api.FolderSettings
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * A folder of preset files. Paths are relative to the folder, with `/` between subfolders. Every call
 * may block on IO and throws an [IOException] when it cannot do what was asked.
 */
interface PresetFolder {
    /** Every `.json` file in the folder and its subfolders. */
    fun files(): List<String>

    fun read(path: String): String

    /** Writes [text] to [path], replacing what was there; a reader never sees half a file. */
    fun write(
        path: String,
        text: String,
    )

    /** Deletes the file at [path]; one that is not there is already gone. */
    fun delete(path: String)

    /** Every subfolder, empty ones included, by its path. */
    fun folders(): List<String>

    /** Makes the subfolder [path], and the folders above it, unless it is there already. */
    fun makeFolder(path: String)

    /** Deletes the subfolder [path] when it is empty; throws when it holds anything. */
    fun deleteFolder(path: String)
}

/**
 * Where presets live (Settings > Default paths, #101). [saved] is where presets are saved and looked
 * for first; [mirror] is a folder every saved preset is also copied to, if any; [appStorage] is the
 * app's own preset folder, where an older Geode saved them, and [backup] keeps the originals of the
 * presets the upgrade converted. [saved] and [mirror] are asked each time, so a change in Settings
 * applies to the next save.
 */
interface PresetPlaces {
    fun saved(): PresetFolder

    fun mirror(): PresetFolder?

    val appStorage: PresetFolder

    val backup: PresetFolder
}

/** A folder on the app's own storage. */
class DirectoryPresetFolder(
    private val root: File,
) : PresetFolder {
    override fun files(): List<String> =
        if (!root.isDirectory) {
            emptyList()
        } else {
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == JSON }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .sorted()
                .toList()
        }

    override fun read(path: String): String = fileAt(path).readText()

    override fun write(
        path: String,
        text: String,
    ) {
        val target = fileAt(path)
        val partial = File(folderFor(target), target.name + PARTIAL)
        partial.writeText(text)
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("could not replace $target")
        }
    }

    override fun delete(path: String) {
        val file = fileAt(path)
        if (file.exists() && !file.delete()) throw IOException("could not delete $path")
    }

    override fun folders(): List<String> =
        if (!root.isDirectory) {
            emptyList()
        } else {
            root
                .walkTopDown()
                .filter { it.isDirectory && it != root }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .sorted()
                .toList()
        }

    override fun makeFolder(path: String) {
        val folder = fileAt(path)
        if (!(folder.isDirectory || folder.mkdirs())) throw IOException("could not make $path")
    }

    // File.delete refuses a folder that holds anything.
    override fun deleteFolder(path: String) {
        val folder = fileAt(path)
        if (folder.isDirectory && !folder.delete()) throw IOException("could not delete $path")
    }

    private fun folderFor(target: File): File {
        val folder = target.parentFile
        if (folder == null || !(folder.isDirectory || folder.mkdirs())) throw IOException("could not make the folder for $target")
        return folder
    }

    private fun fileAt(path: String): File {
        val file = File(root, path)
        if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) throw IOException("$path is outside the folder")
        return file
    }

    private companion object {
        const val PARTIAL = ".partial"
    }
}

/**
 * A folder the person picked through the system's folder picker: a document tree they granted the app
 * lasting access to.
 */
class DocumentTreePresetFolder(
    private val context: Context,
    private val tree: Uri,
) : PresetFolder {
    private fun root(): DocumentFile =
        DocumentFile.fromTreeUri(context, tree)?.takeIf { it.isDirectory } ?: throw FileNotFoundException("$tree is not a folder")

    override fun files(): List<String> = files(root(), "")

    private fun files(
        folder: DocumentFile,
        prefix: String,
    ): List<String> =
        folder.listFiles().flatMap { child ->
            val name = child.name ?: return@flatMap emptyList()
            when {
                child.isDirectory -> files(child, "$prefix$name/")
                name.endsWith(".$JSON") -> listOf("$prefix$name")
                else -> emptyList()
            }
        }

    override fun read(path: String): String {
        val file = find(path) ?: throw FileNotFoundException(path)
        val input = context.contentResolver.openInputStream(file.uri) ?: throw IOException("could not open $path")
        return input.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override fun write(
        path: String,
        text: String,
    ) {
        val parts = path.split('/')
        val folder = parts.dropLast(1).fold(root(), ::subfolder)
        val file = fileIn(folder, parts.last())
        // "wt" truncates: a shorter preset never keeps the tail of the longer one it replaced.
        val output = context.contentResolver.openOutputStream(file.uri, "wt") ?: throw IOException("could not write $path")
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override fun delete(path: String) {
        val file = find(path) ?: return
        if (!file.delete()) throw IOException("could not delete $path")
    }

    override fun folders(): List<String> = folders(root(), "")

    private fun folders(
        folder: DocumentFile,
        prefix: String,
    ): List<String> =
        folder.listFiles().filter { it.isDirectory }.flatMap { child ->
            val name = child.name ?: return@flatMap emptyList()
            listOf("$prefix$name") + folders(child, "$prefix$name/")
        }

    override fun makeFolder(path: String) {
        path.split('/').fold(root(), ::subfolder)
    }

    override fun deleteFolder(path: String) {
        val folder = find(path)?.takeIf { it.isDirectory } ?: return
        if (folder.listFiles().isNotEmpty()) throw IOException("$path is not empty")
        if (!folder.delete()) throw IOException("could not delete $path")
    }

    private fun subfolder(
        folder: DocumentFile,
        name: String,
    ): DocumentFile =
        folder.findFile(name)?.takeIf { it.isDirectory } ?: folder.createDirectory(name) ?: throw IOException("could not make $name")

    private fun fileIn(
        folder: DocumentFile,
        name: String,
    ): DocumentFile = folder.findFile(name) ?: folder.createFile(MIME, name) ?: throw IOException("could not create $name")

    private fun find(path: String): DocumentFile? =
        path.split('/').fold<String, DocumentFile?>(root()) { folder, name -> folder?.findFile(name) }

    private companion object {
        const val MIME = "application/json"
    }
}

/**
 * Preset places on the phone: Settings' preset folder and mirror folder when the person picked them,
 * otherwise the app's own `presets` folder, where an older Geode kept its presets too.
 */
class AndroidPresetPlaces(
    private val context: Context,
    private val settings: Settings,
) : PresetPlaces {
    private val files = context.applicationContext.filesDir

    override val appStorage: PresetFolder = DirectoryPresetFolder(File(files, APP_FOLDER))

    override val backup: PresetFolder = DirectoryPresetFolder(File(files, BACKUP_FOLDER))

    override fun saved(): PresetFolder = folders().presetFolder?.let(::tree) ?: appStorage

    override fun mirror(): PresetFolder? = folders().presetMirror?.let(::tree)

    private fun tree(folder: FolderUri): PresetFolder = DocumentTreePresetFolder(context.applicationContext, Uri.parse(folder.value))

    private fun folders(): FolderSettings =
        when (val state = settings.state.value) {
            is SettingsState.Ready -> state.values.folders
            is SettingsState.Loading -> FolderSettings()
        }

    private companion object {
        const val APP_FOLDER = "presets"
        const val BACKUP_FOLDER = "presets-v1-backup"
    }
}

private const val JSON = "json"
