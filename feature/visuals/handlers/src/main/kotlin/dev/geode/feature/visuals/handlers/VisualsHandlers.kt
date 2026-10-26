package dev.geode.feature.visuals.handlers

import dev.geode.engine.types.PresetId
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.StyleManifest
import dev.geode.feature.visuals.api.BrokenFile
import dev.geode.feature.visuals.api.BuiltInPreset
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropImportProblem
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.PresetPlayback
import dev.geode.feature.visuals.api.PresetShelf
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.feature.visuals.core.MilkdropChecks
import dev.geode.feature.visuals.core.MilkdropFiles
import dev.geode.feature.visuals.core.PickedFile
import dev.geode.feature.visuals.core.Planned
import dev.geode.feature.visuals.core.PresetLinks
import dev.geode.feature.visuals.core.PresetNames
import dev.geode.feature.visuals.core.StyleEntries
import dev.geode.feature.visuals.core.VISUALS_FEATURE
import dev.geode.feature.visuals.core.VisualsEvent
import dev.geode.feature.visuals.core.VisualsReducer
import dev.geode.feature.visuals.core.VisualsWork
import dev.geode.feature.visuals.core.WorkFailure
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Results
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where the person's library is kept. Any call may throw; the handlers turn that into a named failure.
 */
interface VisualsStorage {
    /** The saved library; an empty one when nothing has been saved yet. */
    suspend fun load(): StyleLibrary

    /** Saves [library], replacing what was saved before. */
    suspend fun save(library: StyleLibrary)
}

/**
 * Carries out the Visuals feature's work (ADR-0005): it follows the Style Customization shows and asks
 * Customization for a Style or a preset, through Customization's `:api` only, reads and writes the
 * library in [storage], and lists, changes, shares and imports the preset files in [presets]. Saves
 * are written one at a time, in the order they start. It lists the [milkdrop] folder beside the
 * [builtInMilkdrop] presets, and imports [picked] files into it one by one, each checked first
 * ([MilkdropFiles.plan]), so a broken file is reported and never stops the rest. Everything that can
 * fail is caught here and reported as a named event.
 */
