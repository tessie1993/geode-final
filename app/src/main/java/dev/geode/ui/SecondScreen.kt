package dev.geode.ui

/** A connected display the Visual can be shown on full screen (HDMI, DeX, Miracast), as its id and name. */
internal data class ExternalDisplay(
    val id: Int,
    val name: String,
)

/**
 * The second screen (#407): the display the Visual may go to, and whether the person chose to show
 * it there. Live only, so it is never saved: every launch starts on the phone, and a display that
 * comes back after a disconnect is offered again rather than taken.
 */
internal data class SecondScreenState(
    val display: ExternalDisplay? = null,
    val chosen: Boolean = false,
)

/** What can happen to the second screen. */
internal sealed interface SecondScreenEvent {
    /** The connected presentation displays are now [displays], in the platform's order. */
    data class DisplaysChanged(
        val displays: List<ExternalDisplay>,
    ) : SecondScreenEvent

    /** The person asked for the Visual on the connected display. */
    data object Show : SecondScreenEvent

    /** The person asked for the Visual back on the phone. */
    data object Stop : SecondScreenEvent

    /** The window on the display went away by itself, or could not be shown at all. */
    data object Lost : SecondScreenEvent
}

/** Where the one live Visual is drawn. */
internal enum class VisualHome {
    /** On the phone: the Visuals tab, the full-screen Visual or its picture-in-picture window. */
    PHONE,

    /** Full screen on the connected display; the phone shows the player and Customize, not the Visual. */
    SECOND_SCREEN,

    /** Nowhere: Settings' Visuals off. */
    NONE,
}

/**
 * The rules of the second screen (#407), as plain functions. One engine instance draws the Visual,
 * so it is in exactly one place: on the connected display once the person chooses it, otherwise on
 * the phone. Disconnecting, or the display's window going away, brings it back to the phone.
 */
internal object SecondScreen {
    fun reduce(
        state: SecondScreenState,
        event: SecondScreenEvent,
    ): SecondScreenState =
        when (event) {
            is SecondScreenEvent.DisplaysChanged -> follow(state, event.displays)
            SecondScreenEvent.Show -> state.copy(chosen = state.display != null)
            SecondScreenEvent.Stop, SecondScreenEvent.Lost -> state.copy(chosen = false)
        }

    /**
     * Keeps the display in use while it is still connected; otherwise the first one connected, and the
     * choice is dropped with the display it was for.
     */
    private fun follow(
        state: SecondScreenState,
        displays: List<ExternalDisplay>,
    ): SecondScreenState {
        val kept = state.display?.let { current -> displays.firstOrNull { it.id == current.id } }
        return if (kept != null) {
            state.copy(display = kept)
        } else {
            SecondScreenState(display = displays.firstOrNull(), chosen = false)
        }
    }

    /**
     * Where the Visual is drawn: nowhere while [visualsOff]; on the phone while [inPip] (that window
     * shows the Visual itself); on the connected display once chosen; otherwise on the phone.
     */
    fun home(
        state: SecondScreenState,
        visualsOff: Boolean,
        inPip: Boolean,
    ): VisualHome =
        when {
            visualsOff -> VisualHome.NONE
            inPip -> VisualHome.PHONE
            state.chosen && state.display != null -> VisualHome.SECOND_SCREEN
            else -> VisualHome.PHONE
        }

    /**
     * The displays the Visual may go to: every connected presentation display but [own], the one the
     * app itself is on (DeX, or an app moved to the big screen, already shows the Visual there).
     */
    fun candidates(
        displays: List<ExternalDisplay>,
        own: Int?,
    ): List<ExternalDisplay> = displays.filter { it.id != own }

    /** The display the phone offers to show the Visual on, or null when there is nothing to offer. */
    fun offered(
        state: SecondScreenState,
        visualsOff: Boolean,
    ): ExternalDisplay? = state.display.takeIf { !visualsOff && !state.chosen }
}
