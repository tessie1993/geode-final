package dev.geode.analysis

/**
 * Freezes the live Visual's reactions while the picture keeps moving (#366). While [held], every
 * display frame gets the Sound signals as they stood on the first held frame, with no one-hop event in
 * them: Reactions and Automations stay where they are and nothing fires, while the Style's own
 * time-based motion and the Modulators go on. Releasing it hands the live frames straight back.
 *
 * Live only: nothing of it is stored in a Customization or a preset, and Renders never use it. One
 * instance per reader, stepped once per display frame after [DisplayEvents], which keeps running
 * underneath so the events that fire during the hold are dropped rather than replayed on release.
 */
class SignalHold {
    private var frozen: AudioFeatures? = null

    var held: Boolean = false
        set(value) {
            if (!value) frozen = null
            field = value
        }

    fun frame(live: AudioFeatures): AudioFeatures {
        if (!held) return live
        return frozen ?: live.withoutEvents().also { frozen = it }
    }
}
