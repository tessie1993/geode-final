package dev.geode.ui

import android.app.Application
import android.net.Uri
import dev.geode.data.Preset
import dev.geode.data.PresetFolders
import dev.geode.data.PresetRepository
import dev.geode.data.PresetStore
import dev.geode.engine.types.PresetId
import dev.geode.render.scene.SceneIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PresetLibraryController(
    private val application: Application,
    private val presets: PresetRepository,
    private val scope: CoroutineScope,
    private val storeScope: CoroutineScope,
    private val host: Host,
) {
    interface Host {
        val vizState: StateFlow<VizUiState>

        fun updatePresets(transform: (List<Preset>) -> List<Preset>)

        val presetMirrorUri: String?

        val activeMilkPath: String?
    }

    val folders: StateFlow<PresetFolders> = presets.folders

    fun addPresetFolder(path: String) {
        storeScope.launch { presets.addFolder(path) }
    }

    fun renamePresetFolder(
        from: String,
        to: String,
    ) {
        storeScope.launch { presets.renameFolder(from, to) }
    }

    fun movePresetToFolder(
        name: PresetId,
        folder: String,
    ) {
        storeScope.launch {
            presets.moveToFolder(name, folder)
            mirrorPresetToChosenFolder(name)
            relistPresets()
        }
    }

    fun userMilkPresets(): List<java.io.File> {
        val dir = java.io.File(application.filesDir, "milk")
        dev.geode.render.scene.MilkStarterPack
            .install(application, dir)
        return dir
            .listFiles { f -> f.isFile && f.extension == "milk" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun refreshInitial() {
        scope.launch {
            val listed = presets.list()
            host.updatePresets { current ->
                if (current !== BuiltInPresets.ALL) current else BuiltInPresets.ALL + listed
            }
            presets.refreshFolders()
        }
    }

    private suspend fun relistPresets() {
        val listed = BuiltInPresets.ALL + presets.list()
        host.updatePresets { listed }
    }

    fun savePreset(
        name: String,
        folder: String = "",
    ) {
        @Suppress("NAME_SHADOWING")
        val name = name.replace(" · ", " - ").trim().ifEmpty { "Preset" }
        val s = host.vizState.value
        val milkPath = host.activeMilkPath
        storeScope.launch {
            val milkSource =
                if (s.sceneId == SceneIds.MILKDROP) {
                    milkPath?.let { src -> runCatching { java.io.File(src).readText() }.getOrNull() }
                } else {
                    null
                }
            milkSource?.let { source -> presets.materializeMilk(name, source) }
            presets.save(Preset(name, s.sceneId, s.attack, s.decay, s.params, milkSource), folder)
            mirrorPresetToChosenFolder(PresetId(name))
            relistPresets()
        }
    }

    private fun mirrorPresetToChosenFolder(name: PresetId) {
        val uriStr = host.presetMirrorUri ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tree =
                    androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(application, Uri.parse(uriStr))
                        ?: return@runCatching

                fun copyInto(
                    src: java.io.File,
                    mime: String,
                ) {
                    if (!src.exists()) return
                    tree.findFile(src.name)?.delete()
                    val dest = tree.createFile(mime, src.name) ?: return
                    application.contentResolver.openOutputStream(dest.uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                }
                presets.fileOf(name)?.let { copyInto(it, "application/json") }
                milkFileFor(name).let { copyInto(it, "text/plain") }
            }
        }
    }

    private fun milkFileFor(preset: PresetId): java.io.File =
        java.io.File(
            java.io.File(application.filesDir, "milk").apply { mkdirs() },
            PresetStore.milkFileName(preset.value),
        )

    fun milkPresetPathFor(preset: Preset): String? {
        if (preset.sceneId != SceneIds.MILKDROP) return null
        return presets.materializeMilk(preset.name, preset.milkPreset)
    }

    fun presetShareLink(name: PresetId): String? {
        val preset =
            host.vizState.value.presets
                .firstOrNull { it.id == name } ?: return null
        val link = PresetLink.encode(PresetStore.toJson(preset))
        return link.takeIf { it.length <= PresetLink.MAX_LINK_LENGTH }
    }

    fun importPresetLink(text: String): PresetId? {
        val link = PresetLink.findIn(text) ?: return null
        return importPresetJson(PresetLink.decode(link) ?: return null)
    }

    fun importPresetFile(
        uri: Uri,
        onResult: (PresetId?) -> Unit,
    ) {
        scope.launch {
            val json =
                withContext(Dispatchers.IO) {
                    runCatching {
                        application
                            .contentResolver
                            .openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                    }.getOrNull()
                }
            onResult(json?.let { importPresetJson(it) })
        }
    }

    private fun importPresetJson(json: String): PresetId? {
        val incoming = runCatching { PresetStore.fromJson(json) }.getOrNull() ?: return null
        val existing =
            host.vizState.value.presets
                .map { it.name }
                .toSet()
        val base =
            incoming.name
                .replace(" · ", " - ")
                .trim()
                .ifBlank { "Shared preset" }
        var name = base
        var n = 2
        while (name in existing) {
            name = "$base $n"
            n++
        }
        val preset = incoming.copy(name = name)
        host.updatePresets { it + preset }
        storeScope.launch {
            presets.save(preset)
            relistPresets()
        }
        return preset.id
    }

    fun presetFile(name: PresetId): java.io.File? = presets.fileOf(name)

    fun deletePreset(name: PresetId) {
        if (BuiltInPresets.isBuiltIn(name.value)) return
        host.updatePresets { presets -> presets.filterNot { it.id == name } }
        storeScope.launch {
            removeMirroredPreset(presets.fileOf(name)?.name, milkFileFor(name).name)
            presets.delete(name)
            relistPresets()
        }
    }

    private fun removeMirroredPreset(
        jsonName: String?,
        milkName: String?,
    ) {
        val uriStr = host.presetMirrorUri ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tree =
                    androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(application, Uri.parse(uriStr))
                        ?: return@runCatching
                jsonName?.let { tree.findFile(it)?.delete() }
                milkName?.let { tree.findFile(it)?.delete() }
            }
        }
    }
}
