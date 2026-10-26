package dev.geode.feature.studio.core

/**
 * The name a Project's folder gets inside the Save folder (#340): the Project's name, made safe for
 * any folder the system picker offers (SD cards included), and numbered when the Save folder already
 * holds something of that name, so a Project never takes over a folder that is not its own.
 */
object ProjectFolderNames {
    /** The longest folder name made, in UTF-16 units. */
    const val MAX_LENGTH = 64

    /** What a Project whose name leaves nothing usable has its folder called. */
    const val FALLBACK = "Project"

    private val forbidden = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val spaces = Regex("""\s+""")
    private const val EDGES = " ."

    /**
     * The folder name for a Project named [project], unlike any of [taken] (the names already in the
     * Save folder) whatever their case: the cleaned name, or it numbered from 2.
     */
    fun of(
        project: String,
        taken: Collection<String>,
    ): String {
        val base = clean(project)
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        if (base.lowercase() !in used) return base
        return generateSequence(2) { it + 1 }
            .map { "$base ($it)" }
            .first { it.lowercase() !in used }
    }

    /**
     * [name] as a folder name: characters a folder cannot hold become `_`, runs of spaces become one,
     * leading and trailing dots and spaces go, and it is cut to [MAX_LENGTH]; [FALLBACK] when nothing
     * is left.
     */
    fun clean(name: String): String {
        val replaced = forbidden.replace(name, "_").replace(spaces, " ").trim { it in EDGES }
        // Cut where it would split a character in two, the half is dropped.
        val cut = replaced.take(MAX_LENGTH).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        return cut.trimEnd { it in EDGES }.ifEmpty { FALLBACK }
    }
}
