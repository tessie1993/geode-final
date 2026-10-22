package dev.geode.engine.bridge

import dev.geode.engine.types.Millis

/**
 * Decides when the audio restarts itself after the engine failed inside a playback call (ADR-0005):
 * only for a throw the bridge caught in [EngineSubsystem.AUDIO], and at most [maxRestarts] times in
 * any [window], so an engine that fails on every call cannot spin in a restart loop.
 */
class AudioRestartPolicy(
    private val now: () -> Millis,
    private val maxRestarts: Int = MAX_RESTARTS,
    private val window: Millis = WINDOW,
) {
    private val recent = ArrayDeque<Millis>()

    /** Whether [failure] restarts the audio now; a yes counts against the budget. */
    @Synchronized
    fun shouldRestart(failure: EngineFailure): Boolean {
        if (!failure.caughtAtTheBridge || failure.subsystem != EngineSubsystem.AUDIO) return false
        val at = now()
        while (recent.isNotEmpty() && at - recent.first() >= window) recent.removeFirst()
        if (recent.size >= maxRestarts) return false
        recent.addLast(at)
        return true
    }

    companion object {
        const val MAX_RESTARTS = 3
        val WINDOW = Millis(60_000L)
    }
}
