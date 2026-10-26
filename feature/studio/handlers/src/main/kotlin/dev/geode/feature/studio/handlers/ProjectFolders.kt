package dev.geode.feature.studio.handlers

/**
 * The Projects' folders inside the Save folder (owner decision on #340). [saveFolder] is the
 * document-tree uri Settings keeps; a folder or a file is a document uri. Any call may throw; the
 * handlers turn that into a named failure.
 */
interface ProjectFolders {
    /** The names of everything directly in the Save folder [saveFolder]. */
    suspend fun names(saveFolder: String): Set<String>

    /**
     * The folder [name] directly in the Save folder [saveFolder], made first when it is missing and
     * [make] is true; null when it is missing and not made.
     */
    suspend fun folder(
        saveFolder: String,
        name: String,
        make: Boolean,
    ): String?

    /** Renames [folder] to [name]: what it is now, and the new uri of each file directly in it by its old uri. */
    suspend fun rename(
        folder: String,
        name: String,
    ): RenamedFolder

    /** Moves those of [files] that are directly in the folder [from] into the folder [to]: their new uris, by their old. */
    suspend fun move(
        files: Collection<String>,
        from: String,
        to: String,
    ): Map<String, String>

    /**
     * Deletes everything in [folder] except the files in [keep], then [folder] itself when nothing is
     * left in it; whether it is gone.
     */
    suspend fun delete(
        folder: String,
        keep: Set<String>,
    ): Boolean

    companion object {
        /** No folders can be made: every video goes where its maker puts it, as before #340. */
        val None: ProjectFolders =
            object : ProjectFolders {
                override suspend fun names(saveFolder: String): Set<String> = emptySet()

                override suspend fun folder(
                    saveFolder: String,
                    name: String,
                    make: Boolean,
                ): String? = null

                override suspend fun rename(
                    folder: String,
                    name: String,
                ): RenamedFolder = RenamedFolder(folder, name, emptyMap())

                override suspend fun move(
                    files: Collection<String>,
                    from: String,
                    to: String,
                ): Map<String, String> = emptyMap()

                override suspend fun delete(
                    folder: String,
                    keep: Set<String>,
                ): Boolean = true
            }
    }
}

/** A renamed folder: its uri and [name] now, and the new uri of each file it held by its old one. */
data class RenamedFolder(
    val folder: String,
    val name: String,
    val relinked: Map<String, String>,
)
