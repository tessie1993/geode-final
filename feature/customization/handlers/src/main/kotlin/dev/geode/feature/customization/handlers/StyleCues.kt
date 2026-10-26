package dev.geode.feature.customization.handlers

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The live Visual's word that the engine's Style switching is due (#282). The renderer [raise]s it on
 * its own thread each time the engine's count of due switches rises; Customization's handlers follow
 * [cues] and report each one to the reducer as `CustomizationEvent.StyleDue`. One cue waits for a slow
 * collector; more in the meantime are the same switch.
 */
class StyleCues {
    private val due = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each cue, while it is being followed; none is replayed. */
    val cues: Flow<Unit> = due.asSharedFlow()

    /** Any thread. */
    fun raise() {
        due.tryEmit(Unit)
    }
}
