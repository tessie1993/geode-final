package dev.geode.feature.studio.handlers

import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.core.EditEvent
import dev.geode.feature.studio.core.EditWork
import dev.geode.feature.studio.core.StudioEvent
import dev.geode.feature.studio.core.StudioFailure
import dev.geode.feature.studio.core.relinked
import dev.geode.feature.studio.core.sources
import dev.geode.feature.studio.handlers.edit.EditTools
import dev.geode.kernel.Results
import kotlinx.coroutines.CancellationException

/**
 * Carries out the editors' work (#318): reading a picked video, keeping the Timeline's cut, and saving
 * an Edit's export to the render queue. With Settings' Copy, the Edit's videos are copied into the Save
 * folder first and the Edit points at the copies; with Reference nothing is copied. An Edit is queued
 * with no look: it is cut from videos, never drawn. Every failure is caught here and reported by name.
 */
internal class EditHandlers(
    private val sources: StudioSources,
) {
    private val tools: EditTools get() = sources.edits

    suspend fun run(
        work: EditWork,
        results: Results<StudioEvent>,
    ) {
        when (work) {
            is EditWork.ReadSource -> results.report(EditEvent.SourceRead(attempt { tools.videos.read(work.file) }))
            EditWork.LoadCut -> load(results)
            is EditWork.SaveCut ->
                if (attempt { tools.drafts.save(work.project) } == null) results.report(StudioEvent.Failed(StudioFailure.SAVE_CUT))
            is EditWork.QueueEdit -> queue(work, results)
        }
    }

    /** The saved cut, or an empty one when there is none; one that cannot be read is reported too. */
    private suspend fun load(results: Results<StudioEvent>) {
        val loaded = attempt { Loaded(tools.drafts.load()) }
        if (loaded == null) results.report(StudioEvent.Failed(StudioFailure.LOAD_CUT))
        results.report(EditEvent.CutLoaded(loaded?.project ?: EditorProject()))
    }

    private suspend fun queue(
        work: EditWork.QueueEdit,
        results: Results<StudioEvent>,
    ) {
        val edit = work.render.edit ?: return
        val kept = if (edit.sources == EditSources.COPY) attempt { copied(edit, work.folder) } else edit
        if (kept == null) return results.report(StudioEvent.Failed(StudioFailure.COPY_SOURCES))
        val queued = attempt { sources.storage.queueRenders(listOf(work.render.copy(edit = kept)), NO_LOOK, sources.now()) }
        results.report(if (queued == null) StudioEvent.Failed(StudioFailure.QUEUE_RENDER) else StudioEvent.RendersQueued(queued))
    }

    /** [edit] pointing at copies of its videos in [folder], which must be the Save folder. */
    private suspend fun copied(
        edit: EditJob,
        folder: String?,
    ): EditJob {
        val into = requireNotNull(folder) { "no save folder to copy into" }
        val copies = edit.cut.sources().associateWith { tools.videos.copy(it, into) }
        return edit.copy(cut = edit.cut.relinked(copies))
    }

    /** [block]'s result, or null when it threw. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Handlers catch everything at their edge (ADR-0005).
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            null
        }

    /** A cut read back: [project] is null when none was kept. */
    private class Loaded(
        val project: EditorProject?,
    )

    private companion object {
        /** An Edit has no look to freeze. */
        val NO_LOOK = FrozenLook("")
    }
}
