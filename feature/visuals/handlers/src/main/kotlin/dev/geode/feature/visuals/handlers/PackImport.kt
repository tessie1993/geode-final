package dev.geode.feature.visuals.handlers

import dev.geode.feature.visuals.api.BrokenFile
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropImportProblem
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.PackLimit
import dev.geode.feature.visuals.api.SkippedEntry
import dev.geode.feature.visuals.core.MilkdropChecks
import dev.geode.feature.visuals.core.MilkdropFiles
import dev.geode.feature.visuals.core.MilkdropPacks
import dev.geode.feature.visuals.core.PackEntry
import dev.geode.feature.visuals.core.PickedFile
import dev.geode.feature.visuals.core.Planned
import dev.geode.feature.visuals.core.PresetNeeds
import kotlin.coroutines.cancellation.CancellationException

/**
 * One pack's import as it goes (#399): each file of the pack sorted by its path ([MilkdropPacks.sort]),
 * each preset and texture checked and named as a picked one is ([MilkdropFiles.plan]) beside what the
 * MilkDrop folder already holds ([presets], built-in ones among them, and [textures]), and written by
 * [write]. Only one file is held in memory at a time; of each preset written only what it needs to play
 * is kept ([MilkdropChecks.needs]), so it is judged once every texture of the pack is in, wherever in
 * the pack they came.
 */
internal class PackImport(
    presets: Collection<String>,
    textures: Collection<String>,
    private val write: suspend (MilkdropKind, Planned.Write) -> Boolean,
) {
    private val present = mapOf(MilkdropKind.PRESETS to presets.toMutableList(), MilkdropKind.TEXTURES to textures.toMutableList())
    private val imported = mutableListOf<String>()
    private val brought = mutableListOf<String>()
    private val broken = mutableListOf<BrokenFile>()
    private val skipped = mutableListOf<SkippedEntry>()
    private val needs = mutableMapOf<String, PresetNeeds>()

    /** Each preset file written, with the pack folders it sat in. */
    val placed = mutableListOf<Pair<String, List<String>>>()

    /** Brings in [file], passes it over, or reports it broken. */
    suspend fun take(file: PackFile) {
        when (val entry = MilkdropPacks.sort(file.path)) {
            PackEntry.Ignore -> Unit
            is PackEntry.Skip -> skipped += SkippedEntry(file.path, entry.problem)
            is PackEntry.Take -> take(file, entry)
        }
    }

    private suspend fun take(
        file: PackFile,
        entry: PackEntry.Take,
    ) {
        val kind = entry.kind
        val planned = MilkdropFiles.plan(kind, listOf(read(file, entry.name, MilkdropFiles.limit(kind))), present.getValue(kind)).single()
        when {
            planned is Planned.Refuse -> broken += planned.broken.copy(name = file.path)
            planned is Planned.Write && write(kind, planned) -> wrote(kind, entry, planned)
            else -> broken += BrokenFile(file.path, MilkdropImportProblem.NOT_SAVED)
        }
    }

    private fun wrote(
        kind: MilkdropKind,
        entry: PackEntry.Take,
        planned: Planned.Write,
    ) {
        present.getValue(kind) += planned.stored
        if (kind == MilkdropKind.TEXTURES) {
            brought += planned.shown
            return
        }
        imported += planned.shown
        needs[planned.shown] = MilkdropChecks.needs(planned.bytes.toString(Charsets.ISO_8859_1))
        placed += planned.stored to entry.folder
    }

    /** The pack [name] could not be read through; what came in before that stays in. */
    fun unreadable(name: String) {
        broken += BrokenFile(name, MilkdropImportProblem.UNREADABLE)
    }

    /** How the import went, with each preset brought in judged beside the textures the folder now [holds]. */
    fun report(
        holds: Collection<String>,
        stopped: PackLimit?,
    ): ImportReport =
        ImportReport(
            MilkdropKind.PRESETS,
            imported.toList(),
            broken.toList(),
            imported.associateWith { MilkdropChecks.playback(needs.getValue(it), holds) },
            brought.toList(),
            skipped.toList(),
            stopped,
        )

    /** [file] as a picked file named [name]; one that cannot be read, whatever the reason, is [PickedFile.Unreadable]. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun read(
        file: PackFile,
        name: String,
        limit: Int,
    ): PickedFile =
        try {
            file.read(limit)?.let { PickedFile.Read(name, it) } ?: PickedFile.TooLarge(name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Exception) {
            PickedFile.Unreadable(name)
        }
}
