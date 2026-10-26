package dev.geode.feature.visuals.core

import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import java.time.LocalDateTime
import java.util.Locale

/**
 * Snapshots (#405): a still of the Visual saved as a PNG in Pictures/Geode, not tied to any Project.
 * One is saved at a time; asking again while one is being saved does nothing, so a held key or a
 * double tap makes one picture. The handlers read the frame and write the file, and the reducer
 * announces how it went, for the App UI's short confirmation.
 */
object Snapshots {
    /** Starts a Snapshot of the Style shown now, unless one is being saved. */
    fun take(state: VisualsState): Next<VisualsState, VisualsEffect> {
        if (state.snapshotting) return Next(state)
        val style = state.current?.let { id -> state.styles.firstOrNull { it.id == id }?.name }
        return Next(state.copy(snapshotting = true), listOf(Perform(VisualsWork.TakeSnapshot(style))))
    }

    fun saved(
        state: VisualsState,
        event: VisualsEvent.SnapshotSaved,
    ): Next<VisualsState, VisualsEffect> = Next(state.copy(snapshotting = false), listOf(Emit(VisualsFact.SnapshotSaved(event.fileName))))

    fun failed(
        state: VisualsState,
        event: VisualsEvent.SnapshotFailed,
    ): Next<VisualsState, VisualsEffect> = Next(state.copy(snapshotting = false), listOf(Emit(VisualsFact.SnapshotNotSaved(event.problem))))
}

/** How a Snapshot's file is named: the Style's name and the moment it was taken, so each is its own file. */
object SnapshotNames {
    /** The longest a file name grows from the Style's name, as a Recording's from the song's title. */
    const val MAX_NAME = 80

    /** The name a Snapshot takes when no Style's name is known. */
    const val FALLBACK = "Visual"

    /** Characters a file name on the phone cannot hold, and control characters. */
    private val unsafe = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val spaces = Regex("\\s+")

    /** `Style (Geode Snapshot 2026-10-26 14-03-22).png`, from [style] shown at [at]. */
    fun fileName(
        style: String?,
        at: LocalDateTime,
    ): String {
        val title =
            style
                .orEmpty()
                .replace(unsafe, " ")
                .replace(spaces, " ")
                .trim()
                .take(MAX_NAME)
                .trim()
                .ifEmpty { FALLBACK }
        // Western digits whatever the phone's language, so the names sort by time.
        val stamp =
            String.format(
                Locale.ROOT,
                "%04d-%02d-%02d %02d-%02d-%02d",
                at.year,
                at.monthValue,
                at.dayOfMonth,
                at.hour,
                at.minute,
                at.second,
            )
        return "$title (Geode Snapshot $stamp).png"
    }
}
