package dev.geode.feature.studio.handlers

import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.core.ProjectFolderNames
import dev.geode.feature.studio.core.StudioEvent
import dev.geode.feature.studio.core.StudioFailure
import dev.geode.feature.studio.core.StudioWork
import dev.geode.kernel.Results
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps each Project's folder in the Save folder (owner decision on #340): made when the Project is, or
 * later for a Project that had none; renamed after it; filled with the files of videos moved into it;
 * and deleted with the Project, except for a file another video still uses. A folder is named by
 * [ProjectFolderNames], never after something already in the Save folder, so a Project only ever
 * deletes a folder it made. One change at a time, so two never pick the same name.
 */
internal class ProjectFolderHandlers(
    private val storage: StudioStorage,
    private val folders: ProjectFolders,
    private val files: VideoFiles,
) {
    private val lock = Mutex()

    /** A Project's folder: its document [uri] and [name], and whether it was [named] just now. */
    private class FolderRef(
        val uri: String,
        val name: String,
        val named: Boolean,
    ) {
        fun change(project: ProjectId): Map<ProjectId, String?> = if (named) mapOf(project to name) else emptyMap()
    }

    /** Makes a folder for every Project that has none, oldest first; reports their names, and any it could not make. */
    suspend fun makeAll(
        saveFolder: String,
        results: Results<StudioEvent>,
    ) {
        val named = LinkedHashMap<ProjectId, String?>()
        val whole = lock.withLock { caught { makeMissing(saveFolder, named) } }
        if (named.isNotEmpty()) results.report(StudioEvent.ProjectFoldersChanged(named))
        if (whole != true) results.report(StudioEvent.Failed(StudioFailure.PROJECT_FOLDER))
    }

    /** Makes the missing folders, the oldest Project's first, noting each in [named]; whether every one was made. */
    private suspend fun makeMissing(
        saveFolder: String,
        named: MutableMap<ProjectId, String?>,
    ): Boolean {
        val missing = storage.projects().filter { it.folder == null }.sortedWith(compareBy({ it.createdAt.value }, { it.id.value }))
        var whole = true
        for (project in missing) {
            val made = caught { makeNamed(saveFolder, project) }
            if (made == null) whole = false else named[project.id] = made.name
        }
        return whole
    }

    /** [project] as it was just created, with the folder made for it in [saveFolder] when there is one. */
    suspend fun created(
        saveFolder: String?,
        project: Project,
        results: Results<StudioEvent>,
    ): Project {
        if (saveFolder == null) return project
        val folder = lock.withLock { caught { folderOf(saveFolder, project.id) } }
        if (folder == null) results.report(StudioEvent.Failed(StudioFailure.PROJECT_FOLDER))
        return project.copy(folder = folder?.name ?: project.folder)
    }

    /**
     * The folder in [saveFolder] a Render for [project] saves into, made when it is missing; null without
     * a Save folder, or when it cannot be had (said on the banner): the Render is then saved as before.
     */
    suspend fun folderFor(
        saveFolder: String?,
        project: ProjectId,
        results: Results<StudioEvent>,
    ): String? {
        if (saveFolder == null) return null
        val folder = lock.withLock { caught { folderOf(saveFolder, project) } }
        if (folder == null) {
            results.report(StudioEvent.Failed(StudioFailure.PROJECT_FOLDER))
        } else if (folder.named) {
            results.report(StudioEvent.ProjectFoldersChanged(folder.change(project)))
        }
        return folder?.uri
    }

    /** Renames [project]'s folder in [saveFolder] after its new name, which is saved already. */
    suspend fun renamed(
        saveFolder: String?,
        project: ProjectId,
        results: Results<StudioEvent>,
    ) {
        if (saveFolder == null) return
        val changed = lock.withLock { caught { renameFolder(saveFolder, project) } }
        when {
            changed == null -> results.report(StudioEvent.Failed(StudioFailure.PROJECT_FOLDER))
            changed.folders.isNotEmpty() -> results.report(changed)
        }
    }

    /** Moves [work]'s video to its new Project, and its file into that Project's folder. */
    suspend fun move(
        work: StudioWork.MoveVideo,
        results: Results<StudioEvent>,
    ) {
        lock.withLock { moveLocked(work, results) }
    }

    private suspend fun moveLocked(
        work: StudioWork.MoveVideo,
        results: Results<StudioEvent>,
    ) {
        val saveFolder = work.saveFolder
        val before = if (saveFolder == null) null else caught { placeOf(work.video) }
        if (caught { storage.moveVideo(work.video, work.to) } == null) return results.report(StudioEvent.Failed(StudioFailure.SAVE))
        if (saveFolder == null || before == null) return
        val moved = caught { follow(saveFolder, before, work.to) }
        when {
            moved == null -> results.report(StudioEvent.Failed(StudioFailure.PROJECT_FOLDER))
            moved.folders.isNotEmpty() || moved.relinked.isNotEmpty() -> results.report(moved)
        }
    }

    /**
     * Forgets [work]'s Project, deletes the files asked, and then its folder: the moved videos' files go
     * into their new Project's folder first, and a file another video still uses is never deleted.
     */
    suspend fun delete(
        work: StudioWork.DeleteProject,
        results: Results<StudioEvent>,
    ) {
        lock.withLock { deleteLocked(work, results) }
    }

    private suspend fun deleteLocked(
        work: StudioWork.DeleteProject,
        results: Results<StudioEvent>,
    ) {
        val saveFolder = work.saveFolder
        val folder = if (saveFolder == null) null else caught { storage.projects().firstOrNull { it.id == work.project }?.folder }
        if (caught { storage.deleteProject(work.project, work.moveTo) } == null) {
            // The Project is still saved: keep its files and folder too.
            return results.report(StudioEvent.Failed(StudioFailure.SAVE))
        }
        val failed = work.files.count { caught { files.delete(it) } != true }
        val gone = saveFolder == null || folder == null || caught { removeFolder(saveFolder, folder, work.moveTo, results) } == true
        if (failed > 0 || !gone) results.report(StudioEvent.Failed(StudioFailure.DELETE_FILES))
    }

    /** [project]'s folder in [saveFolder], made when missing, and named first when it has no name yet; null when gone. */
    private suspend fun folderOf(
        saveFolder: String,
        project: ProjectId,
    ): FolderRef? {
        val own = storage.projects().firstOrNull { it.id == project } ?: return null
        val name = own.folder ?: return makeNamed(saveFolder, own)
        return folders.folder(saveFolder, name, make = true)?.let { FolderRef(it, name, named = false) }
    }

    /** Makes a folder named after [project] in [saveFolder], unlike anything there, and saves its name. */
    private suspend fun makeNamed(
        saveFolder: String,
        project: Project,
    ): FolderRef? {
        val others = storage.projects().filter { it.id != project.id }.mapNotNull { it.folder }
        val name = ProjectFolderNames.of(project.name, folders.names(saveFolder) + others)
        val made = folders.folder(saveFolder, name, make = true) ?: return null
        storage.relocate(mapOf(project.id to name), emptyMap())
        return FolderRef(made, name, named = true)
    }

    private suspend fun renameFolder(
        saveFolder: String,
        project: ProjectId,
    ): StudioEvent.ProjectFoldersChanged {
        val projects = storage.projects()
        val own = projects.firstOrNull { it.id == project } ?: return StudioEvent.ProjectFoldersChanged(emptyMap())
        val old = own.folder
        val current = old?.let { folders.folder(saveFolder, it, make = false) }
        if (old == null || current == null) {
            // Nothing to rename: the folder is made under the new name.
            val made = makeNamed(saveFolder, own) ?: error("no folder for ${own.id}")
            return StudioEvent.ProjectFoldersChanged(made.change(project))
        }
        val others = projects.filter { it.id != project }.mapNotNull { it.folder }
        val taken = (folders.names(saveFolder) + others).filterNot { it.equals(old, ignoreCase = true) }
        val wanted = ProjectFolderNames.of(own.name, taken)
        if (wanted == old) return StudioEvent.ProjectFoldersChanged(emptyMap())
        val renamed = folders.rename(current, wanted)
        storage.relocate(mapOf(project to renamed.name), renamed.relinked)
        return StudioEvent.ProjectFoldersChanged(mapOf(project to renamed.name), renamed.relinked)
    }

    /** Where a video was before it moved: the video, and its Project's folder name. */
    private class Place(
        val video: Video,
        val folder: String?,
    )

    private suspend fun placeOf(video: VideoId): Place? {
        val found = storage.videos().firstOrNull { it.id == video } ?: return null
        return Place(found, storage.projects().firstOrNull { it.id == found.project }?.folder)
    }

    /** Moves the file of the video [before] describes into [to]'s folder; what changed. */
    private suspend fun follow(
        saveFolder: String,
        before: Place,
        to: ProjectId,
    ): StudioEvent.ProjectFoldersChanged? {
        val from =
            before.folder?.let { folders.folder(saveFolder, it, make = false) } ?: return StudioEvent.ProjectFoldersChanged(emptyMap())
        val target = folderOf(saveFolder, to) ?: return null
        val relinked = folders.move(listOfNotNull(before.video.file, before.video.thumbnail), from, target.uri)
        if (relinked.isNotEmpty()) storage.relocate(emptyMap(), relinked)
        return StudioEvent.ProjectFoldersChanged(target.change(to), relinked)
    }

    /**
     * Deletes the folder [name] in [saveFolder] of a Project just forgotten, first moving the files of the
     * videos that moved to [moveTo] into its folder; whether the folder is gone.
     */
    private suspend fun removeFolder(
        saveFolder: String,
        name: String,
        moveTo: ProjectId?,
        results: Results<StudioEvent>,
    ): Boolean {
        val current = folders.folder(saveFolder, name, make = false) ?: return true
        if (moveTo != null) caught { moveInto(saveFolder, moveTo, current) }?.let(results::report)
        val keep = storage.videos().flatMapTo(HashSet()) { listOfNotNull(it.file, it.thumbnail) }
        return folders.delete(current, keep)
    }

    /** Moves the files of [project]'s videos that are in the folder [from] into [project]'s own folder. */
    private suspend fun moveInto(
        saveFolder: String,
        project: ProjectId,
        from: String,
    ): StudioEvent.ProjectFoldersChanged? {
        val target = folderOf(saveFolder, project) ?: return null
        val moving = storage.videos().filter { it.project == project }.flatMap { listOfNotNull(it.file, it.thumbnail) }
        val relinked = folders.move(moving, from, target.uri)
        if (relinked.isNotEmpty()) storage.relocate(emptyMap(), relinked)
        return StudioEvent.ProjectFoldersChanged(target.change(project), relinked).takeIf { target.named || relinked.isNotEmpty() }
    }
}

/** [block]'s result, or null when it threw. */
@Suppress("TooGenericExceptionCaught", "SwallowedException") // Handlers catch everything at their edge (ADR-0005).
private suspend fun <T> caught(block: suspend () -> T): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Exception) {
        null
    }
