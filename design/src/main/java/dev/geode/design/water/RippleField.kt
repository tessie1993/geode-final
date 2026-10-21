package dev.geode.design.water

/**
 * The ripples alive on one surface. Pure and immutable: a touch adds one, time prunes the finished
 * ones, and an empty field means the surface draws exactly as it would with no ripple at all.
 * At most [capacity] ripples run together; a new touch retires the oldest, so a burst of taps never
 * stacks into noise.
 */
data class RippleField(
    val ripples: List<Ripple> = emptyList(),
    val capacity: Int = MAX_RIPPLES,
) {
    init {
        require(capacity in 1..MAX_RIPPLES) { "a surface holds between 1 and $MAX_RIPPLES ripples" }
    }

    /** True when no ripple is alive, so nothing animates and the surface's layer stays cached. */
    val isIdle: Boolean get() = ripples.isEmpty()

    /** The field with a ripple from a touch at [ripple], dropping the oldest if it is full. */
    fun touched(ripple: Ripple): RippleField = copy(ripples = (ripples + ripple).takeLast(capacity))

    /** The field without the ripples that have finished by [nowNanos]. */
    fun prunedAt(
        nowNanos: Long,
        spec: RippleSpec,
    ): RippleField {
        val alive = ripples.filter { RipplePhysics.isAlive(it, nowNanos, spec) }
        return if (alive.size == ripples.size) this else copy(ripples = alive)
    }

    companion object {
        /** The most ripples one surface runs at once; the AGSL shader has a slot for each. */
        const val MAX_RIPPLES = 2
    }
}
