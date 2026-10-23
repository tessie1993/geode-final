package dev.geode.feature.visuals.core

/** How the names of saved presets and their folders are cleaned, and how a taken name is avoided. */
object PresetNames {
    /** The longest name kept; anything longer is cut. */
    const val NAME_LIMIT = 60

    /** The name a shared preset is saved under when it carries none. */
    const val SHARED_NAME = "Shared preset"

    private val spaces = Regex("\\s+")

    /** Folder names are folders on the phone: anything but letters, digits, `-`, `_` and spaces becomes `_`. */
    private val unsafe = Regex("[^A-Za-z0-9-_ ]")

    /** [name] without stray spaces and cut to [NAME_LIMIT], or null when nothing is left. */
    fun preset(name: String): String? =
        name
            .trim()
            .replace(spaces, " ")
            .take(NAME_LIMIT)
            .ifEmpty { null }

    /** [name] cleaned as [preset] and made safe as a folder on the phone, or null when nothing is left. */
    fun folder(name: String): String? = preset(name)?.replace(unsafe, "_")

    /**
     * The name a shared preset named [wanted] is saved under: [wanted] cleaned (or [SHARED_NAME]), or,
     * when [taken] already has it, the first of "name 2", "name 3" … that is free. A saved preset is
     * never replaced by an import.
     */
    fun free(
        wanted: String,
        taken: Set<String>,
    ): String {
        val base = preset(wanted) ?: SHARED_NAME
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in taken }
    }
}
