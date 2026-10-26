package dev.geode.studio

import dev.geode.data.EditorProjectStore
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.handlers.edit.EditDrafts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The Timeline's cut (#318), kept where the old editor kept its one project: `files/editor/Untitled.json`,
 * so a cut made before the editors moved into Studio opens as it was. Read and written on [io].
 */
class AppEditDrafts(
    private val store: EditorProjectStore,
    private val io: CoroutineDispatcher,
) : EditDrafts {
    override suspend fun load(): EditorProject? = withContext(io) { store.load(DRAFT) }

    override suspend fun save(project: EditorProject) {
        val saved = withContext(io) { store.save(DRAFT, project) }
        check(saved) { "the cut could not be written" }
    }

    private companion object {
        /** The name the old editor gave its project. */
        const val DRAFT = "Untitled"
    }
}
