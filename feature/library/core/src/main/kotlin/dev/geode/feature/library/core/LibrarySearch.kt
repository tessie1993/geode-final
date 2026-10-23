package dev.geode.feature.library.core

/**
 * The Library's search, as the old Library searched (#17 keeps its specs): every word typed must be
 * found in one of the fields, so more words narrow rather than widen, and a longer word may be
 * misspelt a little.
 */
internal object LibrarySearch {
    /** The words of [query]; none when it is blank. */
    fun terms(query: String): List<String> = query.trim().split(WORD_SPLIT).filter { it.isNotBlank() }

    /** Whether every one of [terms] is found in [fields]. */
    fun matches(
        terms: List<String>,
        fields: List<String>,
    ): Boolean = terms.all { term -> matches(term, fields) }

    /**
     * Whether [term] is in one of [fields], allowing for a typo: a word of four or five letters may be
     * one edit away, a longer one two; a shorter one must be typed as it is.
     */
    private fun matches(
        term: String,
        fields: List<String>,
    ): Boolean {
        if (fields.any { it.contains(term, ignoreCase = true) }) return true
        val budget = typoBudget(term)
        return budget > 0 &&
            fields.any { field ->
                field.split(WORD_SPLIT).any { word -> word.isNotBlank() && withinEditDistance(term, word, budget) }
            }
    }

    private fun typoBudget(term: String): Int =
        when {
            term.length <= SHORT_WORD -> 0
            term.length <= MEDIUM_WORD -> 1
            else -> 2
        }

    /** Whether [a] and [b] are at most [max] edits apart, ignoring case; it stops as soon as they cannot be. */
    fun withinEditDistance(
        a: String,
        b: String,
        max: Int,
    ): Boolean {
        if (a.length - b.length > max || b.length - a.length > max) return false
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        var rowMin = 0
        for (i in 1..a.length) {
            cur[0] = i
            rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1].equals(b[j - 1], ignoreCase = true)) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) break
            val swap = prev
            prev = cur
            cur = swap
        }
        // A row whose cheapest path is already over the budget only gets dearer, so the loop leaves
        // `prev` on the last row worth reading and `rowMin` saying whether it gave up.
        return rowMin <= max && prev[b.length] <= max
    }

    private const val SHORT_WORD = 3
    private const val MEDIUM_WORD = 5
    private val WORD_SPLIT = Regex("""[\s\-_./]+""")
}

/**
 * Folder names as the Folders view lists them (the old folder tree's labels): each by its last part,
 * with more of its path wherever two would read the same, and the whole path when even that does not
 * tell them apart.
 */
internal object FolderLabels {
    fun of(folders: Collection<String>): Map<String, String> {
        val segments = folders.associateWith { path -> path.split('/').filter(String::isNotEmpty) }
        val depths = folders.associateWithTo(HashMap()) { 1 }
        var widening = true
        while (widening) widening = widen(segments, depths)
        val labels = folders.associateWith { path -> label(path, segments.getValue(path), depths.getValue(path)) }
        val clashing =
            labels.entries
                .groupBy { it.value }
                .filterValues { it.size > 1 }
                .values
                .flatten()
                .map { it.key }
                .toSet()
        return labels.mapValues { (path, label) -> if (path in clashing) path else label }
    }

    private fun widen(
        segments: Map<String, List<String>>,
        depths: MutableMap<String, Int>,
    ): Boolean {
        var widened = false
        val sharing = depths.keys.groupBy { label(it, segments.getValue(it), depths.getValue(it)) }
        for (paths in sharing.values) {
            if (paths.size < 2) continue
            for (path in paths) {
                if (depths.getValue(path) >= segments.getValue(path).size) continue
                depths[path] = depths.getValue(path) + 1
                widened = true
            }
        }
        return widened
    }

    private fun label(
        path: String,
        segments: List<String>,
        depth: Int,
    ): String = if (segments.isEmpty()) path else segments.takeLast(depth).joinToString("/")
}
