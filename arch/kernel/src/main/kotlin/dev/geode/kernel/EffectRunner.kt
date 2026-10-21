package dev.geode.kernel

/**
 * Carries out a feature's [Perform] work: the feature's handlers, the only place it touches IO, the
 * audio adapter or the system (ADR-0005).
 */
interface EffectRunner<in W, out E> {
    /**
     * Carries out [work] and reports each result to [results], as many times as it has something to
     * say (none is fine), for as long as the work lasts: a subscription may report for the life of
     * the app.
     *
     * Runs on the store's background dispatcher. [results] may be called from any thread. Throwing
     * reports the failure to the app-wide [ErrorChannel] under the feature's name; a failure the
     * reducer should react to is better reported as an event.
     */
    suspend fun run(
        work: W,
        results: Results<E>,
    )
}

/** Where an [EffectRunner] reports what its work found out; each result reaches the reducer as an event. */
fun interface Results<in E> {
    fun report(event: E)
}
