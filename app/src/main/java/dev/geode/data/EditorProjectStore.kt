package dev.geode.data

import android.content.Context
import androidx.annotation.WorkerThread
import dev.geode.engine.types.SceneId
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.handlers.edit.EditorProjectFormat
import dev.geode.render.scene.SceneCapabilities
import java.io.File

/** One JSON file per named project under `files/editor`, written atomically like the presets. */
class EditorProjectStore(
    context: Context,
) {
    private val dir = File(context.filesDir, "editor").apply { mkdirs() }

    fun fileOf(name: String): File = File(dir, PresetStore.safeFileName(name) + ".json")

    @WorkerThread
    fun names(): List<String> =
        dir
            .listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .sorted()

    /** Null when there is no such project or its file is unreadable; an unreadable file is quarantined. */
    @WorkerThread
    fun load(name: String): EditorProject? {
        val file = fileOf(name)
        if (!file.isFile) return null
        return runCatching { EditorProjectFormat.fromJson(file.readText(), ::storedScene) }
            .onFailure {
                dev.geode.RingLog.note("EditorProjectStore", "unreadable project quarantined: ${file.name}", it)
                AtomicWrite.quarantine(file)
            }.getOrNull()
    }

    @WorkerThread
    fun save(
        name: String,
        project: EditorProject,
    ): Boolean = AtomicWrite.text(fileOf(name), EditorProjectFormat.toJson(project))

    @WorkerThread
    fun delete(name: String): Boolean = fileOf(name).delete()

    /** A saved Style id as one this version draws: a retired Style becomes the default. */
    private fun storedScene(id: String): String = SceneCapabilities.storedOrDefault(SceneId(id)).value
}
