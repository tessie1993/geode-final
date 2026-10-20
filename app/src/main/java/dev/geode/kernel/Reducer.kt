package dev.geode.kernel

/**
 * What a [Reducer] decided for one event: the state to move to, and the effects to carry out.
 *
 * The store publishes [state] first and then carries out [effects] in list order (ADR-0003).
 */
data class Next<out S, out F>(
    val state: S,
    val effects: List<F> = emptyList(),
)

/**
 * A feature's core: a pure function from the current state and one event to a [Next].
 *
 * It does no IO and reads no clock. Everything it wants done goes out as an effect, and everything
 * it learns comes back in as an event. States are sealed types matched with an exhaustive `when`.
 */
fun interface Reducer<S, in E, out F> {
    fun reduce(
        state: S,
        event: E,
    ): Next<S, F>
}
