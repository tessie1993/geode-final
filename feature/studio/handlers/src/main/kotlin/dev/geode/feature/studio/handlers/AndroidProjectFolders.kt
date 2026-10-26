package dev.geode.feature.studio.handlers

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException

/**
 * The Projects' folders on the phone (#340), in the Save folder's document tree through the Storage
 * Access Framework. Files are told apart by document id, so a uri saved in another form still matches.
 * The handlers call it off the main thread.
 */
class AndroidProjectFolders(
    private val resolver: ContentResolver,
) : ProjectFolders {
    /** One document directly in a folder. */
    private class Child(
        val uri: Uri,
        val id: String,
        val name: String,
        val isFolder: Boolean,
    )

    override suspend fun names(saveFolder: String): Set<String> = childrenOf(rootOf(saveFolder)).mapTo(HashSet()) { it.name }

    override suspend fun folder(
        saveFolder: String,
        name: String,
        make: Boolean,
    ): String? {
        val root = rootOf(saveFolder)
        val children = childrenOf(root).filter { it.isFolder }
        val found = children.firstOrNull { it.name == name } ?: children.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (found != null || !make) return found?.uri?.toString()
        val made = DocumentsContract.createDocument(resolver, root, Document.MIME_TYPE_DIR, name) ?: throw IOException("no folder $name")
        return made.toString()
    }

    override suspend fun rename(
        folder: String,
        name: String,
    ): RenamedFolder {
        val before = childrenOf(Uri.parse(folder))
        val renamed = DocumentsContract.renameDocument(resolver, Uri.parse(folder), name) ?: throw IOException("not renamed to $name")
        val after = childrenOf(renamed).associateBy { it.name }
        val relinked = before.mapNotNull { old -> after[old.name]?.let { new -> old.uri.toString() to new.uri.toString() } }.toMap()
        return RenamedFolder(renamed.toString(), nameOf(renamed) ?: name, relinked.filter { (old, new) -> old != new })
    }

    override suspend fun move(
        files: Collection<String>,
        from: String,
        to: String,
    ): Map<String, String> {
        val source = Uri.parse(from)
        val target = Uri.parse(to)
        val inside = childrenOf(source).associateBy { it.id }
        val moved = LinkedHashMap<String, String>()
        for (file in files) {
            val child = idOf(file)?.let(inside::get) ?: continue
            // A provider that cannot move leaves the file where it is; the folder then is not deleted.
            runCatching { DocumentsContract.moveDocument(resolver, child.uri, source, target) }
                .getOrNull()
                ?.let { moved[file] = it.toString() }
        }
        return moved
    }

    override suspend fun delete(
        folder: String,
        keep: Set<String>,
    ): Boolean {
        val uri = Uri.parse(folder)
        val kept = keep.mapNotNullTo(HashSet()) { idOf(it) }
        return emptied(uri, kept) && DocumentsContract.deleteDocument(resolver, uri)
    }

    /** Deletes everything in [folder] whose id is not in [kept]; whether nothing is left. */
    private fun emptied(
        folder: Uri,
        kept: Set<String>,
    ): Boolean =
        childrenOf(folder)
            .map { child ->
                when {
                    child.id in kept -> false
                    child.isFolder -> emptied(child.uri, kept) && deleted(child.uri)
                    else -> deleted(child.uri)
                }
            }.all { it }

    private fun deleted(document: Uri): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, document) }.getOrDefault(false)

    /** The Save folder's own document, under its tree. */
    private fun rootOf(saveFolder: String): Uri {
        val tree = Uri.parse(saveFolder)
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    private fun childrenOf(folder: Uri): List<Child> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val found = mutableListOf<Child>()
        resolver.query(children, columns, null, null, null)?.use { row ->
            while (row.moveToNext()) {
                val id = row.getString(0)
                val uri = DocumentsContract.buildDocumentUriUsingTree(folder, id)
                found += Child(uri, id, row.getString(1).orEmpty(), row.getString(2) == Document.MIME_TYPE_DIR)
            }
        } ?: throw IOException("cannot list $folder")
        return found
    }

    private fun nameOf(document: Uri): String? =
        resolver.query(document, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { row ->
            if (row.moveToFirst()) row.getString(0) else null
        }

    private fun idOf(file: String): String? = runCatching { DocumentsContract.getDocumentId(Uri.parse(file)) }.getOrNull()
}
