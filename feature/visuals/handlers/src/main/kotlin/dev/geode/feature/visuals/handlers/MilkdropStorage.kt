package dev.geode.feature.visuals.handlers

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.core.PickedFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * The MilkDrop folder (#145): presets at its top, the textures they sample in its `textures` folder.
 * Any call may throw; the handlers turn that into a named failure.
 */
interface MilkdropFolder {
    /** The names of the files of [kind] in the folder. */
    suspend fun files(kind: MilkdropKind): List<String>

    /** Writes [bytes] as the file [name] of [kind], whole or not at all, never over a file already there. */
    suspend fun write(
        kind: MilkdropKind,
        name: String,
        bytes: ByteArray,
    )

    /** Deletes the file [name] of [kind]; one already gone is no failure (#360). */
    suspend fun delete(
        kind: MilkdropKind,
        name: String,
    )

    /**
     * Runs [block], a run of writes made as one, such as a whole pack's (#399): a folder that does work
     * after each write may do it once, after the last, whether [block] finishes or throws.
     */
    suspend fun <T> batch(block: suspend () -> T): T = block()
}

/** Reads what the person picked through the system picker. */
fun interface PickedFiles {
    /** Reads the file at [uri] (a content uri), at most [limit] bytes of it. Never throws: a failure is [PickedFile.Unreadable]. */
    suspend fun read(
        uri: String,
        limit: Int,
    ): PickedFile
}

/**
 * The MilkDrop folder on the phone, where the engine reads presets and textures from: `files/milk` in
 * the app's own storage, the folder the old importers wrote to and the built-in presets are installed
 * into. A file is written beside its final name first and then renamed into place, so a write that
 * fails part way leaves nothing behind.
 */
class FileMilkdropFolder(
    private val root: File,
) : MilkdropFolder {
    override suspend fun files(kind: MilkdropKind): List<String> =
        dir(kind)
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .map { it.name }

    override suspend fun write(
        kind: MilkdropKind,
        name: String,
        bytes: ByteArray,
    ) {
        val dir = dir(kind)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make $dir")
        val target = File(dir, name)
        if (target.exists()) throw IOException("$name is already there")
        val partial = File(dir, ".$name.partial")
        try {
            partial.writeBytes(bytes)
            if (!partial.renameTo(target)) throw IOException("cannot move $name into place")
        } finally {
            partial.delete()
        }
    }

    override suspend fun delete(
        kind: MilkdropKind,
        name: String,
    ) {
        val target = File(dir(kind), name)
        // Only a file directly in the folder: never a path out of it, never a folder.
        if (name != target.name || name.startsWith(".")) throw IOException("$name is not a MilkDrop file")
        if (target.isDirectory) throw IOException("$name is a folder")
        if (target.exists() && !target.delete()) throw IOException("cannot delete $name")
    }

    private fun dir(kind: MilkdropKind): File =
        when (kind) {
            MilkdropKind.PRESETS -> root
            MilkdropKind.TEXTURES -> File(root, TEXTURES)
        }

    companion object {
        /** The folder under the app's files the MilkDrop folder is. */
        const val FOLDER = "milk"

        /** The folder inside it textures are kept in. */
        const val TEXTURES = "textures"

        /** The app's MilkDrop folder. */
        fun of(context: Context): FileMilkdropFolder = FileMilkdropFolder(File(context.applicationContext.filesDir, FOLDER))
    }
}

/**
 * Reads picked files through the [ContentResolver], named as the picker shows them. A file the provider
 * says is too large is refused before it is read; otherwise reading stops at the limit, so a huge pick
 * never fills the heap.
 */
class ContentPickedFiles(
    context: Context,
) : PickedFiles {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun read(
        uri: String,
        limit: Int,
    ): PickedFile {
        val parsed = Uri.parse(uri)
        val name = query(parsed, OpenableColumns.DISPLAY_NAME) { getString(0) } ?: parsed.lastPathSegment?.substringAfterLast('/').orEmpty()
        return try {
            val declared = query(parsed, OpenableColumns.SIZE) { getLong(0) }
            if (declared != null && declared > limit) {
                PickedFile.TooLarge(name)
            } else {
                resolver.openInputStream(parsed)?.use { input -> bounded(name, limit) { input.read(it) } } ?: PickedFile.Unreadable(name)
            }
        } catch (thrown: Exception) {
            PickedFile.Unreadable(name)
        }
    }

    /** Reads through [read] into memory, or gives up once past [limit]. */
    private inline fun bounded(
        name: String,
        limit: Int,
        read: (ByteArray) -> Int,
    ): PickedFile {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val count = read(buffer)
            if (count < 0) return PickedFile.Read(name, out.toByteArray())
            out.write(buffer, 0, count)
            if (out.size() > limit) return PickedFile.TooLarge(name)
        }
    }

    /** The provider's [column] for [uri], read by [get]; null when it has none or cannot say. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun <T> query(
        uri: Uri,
        column: String,
        get: Cursor.() -> T,
    ): T? =
        try {
            resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.get() else null
            }
        } catch (thrown: Exception) {
            null
        }

    private companion object {
        const val READ_BUFFER_BYTES = 64 * 1024
    }
}
