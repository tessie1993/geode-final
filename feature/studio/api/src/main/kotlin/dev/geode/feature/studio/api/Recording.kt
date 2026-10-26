package dev.geode.feature.studio.api

import dev.geode.engine.types.Millis

/**
 * Where a Recording of the live Visual has got (#317, `CONTEXT.md`). A Recording holds the Visual
 * alone, never the App UI, with the sound that drives it: the playing track, or Live input while Live
 * sound is on. It stops by itself at [LIMIT], and when it stops it waits for the person to choose its
 * Project.
 */
sealed interface RecordingState {
    /** No Recording: Record is offered. */
    data object Idle : RecordingState

    /** Record was pressed: the encoder and the Visual's second surface are being set up. */
    data object Starting : RecordingState

    /**
     * Recording for [elapsed] so far. [lowStorage] while the phone's free storage runs low;
     * [stopping] once Stop was pressed or a limit was reached, until the file is finished; [atLimit]
     * when it is stopping by itself, at [LIMIT] or because storage ran out.
     */
    data class Recording(
        val elapsed: Millis = Millis.ZERO,
        val lowStorage: Boolean = false,
        val stopping: Boolean = false,
        val atLimit: Boolean = false,
    ) : RecordingState

    /**
     * Stopped and saved as [video], which waits for the person to choose its Project. [stoppedAtLimit]
     * when it stopped by itself at [LIMIT] or because storage ran out, rather than by Stop.
     */
    data class ChooseProject(
        val video: NewVideo,
        val stoppedAtLimit: Boolean = false,
    ) : RecordingState

    companion object {
        /** The longest a Recording runs (#23): it stops by itself here. */
        val LIMIT: Millis = Millis(30L * 60L * 1000L)
    }
}

/** What Studio does with Recordings (#317). */
sealed interface RecordingCommand : StudioCommand {
    /**
     * Starts recording the live Visual with its sound; [song] is the title of what plays now, blank
     * when nothing does. Refused, with a word on the banner, while Visuals off is on.
     */
    data class Start(
        val song: String = "",
    ) : RecordingCommand

    /** Stops the Recording; it is finished and saved, then waits for its Project. */
    data object Stop : RecordingCommand

    /**
     * Puts the stopped Recording into [project]; null when the person dismissed the question, which
     * puts it into the default Project. With neither it keeps waiting: nothing is discarded silently.
     */
    data class Save(
        val project: ProjectId?,
    ) : RecordingCommand
}
