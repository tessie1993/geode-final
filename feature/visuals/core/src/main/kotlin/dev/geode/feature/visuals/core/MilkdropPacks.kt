package dev.geode.feature.visuals.core

import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.PackLimit
import dev.geode.feature.visuals.api.PackProblem

/** What to do with one file in a pack, by its path there (#399). */
sealed interface PackEntry {
    /** Read it as a file of [kind] named [name]; it sits in the pack's folders [folder], outermost first. */
    data class Take(
        val kind: MilkdropKind,
        val folder: List<String>,
        val name: String,
    ) : PackEntry

    /** Pass it over, and say so in the report. */
    data class Skip(
        val problem: PackProblem,
    ) : PackEntry

    /** Pass it over without a word: a folder, or what an operating system leaves in packs (`.DS_Store`, `__MACOSX`). */
    data object Ignore : PackEntry
}

/**
 * The rules for bringing in a whole pack of MilkDrop presets, a folder or a `.zip` (#399), as pure
 * functions: which of its files come in, and as what; how far a pack may go before the rest of it is
 * left unread; and which folders of the Visuals tab its presets are filed in. Each file that comes in
 * is then checked and named as a picked one is ([MilkdropFiles.plan]); only its path is the pack's.
 *
 * A path never decides where a file is written: the MilkDrop folder is flat, and a file keeps only its
 * name. A path that leads out of the pack is still refused, so no entry of a crafted zip is ever read
 * as though it belonged.
 */
object MilkdropPacks {
    /**
     * The most files and folders one pack may hold. The largest packs people share hold about ten
     * thousand presets; a zip of millions of empty entries stops here.
     */
    const val MAX_ENTRIES = 20_000

    /** The most one pack may unpack to, counted as it is read: far above real packs, far below a zip bomb. */
    const val MAX_BYTES = 512L * 1024 * 1024

    /** The deepest a file may sit in a pack, in folders and file name together. */
    const val MAX_DEPTH = 16

    /** What a pack's folder is called when its own name says nothing. */
    const val UNNAMED_PACK = "MilkDrop pack"

    private const val ZIP_EXTENSION = ".zip"
    private const val MAC_FORKS = "__MACOSX"
    private val drive = Regex("^[A-Za-z]:")

    /** What to do with the file at [path] in a pack, by the path alone. */
    fun sort(path: String): PackEntry {
        val segments = segments(path) ?: return PackEntry.Skip(PackProblem.UNSAFE_PATH)
        // A zip names a folder of its own with a slash at the end.
        if (path.endsWith('/') || path.endsWith('\\')) return PackEntry.Ignore
        if (segments.isEmpty() || segments.first() == MAC_FORKS || segments.any { it.startsWith(".") }) return PackEntry.Ignore
        val name = segments.last()
        val kind = kindOf(name) ?: return PackEntry.Skip(PackProblem.NOT_MILKDROP)
        return PackEntry.Take(kind, segments.dropLast(1), name)
    }

    /**
     * The folders and file name [path] names, outermost first, with empty and `.` steps left out; null
     * when it is not a plain path inside the pack: absolute, on a drive, stepping out with `..`,
     * deeper than [MAX_DEPTH], or holding a NUL. Either slash separates.
     */
    fun segments(path: String): List<String>? {
        val unified = path.replace('\\', '/')
        if ('\u0000' in unified || unified.startsWith("/") || drive.containsMatchIn(unified)) return null
        val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }
        return segments.takeIf { ".." !in it && it.size <= MAX_DEPTH }
    }

    private fun kindOf(name: String): MilkdropKind? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            extension == MilkdropFiles.PRESET_EXTENSION -> MilkdropKind.PRESETS
            extension in MilkdropFiles.TEXTURE_EXTENSIONS -> MilkdropKind.TEXTURES
            else -> null
        }
    }

    /**
     * The limit a pack has run into once [entries] of it have been met and [bytes] read, or null while
     * it is within both.
     */
    fun limitReached(
        entries: Int,
        bytes: Long,
    ): PackLimit? =
        when {
            entries > MAX_ENTRIES -> PackLimit.ENTRIES
            bytes > MAX_BYTES -> PackLimit.SIZE
            else -> null
        }

    /** The name a pack is filed under, from the name it was picked by: a zip without its `.zip`. */
    fun packName(picked: String): String {
        val trimmed = picked.trim()
        val bare = if (trimmed.endsWith(ZIP_EXTENSION, ignoreCase = true)) trimmed.dropLast(ZIP_EXTENSION.length) else trimmed
        return bare.trim().ifEmpty { UNNAMED_PACK }
    }

    /**
     * The folder of the Visuals tab each imported preset is filed in, outermost first: every preset
     * file of [placed] (with the pack folders it sat in) goes into a folder named for the pack, [pack]
     * (its name as picked), and the pack's own folders inside that. When every preset sits in one and
     * the same folder at the top of the pack, as a zip of one folder has it, that folder is the pack's
     * and stands in for its name.
     */
    fun folders(
        pack: String,
        placed: List<Pair<String, List<String>>>,
    ): Map<String, List<String>> {
        val top = placed.map { (_, folder) -> folder.firstOrNull() }.distinct().singleOrNull()
        val root = top ?: packName(pack)
        val inside = if (top == null) 0 else 1
        return placed.associate { (file, folder) -> file to listOf(root) + folder.drop(inside) }
    }
}
