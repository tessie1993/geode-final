package dev.geode.feature.visuals.handlers

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.geode.feature.visuals.api.MilkdropPackType
import dev.geode.feature.visuals.api.PackLimit
import dev.geode.feature.visuals.core.MilkdropPacks
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/** One file of a pack, as a walk meets it (#399). */
interface PackFile {
    /** Where it sits in the pack, folders and name, as the pack names it; not yet checked ([MilkdropPacks.sort]). */
    val path: String

    /**
     * Its bytes, at most [limit] of them: null when it is larger, or when the pack has run out of room
     * for it ([MilkdropPacks.MAX_BYTES]). Throws when it cannot be read.
     */
    suspend fun read(limit: Int): ByteArray?
}

/** Reads a pack of MilkDrop presets the person picked, a folder or a zip (#399). */
interface PickedPacks {
    /** The name the pack was picked by (a folder's, or a zip file's); never throws. */
    suspend fun name(
        type: MilkdropPackType,
        uri: String,
    ): String

    /**
     * Hands each file of the pack at [uri] to [visit] in turn, within the pack limits, and says which one
     * it ran into, if it did; nothing after that is read. Throws when the pack cannot be read at all, or
     * stops being readable part way (the files visited before that stay visited).
     */
    suspend fun walk(
        type: MilkdropPackType,
        uri: String,
        visit: suspend (PackFile) -> Unit,
    ): PackLimit?
}

/**
 * How much of one pack has been met and read, against the pack limits ([limitOf], by default
 * [MilkdropPacks.limitReached]). Every entry met counts, folders and files passed over too, and so
 * does every byte read or unpacked, so neither a zip of millions of entries nor one that unpacks to
 * gigabytes gets far. Once a limit is reached it stays [stopped].
 */
class PackBudget(
    private val limitOf: (entries: Int, bytes: Long) -> PackLimit? = MilkdropPacks::limitReached,
) {
    private var entries = 0
    private var bytes = 0L

    /** The limit reached, or null while within both. */
    var stopped: PackLimit? = null
        private set

    /** Counts one more entry met; false once a limit is reached. */
    fun enter(): Boolean {
        entries++
        return within()
    }

    /** Counts [count] more bytes read; false once a limit is reached. */
    fun take(count: Int): Boolean {
        bytes += count
        return within()
    }

    private fun within(): Boolean {
        if (stopped == null) stopped = limitOf(entries, bytes)
        return stopped == null
    }
}

/**
 * Reads this stream to its end into memory, at most [limit] bytes, counting each byte in [budget]: null
 * once it runs past [limit] or the budget runs out, with the rest left unread.
 */
internal fun InputStream.readWithin(
    limit: Int,
    budget: PackBudget,
): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(PACK_BUFFER_BYTES)
    while (true) {
        val count = read(buffer)
        if (count < 0) return out.toByteArray()
        if (!budget.take(count)) return null
        out.write(buffer, 0, count)
        if (out.size() > limit) return null
    }
}

/** Reads this stream to its end and drops what it reads, counting each byte in [budget]; false once the budget runs out. */
internal fun InputStream.skipWithin(budget: PackBudget): Boolean {
    val buffer = ByteArray(PACK_BUFFER_BYTES)
    while (true) {
        val count = read(buffer)
        if (count < 0) return true
        if (!budget.take(count)) return false
    }
}

private const val PACK_BUFFER_BYTES = 64 * 1024

/**
 * A zip pack, read as it streams in with `java.util.zip` (#399). Each entry is unpacked once, in the
 * order the zip holds them; one [visit] does not read is still unpacked to reach the next, and counted
 * in [budget], so the pack limits hold whatever the zip claims about its sizes. Entry names a zip
 * marks as UTF-8 are read so; any other, as old Windows tools wrote them, in code page 437.
 */
class ZipPack(
    private val input: InputStream,
    private val budget: PackBudget,
) {
    /** Walks the zip; throws when it is not one, or breaks part way. */
    suspend fun walk(visit: suspend (PackFile) -> Unit) {
        ZipInputStream(input, legacyNames).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return
                if (!budget.enter()) return
                if (!entry.isDirectory) visit(Entry(entry.name, zip))
                // What the visit left unread is unpacked here, so it counts too.
                if (!zip.skipWithin(budget)) return
            }
        }
    }

    private inner class Entry(
        override val path: String,
        private val zip: ZipInputStream,
    ) : PackFile {
        override suspend fun read(limit: Int): ByteArray? = zip.readWithin(limit, budget)
    }

    private companion object {
        /** How a zip names its entries when it does not mark them UTF-8: code page 437, where the phone has it. */
        val legacyNames: Charset = runCatching { Charset.forName("IBM437") }.getOrDefault(Charsets.ISO_8859_1)
    }
}

