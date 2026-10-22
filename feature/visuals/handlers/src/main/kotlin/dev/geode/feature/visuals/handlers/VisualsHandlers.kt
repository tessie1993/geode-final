package dev.geode.feature.visuals.handlers

import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.StyleManifest
import dev.geode.feature.visuals.api.BrokenFile
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.feature.visuals.core.MilkdropFiles
import dev.geode.feature.visuals.core.PickedFile
import dev.geode.feature.visuals.core.Planned
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
 * Customization for a Style, through Customization's `:api` only, and reads and writes the library in
 * [storage]. Saves are written one at a time, in the order they start. It lists the [milkdrop] folder
 * beside the [builtInPresets], and imports [picked] files into it one by one, each checked first
 * ([MilkdropFiles.plan]), so a broken file is reported and never stops the rest. Everything that can
 * fail is caught here and reported as a named event.
 */
class VisualsHandlers(
    private val customization: CustomizationFeature,
    private val storage: VisualsStorage,
    private val milkdrop: MilkdropFolder,
    private val picked: PickedFiles,
    private val builtInPresets: List<MilkdropPreset>,
) : EffectRunner<VisualsWork, VisualsEvent> {
    private val writing = Mutex()

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
            VisualsWork.ListMilkdrop ->
                attempt(results, WorkFailure.MILKDROP_NOT_LISTED) { listing() }
                    ?.let { (presets, textures) -> results.report(VisualsEvent.MilkdropListed(presets, textures)) }
            is VisualsWork.ImportMilkdrop ->
                attempt(results, WorkFailure.MILKDROP_NOT_IMPORTED) { import(work.kind, work.files) }?.let(results::report)
        }
    }

    /** The presets, built-in ones among them, and the textures, as the MilkDrop folder holds them now. */
    private suspend fun listing(): Pair<List<MilkdropPreset>, List<String>> =
        MilkdropFiles.presets(builtInPresets, milkdrop.files(MilkdropKind.PRESETS)) to
            MilkdropFiles.textures(milkdrop.files(MilkdropKind.TEXTURES))

    /** Reads, checks and writes each of [files] in turn, so only one is ever held in memory. */
    private suspend fun import(
        kind: MilkdropKind,
        files: List<String>,
    ): VisualsEvent.MilkdropImported {
        val present = milkdrop.files(kind).toMutableList()
        if (kind == MilkdropKind.PRESETS) present += builtInPresets.map { it.file }
        val imported = mutableListOf<String>()
        val broken = mutableListOf<BrokenFile>()
        for (file in files) {
            when (val planned = MilkdropFiles.plan(kind, listOf(read(file, kind)), present).single()) {
                is Planned.Refuse -> broken += planned.broken
                is Planned.Write ->
                    if (written(kind, planned)) {
                        imported += planned.shown
                        present += planned.stored
                    } else {
                        broken += BrokenFile(planned.picked, ImportProblem.NOT_SAVED)
                    }
            }
        }
        val (presets, textures) = listing()
        return VisualsEvent.MilkdropImported(ImportReport(kind, imported, broken), presets, textures)
    }

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
 * over the app's one Customization store, listing the Styles of [manifests]. Following Customization,
 * reading the library and listing the MilkDrop folder are its start effects.
 *
 * MilkDrop (#145): [milkdrop] is the folder presets and textures are imported into
 * ([FileMilkdropFolder.of]), [picked] reads what the system picker gives ([ContentPickedFiles]), and
 * [builtInPresets] are the curated presets Geode ships (#93), from `assets/milk/catalog.json`, each
 * with its display name and author.
 */
fun visualsStore(
    customization: CustomizationFeature,
    storage: VisualsStorage,
    manifests: List<StyleManifest>,
    milkdrop: MilkdropFolder,
    picked: PickedFiles,
    builtInPresets: List<MilkdropPreset>,
    runtime: FeatureRuntime,
): Visuals =
    FeatureStore<VisualsState, VisualsCommand, VisualsEvent, VisualsWork, VisualsFact>(
        name = VISUALS_FEATURE,
        initial = VisualsState(styles = StyleEntries.from(manifests)),
        reducer = VisualsReducer,
        toEvent = VisualsEvent::Command,
        runner = VisualsHandlers(customization, storage, milkdrop, picked, builtInPresets),
        runtime = runtime,
        start = VisualsReducer.start,
    )
