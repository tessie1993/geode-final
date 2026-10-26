package dev.geode.studio

import dev.geode.data.EditorProjectStore
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.handlers.edit.EditDrafts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The Timeline's cut (#318), kept where the old editor kept its project: `files/editor/Untitled.json`,
 * so a cut made before the editors moved into Studio opens as it was. The old editor's other projects
 * there open by their names (#347), read through the format's import of the old editor's version. Read
 * and written on [io].
 */
class AppEditDrafts(
    private val store: EditorProjectStore,
    private val io: CoroutineDispatcher,
) : EditDrafts {
    override suspend fun load(name: String?): EditorProject? = withContext(io) { store.load(name ?: DRAFT) }

    override suspend fun save(
        project: EditorProject,
        name: String?,
    ) {
        val saved = withContext(io) { store.save(name ?: DRAFT, project) }
        check(saved) { "the cut could not be written" }
    }

    override suspend fun names(): List<String> = withContext(io) { store.names().filter { it != DRAFT } }

    private companion object {
        /** The name the old editor gave its project. */
        const val DRAFT = "Untitled"
    }
}