class VisualsHandlers(
    private val customization: CustomizationFeature,
    private val storage: VisualsStorage,
    private val presets: PresetFiles,
    private val milkdrop: MilkdropFolder,
    private val picked: PickedFiles,
    private val builtInMilkdrop: List<MilkdropPreset>,
) : EffectRunner<VisualsWork, VisualsEvent> {
    private val writing = Mutex()
    private val writingPresets = Mutex()

    override suspend fun run(
        work: VisualsWork,
        results: Results<VisualsEvent>,
    ) {
        when (work) {
            VisualsWork.FollowCurrentStyle ->
                customization.state
                    .map { it.current.style }
                    .distinctUntilChanged()
                    .collect { results.report(VisualsEvent.CurrentStyle(it)) }
            VisualsWork.LoadLibrary ->
                attempt(results, WorkFailure.LIBRARY_NOT_LOADED) { storage.load() }
                    ?.let { results.report(VisualsEvent.LibraryLoaded(it)) }
            is VisualsWork.SaveLibrary ->
                attempt(results, WorkFailure.LIBRARY_NOT_SAVED) { writing.withLock { storage.save(work.library) } }
            is VisualsWork.ChooseStyle ->
                attempt(results, WorkFailure.STYLE_NOT_CHOSEN) { customization.send(CustomizationCommand.ChooseStyle(work.style)) }
            // The Presets browser's work (#144).
            VisualsWork.LoadPresets ->
                attempt(results, WorkFailure.PRESETS_NOT_LOADED) { shelf() }?.let { results.report(VisualsEvent.PresetsLoaded(it)) }
            is VisualsWork.ApplySavedPreset ->
                attempt(results, WorkFailure.PRESET_NOT_APPLIED) { customization.send(CustomizationCommand.LoadPreset(work.id)) }
            is VisualsWork.ApplyBuiltInPreset ->
                attempt(results, WorkFailure.PRESET_NOT_APPLIED) {
                    customization.send(CustomizationCommand.ApplyPreset(presets.builtIn().first { PresetId(it.name) == work.id }))
                }
            is VisualsWork.RenamePreset -> change(results) { presets.rename(work.id, work.name) }
            is VisualsWork.MovePreset -> change(results) { presets.move(work.id, work.folder) }
            is VisualsWork.DeletePreset -> change(results) { presets.delete(work.id) }
            is VisualsWork.CreatePresetFolder -> change(results) { presets.createFolder(work.folder) }
            is VisualsWork.RenamePresetFolder -> change(results) { presets.renameFolder(work.from, work.to) }
            is VisualsWork.DeletePresetFolder -> change(results) { presets.deleteFolder(work.folder) }
            is VisualsWork.EncodePreset ->
                attempt(results, WorkFailure.PRESET_NOT_SHARED) { PresetLinks.encode(presets.text(work.id)) }
                    ?.let { results.report(VisualsEvent.PresetEncoded(work.id, it)) }
            is VisualsWork.ImportPresetLink -> {
                // Unpacked here, off the main thread: a link may unpack to megabytes.
                val text = PresetLinks.decode(work.link)
                if (text == null) results.report(VisualsEvent.ImportRefused(ImportProblem.NOT_A_PRESET)) else import(text, results)
            }
            is VisualsWork.ImportPresetFile ->
                attempt(results, WorkFailure.PRESET_NOT_IMPORTED) { presets.readDocument(work.uri) }?.let { import(it, results) }
            // MilkDrop import (#145).
            VisualsWork.ListMilkdrop ->
                attempt(results, WorkFailure.MILKDROP_NOT_LISTED) { listing() }
                    ?.let { (milks, textures) -> results.report(VisualsEvent.MilkdropListed(milks, textures)) }
            is VisualsWork.ImportMilkdrop ->
                attempt(results, WorkFailure.MILKDROP_NOT_IMPORTED) { importMilkdrop(work.kind, work.files) }?.let(results::report)
            is VisualsWork.DeleteMilkdrop ->
                attempt(results, WorkFailure.MILKDROP_NOT_DELETED) {
                    milkdrop.delete(work.kind, work.file)
                    listing()
                }?.let { (milks, textures) -> results.report(VisualsEvent.MilkdropDeleted(milks, textures)) }
        }
    }

    private suspend fun shelf(): PresetShelf {
        val saved = presets.saved()
        return PresetShelf(
            builtIn = presets.builtIn().map { BuiltInPreset(PresetId(it.name), it.style) },
            saved = saved.presets,
            folders = saved.folders,
        )
    }

    /** Saves the shared preset [text] under a name no saved preset has, and says how that went. */
    private suspend fun import(
        text: String,
        results: Results<VisualsEvent>,
    ) {
        attempt(results, WorkFailure.PRESET_NOT_IMPORTED) {
            writingPresets
                .withLock {
                    val taken =
                        presets
                            .saved()
                            .presets
                            .map { it.id.value }
                            .toSet()
                    presets.import(text) { PresetNames.free(it, taken) }
                }?.let(VisualsEvent::PresetImported) ?: VisualsEvent.ImportRefused(ImportProblem.NOT_A_PRESET)
        }?.let { results.report(it) }
    }

    /** One change to the saved presets, made one at a time in the order they start. */
    private suspend fun change(
        results: Results<VisualsEvent>,
        block: suspend () -> Unit,
    ) {
        attempt(results, WorkFailure.PRESET_NOT_CHANGED) { writingPresets.withLock { block() } }
    }

    /** The presets, built-in ones among them, and the textures, as the MilkDrop folder holds them now. */
    private suspend fun listing(): Pair<List<MilkdropPreset>, List<String>> =
        MilkdropFiles.presets(builtInMilkdrop, milkdrop.files(MilkdropKind.PRESETS)) to
            MilkdropFiles.textures(milkdrop.files(MilkdropKind.TEXTURES))

    /**
     * Reads, checks and writes each of [files] in turn, so only one is ever held in memory. Each preset
     * written is read for how it will play ([MilkdropChecks]) beside the textures the folder holds.
     */
    private suspend fun importMilkdrop(
        kind: MilkdropKind,
        files: List<String>,
    ): VisualsEvent.MilkdropImported {
        val present = milkdrop.files(kind).toMutableList()
        if (kind == MilkdropKind.PRESETS) present += builtInMilkdrop.map { it.file }
        val held = if (kind == MilkdropKind.PRESETS) milkdrop.files(MilkdropKind.TEXTURES) else emptyList()
        val imported = mutableListOf<String>()
        val broken = mutableListOf<BrokenFile>()
        val playback = mutableMapOf<String, PresetPlayback>()
        for (file in files) {
            when (val planned = MilkdropFiles.plan(kind, listOf(read(file, kind)), present).single()) {
                is Planned.Refuse -> broken += planned.broken
                is Planned.Write -> {
                    if (!written(kind, planned)) {
                        broken += BrokenFile(planned.picked, MilkdropImportProblem.NOT_SAVED)
                        continue
                    }
                    imported += planned.shown
                    present += planned.stored
                    if (kind == MilkdropKind.PRESETS) playback[planned.shown] = playbackOf(planned, held)
                }
            }
        }
        val (milks, textures) = listing()
        return VisualsEvent.MilkdropImported(ImportReport(kind, imported, broken, playback), milks, textures)
    }

    private fun playbackOf(
        planned: Planned.Write,
        held: List<String>,
    ): PresetPlayback = MilkdropChecks.playback(planned.bytes.toString(Charsets.ISO_8859_1), held)

    /** [uri] as picked; one that cannot be read, whatever the reason, is [PickedFile.Unreadable]. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun read(
        uri: String,
        kind: MilkdropKind,
    ): PickedFile =
        try {
            picked.read(uri, MilkdropFiles.limit(kind))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Exception) {
            PickedFile.Unreadable(uri.substringAfterLast('/'))
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun written(
        kind: MilkdropKind,
        planned: Planned.Write,
    ): Boolean =
        try {
            milkdrop.write(kind, planned.stored, planned.bytes)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Exception) {
            false
        }

    /** Runs [block], turning anything it throws into a report of [failure]. Cancellation is passed on. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> attempt(
        results: Results<VisualsEvent>,
        failure: WorkFailure,
        block: suspend () -> T,
    ): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Exception) {
            results.report(VisualsEvent.WorkFailed(failure))
            null
        }
}

/**
 * The Visuals feature's one app-scoped store, as `:app` builds it when it wires the feature in (#17):
 * over the app's one Customization store, listing the Styles of [manifests] and the preset files in
 * [presets]. Following Customization, reading the library, listing the presets and listing the
 * MilkDrop folder are its start effects.
 *
 * MilkDrop (#145): [milkdrop] is the folder presets and textures are imported into
 * ([FileMilkdropFolder.of]), [picked] reads what the system picker gives ([ContentPickedFiles]), and
 * [builtInMilkdrop] are the curated presets Geode ships (#93), from `assets/milk/catalog.json`, each
 * with its display name and author.
 */
fun visualsStore(
    customization: CustomizationFeature,
    storage: VisualsStorage,
    presets: PresetFiles,
    manifests: List<StyleManifest>,
    milkdrop: MilkdropFolder,
    picked: PickedFiles,
    builtInMilkdrop: List<MilkdropPreset>,
    runtime: FeatureRuntime,
): Visuals =
    FeatureStore<VisualsState, VisualsCommand, VisualsEvent, VisualsWork, VisualsFact>(
        name = VISUALS_FEATURE,
        initial = VisualsState(styles = StyleEntries.from(manifests)),
        reducer = VisualsReducer,
        toEvent = VisualsEvent::Command,
        runner = VisualsHandlers(customization, storage, presets, milkdrop, picked, builtInMilkdrop),
        runtime = runtime,
        start = VisualsReducer.start,
    )