/**
 * Reads packs through the [ContentResolver] (#399): a zip by its document uri, a folder by the document
 * tree uri the system folder picker gives, walked folder by folder through [DocumentsContract]. No
 * permission is needed beyond what the picker grants.
 */
class ContentPickedPacks(
    context: Context,
) : PickedPacks {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun name(
        type: MilkdropPackType,
        uri: String,
    ): String {
        val parsed = Uri.parse(uri)
        val named =
            try {
                val document =
                    when (type) {
                        MilkdropPackType.ZIP -> parsed
                        MilkdropPackType.FOLDER ->
                            DocumentsContract.buildDocumentUriUsingTree(
                                parsed,
                                DocumentsContract.getTreeDocumentId(parsed),
                            )
                    }
                displayName(document)
            } catch (thrown: Exception) {
                null
            }
        // A tree's last segment is its document id, such as "primary:Download/Pack": the name is after the last separator.
        return named ?: parsed.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            .orEmpty()
    }

    override suspend fun walk(
        type: MilkdropPackType,
        uri: String,
        visit: suspend (PackFile) -> Unit,
    ): PackLimit? {
        val budget = PackBudget()
        val parsed = Uri.parse(uri)
        when (type) {
            MilkdropPackType.ZIP ->
                resolver.openInputStream(parsed)?.use { ZipPack(it.buffered(), budget).walk(visit) }
                    ?: throw IOException("cannot open $uri")
            MilkdropPackType.FOLDER -> walkTree(parsed, budget, visit)
        }
        return budget.stopped
    }

    /** Walks the folder [tree] breadth first, never deeper than a pack's files may sit ([MilkdropPacks.MAX_DEPTH]). */
    private suspend fun walkTree(
        tree: Uri,
        budget: PackBudget,
        visit: suspend (PackFile) -> Unit,
    ) {
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to ""))
        while (pending.isNotEmpty()) {
            val (folder, prefix) = pending.removeFirst()
            for (child in children(tree, folder)) {
                if (!budget.enter()) return
                val path = prefix + child.name
                when {
                    !child.folder -> visit(TreeFile(path, DocumentsContract.buildDocumentUriUsingTree(tree, child.id), child.size, budget))
                    path.count { it == '/' } + 1 < MilkdropPacks.MAX_DEPTH -> pending.addLast(child.id to "$path/")
                }
            }
        }
    }

    /** The documents directly inside [folder] of [tree], by name; throws when the provider will not list it. */
    private fun children(
        tree: Uri,
        folder: String,
    ): List<Child> {
        val columns =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
        val listed = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder)
        val cursor = resolver.query(listed, columns, null, null, null) ?: throw IOException("cannot list $listed")
        val children = mutableListOf<Child>()
        cursor.use {
            while (it.moveToNext()) {
                val size = if (it.isNull(SIZE_COLUMN)) UNKNOWN_SIZE else it.getLong(SIZE_COLUMN)
                val folderType = it.getString(MIME_COLUMN) == DocumentsContract.Document.MIME_TYPE_DIR
                children += Child(it.getString(ID_COLUMN), it.getString(NAME_COLUMN).orEmpty(), folderType, size)
            }
        }
        return children.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun displayName(document: Uri): String? =
        resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    /** One document of a folder pack: [size] as its provider says, or [UNKNOWN_SIZE]. */
    private class Child(
        val id: String,
        val name: String,
        val folder: Boolean,
        val size: Long,
    )

    private inner class TreeFile(
        override val path: String,
        private val document: Uri,
        private val size: Long,
        private val budget: PackBudget,
    ) : PackFile {
        override suspend fun read(limit: Int): ByteArray? {
            // A file its provider says is too large is passed over unread.
            if (size > limit) return null
            val input = resolver.openInputStream(document) ?: throw IOException("cannot open $path")
            return input.use { it.readWithin(limit, budget) }
        }
    }

    private companion object {
        const val ID_COLUMN = 0
        const val NAME_COLUMN = 1
        const val MIME_COLUMN = 2
        const val SIZE_COLUMN = 3
        const val UNKNOWN_SIZE = -1L
    }
}
