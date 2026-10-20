package dev.geode.feature.kernel.core

/**
 * Carries out a feature's [Perform] work: the only place a feature touches IO, the audio adapter or
 * another feature's `send`.
 */
interface EffectRunner<in W, out E> {
    /**
     * Carries out [work] and reports each result with [report], as many times as it has something
     * to say (none is fine). [report] is safe to call from any thread, during or after [run].
     *
     * The store starts [run] undispatched, on the thread that delivered the deciding event, so the
     * effects of one transition start in list order. Blocking work must move to another dispatcher.
     * A failure is a result: report it as an event rather than throwing.
     */
    suspend fun run(
        work: W,
        report: (E) -> Unit,
    )
}
