package dev.geode.engine.types

// Conversions that lose precision truncate toward zero, which is what the integer arithmetic
// they replace did.

@JvmInline
value class Millis(
    val value: Long,
) : Comparable<Millis> {
    operator fun plus(other: Millis): Millis = Millis(value + other.value)

    operator fun minus(other: Millis): Millis = Millis(value - other.value)

    override fun compareTo(other: Millis): Int = value.compareTo(other.value)

    fun toMicros(): Micros = Micros(value * MICROS_PER_MILLI)

    fun toFrames(rate: Hz): Frames = Frames(value * rate.value / MILLIS_PER_SECOND)

    override fun toString(): String = "${value}ms"

    companion object {
        val ZERO = Millis(0L)
    }
}

@JvmInline
value class Micros(
    val value: Long,
) : Comparable<Micros> {
    operator fun plus(other: Micros): Micros = Micros(value + other.value)

    operator fun minus(other: Micros): Micros = Micros(value - other.value)

    override fun compareTo(other: Micros): Int = value.compareTo(other.value)

    fun toMillis(): Millis = Millis(value / MICROS_PER_MILLI)

    fun toFrames(rate: Hz): Frames = Frames(value * rate.value / MICROS_PER_SECOND)

    override fun toString(): String = "${value}us"

    companion object {
        val ZERO = Micros(0L)
    }
}

/** A sample rate. */
@JvmInline
value class Hz(
    val value: Int,
) {
    override fun toString(): String = "${value}Hz"
}

/** A count of audio sample frames: one sample per channel. */
@JvmInline
value class Frames(
    val value: Long,
) : Comparable<Frames> {
    operator fun plus(other: Frames): Frames = Frames(value + other.value)

    operator fun minus(other: Frames): Frames = Frames(value - other.value)

    override fun compareTo(other: Frames): Int = value.compareTo(other.value)

    fun toMillis(rate: Hz): Millis = Millis(value * MILLIS_PER_SECOND / rate.value)

    fun toMicros(rate: Hz): Micros = Micros(value * MICROS_PER_SECOND / rate.value)

    override fun toString(): String = "$value frames"
}

private const val MICROS_PER_MILLI = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val MICROS_PER_SECOND = 1_000_000L
