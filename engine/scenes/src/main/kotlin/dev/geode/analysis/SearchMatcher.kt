package dev.geode.analysis

import dev.geode.engine.types.TrackId

object SearchMatcher {
    const val TRACK_LIMIT = 30

    fun terms(query: String): List<String> = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    fun matches(
        terms: List<String>,
        fields: List<String>,
    ): Boolean = terms.isNotEmpty() && terms.all { term -> fields.any { it.contains(term, ignoreCase = true) } }

    fun <T> filterTracks(
        terms: List<String>,
        items: List<T>,
        max: Int = TRACK_LIMIT,
        uriOf: (T) -> TrackId,
        fieldsOf: (T) -> List<String>,
        preferred: (T) -> Boolean = { false },
    ): List<T> {
        if (terms.isEmpty()) return emptyList()
        val byUri = LinkedHashMap<TrackId, T>()
        for (item in items) {
            if (!matches(terms, fieldsOf(item))) continue
            val uri = uriOf(item)
            val current = byUri[uri]
            if (current == null || (!preferred(current) && preferred(item))) byUri[uri] = item
        }
        return byUri.values.take(max)
    }
}
