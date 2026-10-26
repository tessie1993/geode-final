package dev.geode.feature.visuals.core

import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.StyleMark

/**
 * How the person's library is saved: plain text, one fact per line, under a first line naming the
 * format and its version (ADR-0004). A Style id never holds a space and a folder name never holds a
 * line break, so nothing needs escaping:
 *
 * ```
 * geode-style-library 1
 * favourite <style>
 * recent <style>
 * folder <id> <parent id, or - at the top> <name>
 * style <folder id> <style>
 * mark <style> <love or never>
 * ```
 *
 * A Style with no mark line is Normal (#308). An older Geode skips mark lines, as any line it does not
 * understand, so the version stays 1.
 *
 * Reading is forgiving: a line it does not understand (broken, or from a newer version) is skipped
 * and the rest is kept, and a folder whose parent is missing or that loops comes back at the top.
 * Text that is not a library at all reads as an empty one.
 */
object StyleLibraryCodec {
    /** The version this build writes. */
    const val VERSION = 1

    private const val HEADER = "geode-style-library"
    private const val FAVOURITE = "favourite"
    private const val RECENT = "recent"
    private const val FOLDER = "folder"
    private const val STYLE = "style"
    private const val MARK = "mark"
    private const val TOP = "-"

    fun encode(library: StyleLibrary): String =
        buildList {
            add("$HEADER $VERSION")
            library.favourites.forEach { add("$FAVOURITE $it") }
            library.recent.forEach { add("$RECENT $it") }
            for (folder in library.folders) {
                add("$FOLDER ${folder.id.value} ${folder.parent?.value ?: TOP} ${folder.name}")
                folder.styles.forEach { add("$STYLE ${folder.id.value} $it") }
            }
            library.marks.filterValues { it != StyleMark.NORMAL }.forEach { (style, mark) -> add("$MARK $style ${mark.name.lowercase()}") }
        }.joinToString("\n")

    fun decode(text: String): StyleLibrary {
        val lines = text.lines()
        val version =
            lines
                .first()
                .split(' ')
                .takeIf { it.size == 2 && it[0] == HEADER }
                ?.get(1)
                ?.toIntOrNull()
        if (version == null || version < VERSION) return StyleLibrary()
        val facts = lines.drop(1).map { it.split(' ', limit = FOLDER_PARTS) }
        val folders = folders(facts)
        val styles = facts.filter { it.size == 3 && it[0] == STYLE }
        return StyleLibrary(
            favourites = styleIds(facts, FAVOURITE),
            recent = styleIds(facts, RECENT).take(StyleLibrary.RECENT_LIMIT),
            folders =
                folders.map { folder ->
                    folder.copy(styles = styles.filter { it[1] == folder.id.value.toString() }.mapNotNull { styleId(it[2]) }.distinct())
                },
            marks = marks(facts),
        )
    }

    /** The mark lines: Love and Never; a broken or unknown mark is skipped, and the first for a Style wins. */
    private fun marks(facts: List<List<String>>): Map<SceneId, StyleMark> =
        facts
            .filter { it.size == 3 && it[0] == MARK }
            .mapNotNull { fact ->
                val style = styleId(fact[1]) ?: return@mapNotNull null
                val mark = MARKS[fact[2]] ?: return@mapNotNull null
                style to mark
            }.distinctBy { it.first }
            .toMap()

    private fun styleIds(
        facts: List<List<String>>,
        kind: String,
    ): List<SceneId> = facts.filter { it.size == 2 && it[0] == kind }.mapNotNull { styleId(it[1]) }.distinct()

    private fun styleId(text: String): SceneId? = text.takeIf { it.isNotEmpty() }?.let(::SceneId)

    private fun folders(facts: List<List<String>>): List<Folder> {
        val read =
            facts
                .filter { it.size == FOLDER_PARTS && it[0] == FOLDER && it[3].isNotBlank() }
                .mapNotNull { fact ->
                    val id = fact[1].toLongOrNull() ?: return@mapNotNull null
                    val parent = if (fact[2] == TOP) null else fact[2].toLongOrNull() ?: return@mapNotNull null
                    Folder(FolderId(id), fact[3], parent?.let(::FolderId), emptyList())
                }.distinctBy { it.id }
        val parents = read.associate { it.id to it.parent }.toMutableMap()
        for (folder in read) {
            if (!reachesTop(folder.id, parents)) parents[folder.id] = null
        }
        return read.map { it.copy(parent = parents[it.id]) }
    }

    /** Whether going up from [id] reaches the top, rather than a missing folder or [id] itself again. */
    private fun reachesTop(
        id: FolderId,
        parents: Map<FolderId, FolderId?>,
    ): Boolean {
        val seen = mutableSetOf(id)
        var at = parents[id]
        while (at != null) {
            if (at !in parents || !seen.add(at)) return false
            at = parents[at]
        }
        return true
    }

    /** A folder line's parts: the word, its id, its parent and its name. */
    private const val FOLDER_PARTS = 4

    /** The marks a mark line may hold, by the word it is written as; Normal is never written. */
    private val MARKS: Map<String, StyleMark> = listOf(StyleMark.LOVE, StyleMark.NEVER).associateBy { it.name.lowercase() }
}
