package dev.geode.feature.visuals.core

import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.kernel.Next
import dev.geode.kernel.Perform

object Fixtures {
    val tunnel = SceneId("tunnel")
    val ink = SceneId("fluid_ink")
    val milk = SceneId("milkdrop")

    /** A Style this version does not draw. */
    val gone = SceneId("silk")

    val styles =
        listOf(
            StyleEntry(tunnel, "Tunnel", StyleGroup.SHADERS),
            StyleEntry(ink, "Fluid Ink", StyleGroup.FLUIDS),
            StyleEntry(milk, "Milkdrop", StyleGroup.MILKDROP),
        )

    val one = FolderId(1)
    val two = FolderId(2)
    val three = FolderId(3)

    /** Calm (1) holds Deep (2), which holds Deeper (3); Calm holds the tunnel. */
    val nested =
        StyleLibrary(
            folders =
                listOf(
                    Folder(one, "Calm", null, listOf(tunnel)),
                    Folder(two, "Deep", one, emptyList()),
                    Folder(three, "Deeper", two, emptyList()),
                ),
        )

    /** Before anything has been heard from Customization or the phone. */
    val fresh = VisualsState(styles = styles)

    /** Showing the tunnel, with an empty library loaded. */
    val ready = VisualsState(styles = styles, current = tunnel, library = StyleLibrary(recent = listOf(tunnel)))

    fun command(command: VisualsCommand) = VisualsEvent.Command(command)

    fun reduce(
        state: VisualsState,
        event: VisualsEvent,
    ): Next<VisualsState, VisualsEffect> = VisualsReducer.reduce(state, event)

    fun saved(library: StyleLibrary) = Perform(VisualsWork.SaveLibrary(library))
}
