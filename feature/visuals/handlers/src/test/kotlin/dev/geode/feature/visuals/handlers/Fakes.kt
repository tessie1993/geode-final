package dev.geode.feature.visuals.handlers

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFact
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.api.PRESET_VERSION
import dev.geode.feature.customization.api.Preset
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.customization.api.StyleManifest
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.PresetFolderPath
import dev.geode.feature.visuals.api.SavedPreset
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.core.PickedFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.FileNotFoundException

/** A Customization showing [style]; nothing else of it matters here. */
fun customizationOf(style: SceneId): CustomizationState =
    CustomizationState(Customization(style, emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList(), emptyMap()))

fun manifest(
    id: SceneId,
    family: StyleFamily,
): StyleManifest = StyleManifest(id, family, false, emptyList(), emptyMap(), emptyList(), emptyList())

/** Customization as Visuals sees it: it switches Style when asked, and records every Command. */
class FakeCustomization(
    style: SceneId,
) : CustomizationFeature {
    private val mutableState = MutableStateFlow(customizationOf(style))
    override val state: StateFlow<CustomizationState> = mutableState
    override val events: Flow<CustomizationFact> = emptyFlow()

    val sent = mutableListOf<CustomizationCommand>()

    /** What the next send throws, once; null lets it through. */
    var failNextSend: Exception? = null

    override fun send(command: CustomizationCommand) {
        failNextSend?.let {
            failNextSend = null
            throw it
        }
        sent += command
        if (command is CustomizationCommand.ChooseStyle) show(command.style)
    }

    /** Customization moves to [style] by itself, as a preset or an undo would. */
    fun show(style: SceneId) {
        mutableState.value = customizationOf(style)
    }
}

/** Storage in memory, which can be made to fail. */
class FakeVisualsStorage(
    var saved: StyleLibrary? = null,
) : VisualsStorage {
    var failLoad = false
    var failSave = false
    var saves = 0

    override suspend fun load(): StyleLibrary {
        check(!failLoad) { "disk unreadable" }
        return saved ?: StyleLibrary()
    }

    override suspend fun save(library: StyleLibrary) {
        check(!failSave) { "disk full" }
        saves++
        saved = library
    }
}

/** The MilkDrop folder in memory, which can be made to fail. */
class FakeMilkdropFolder(
    presets: List<String> = emptyList(),
    textures: List<String> = emptyList(),
) : MilkdropFolder {
    val stored: Map<MilkdropKind, MutableMap<String, ByteArray>> =
        mapOf(
            MilkdropKind.PRESETS to presets.associateWith { ByteArray(0) }.toMutableMap(),
            MilkdropKind.TEXTURES to textures.associateWith { ByteArray(0) }.toMutableMap(),
        )
    var failList = false

    /** Names whose write fails. */
    val failWrite = mutableSetOf<String>()

    override suspend fun files(kind: MilkdropKind): List<String> {
        check(!failList) { "folder gone" }
        return stored.getValue(kind).keys.toList()
    }

    override suspend fun write(
        kind: MilkdropKind,
        name: String,
        bytes: ByteArray,
    ) {
        check(name !in failWrite) { "disk full" }
        stored.getValue(kind)[name] = bytes
    }

    /** Names whose delete fails. */
    val failDelete = mutableSetOf<String>()

    override suspend fun delete(
        kind: MilkdropKind,
        name: String,
    ) {
        check(name !in failDelete) { "read-only" }
        stored.getValue(kind).remove(name)
    }
}

/** Picked files by uri; a uri it does not know is unreadable, and [throwing] ones throw. */
class FakePickedFiles(
    private val files: Map<String, PickedFile>,
    private val throwing: Set<String> = emptySet(),
) : PickedFiles {
    val limits = mutableListOf<Int>()

    override suspend fun read(
        uri: String,
        limit: Int,
    ): PickedFile {
        limits += limit
        check(uri !in throwing) { "provider crashed" }
        return files[uri] ?: PickedFile.Unreadable(uri)
    }
}

/** A preset that keeps only its Style. */
fun presetOf(
    name: String,
    style: SceneId,
): Preset = Preset(PRESET_VERSION, name, style, setOf(PresetPart.STYLE))

/**
 * Preset files in memory, which can be made to fail. A preset's text here is "name|style"; any other
 * text is not a preset.
 */
class FakePresetFiles(
    private val builtIns: List<Preset> = emptyList(),
    saved: List<SavedPreset> = emptyList(),
    folders: List<PresetFolderPath> = emptyList(),
    private val documents: Map<String, String> = emptyMap(),
) : PresetFiles {
    val presets = saved.toMutableList()
    val folders = folders.toMutableList()

    /** What every call throws while set. */
    var failure: Exception? = null

    /** The text a preset is shared as, when set here; otherwise "name|style". */
    val texts = mutableMapOf<PresetId, String>()

    private fun failIfAsked() {
        failure?.let { throw it }
    }

    override suspend fun builtIn(): List<Preset> {
        failIfAsked()
        return builtIns
    }

    override suspend fun saved(): SavedPresets {
        failIfAsked()
        return SavedPresets(presets.toList(), folders.toList())
    }

    override suspend fun rename(
        id: PresetId,
        name: String,
    ) = change(id) { it.copy(id = PresetId(name)) }

    override suspend fun move(
        id: PresetId,
        folder: PresetFolderPath,
    ) = change(id) { it.copy(folder = folder) }

    override suspend fun delete(id: PresetId) {
        failIfAsked()
        presets.removeAll { it.id == id }
    }

    override suspend fun createFolder(folder: PresetFolderPath) {
        failIfAsked()
        folders += folder
    }

    override suspend fun renameFolder(
        from: PresetFolderPath,
        to: PresetFolderPath,
    ) {
        failIfAsked()
        folders.replaceAll { if (it == from) to else it }
        presets.replaceAll { if (it.folder == from) it.copy(folder = to) else it }
    }

    override suspend fun deleteFolder(folder: PresetFolderPath) {
        failIfAsked()
        folders.remove(folder)
    }

    override suspend fun text(id: PresetId): String {
        failIfAsked()
        val preset = presets.first { it.id == id }
        return texts[id] ?: "${preset.id}|${preset.style}"
    }

    override suspend fun import(
        text: String,
        name: (String) -> String,
    ): SavedPreset? {
        failIfAsked()
        val parts = text.split('|')
        if (parts.size != 2) return null
        val preset = SavedPreset(PresetId(name(parts[0])), SceneId(parts[1]))
        presets += preset
        return preset
    }

    override suspend fun readDocument(uri: String): String {
        failIfAsked()
        return documents[uri] ?: throw FileNotFoundException(uri)
    }

    private fun change(
        id: PresetId,
        change: (SavedPreset) -> SavedPreset,
    ) {
        failIfAsked()
        presets.replaceAll { if (it.id == id) change(it) else it }
    }
}
