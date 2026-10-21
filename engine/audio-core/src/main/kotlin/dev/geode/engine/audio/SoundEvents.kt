package dev.geode.engine.audio

/** The one-hop Sound signals, in the order of the native frame's `events` (core/api/include/geode/api/geode_api.h). */
enum class SoundEvent {
    BEAT,
    TRANSIENT,
    KICK,
    SNARE,
    HAT,
    DOWNBEAT,
    SECTION_BOUNDARY,
    DROP,
    ARRIVAL,
    ;

    companion object {
        val COUNT: Int = entries.size
    }
}

/**
 * The sequenced events of one analysis frame: for each [SoundEvent], how many times it has fired
 * since the analysis started or was reset, and the strength it fired with last.
 *
 * A one-hop impulse is gone by the next hop, and a live frame is read once per display frame, so
 * an impulse alone can fall between two reads. These counts stay until the next occurrence;
 * [SoundEventEdges] turns them into "fired since the last look".
 */
class SoundEvents private constructor(
    private val floats: FloatArray,
) {
    /** 0 until the event first fires, then 1, 2, 3 ... */
    fun seq(event: SoundEvent): Int = floats[event.ordinal * 2].toInt()

    /** The strength the event fired with last; 0 before it first fires. */
    fun strength(event: SoundEvent): Float = floats[event.ordinal * 2 + 1]

    /** Lays these out as the native frame's `events` block, starting at [offset] of [frame]. */
    fun writeTo(
        frame: FloatArray,
        offset: Int,
    ) {
        floats.copyInto(frame, offset)
    }

    companion object {
        val NONE: SoundEvents = SoundEvents(FloatArray(SoundEvent.COUNT * 2))

        /** Reads the native frame's `events` block, which starts at [offset] of [frame]. */
        fun read(
            frame: FloatArray,
            offset: Int,
        ): SoundEvents = SoundEvents(frame.copyOfRange(offset, offset + SoundEvent.COUNT * 2))
    }
}

/**
 * Edge detection on [SoundEvents]: which events fired since the previous [step], whatever the rate
 * of the steps. An event has fired when its sequence number moved, so each occurrence is reported
 * on exactly one step, never repeated and never lost. Occurrences of one event that land between
 * the same two steps (closer together than a step) report once, with the latest strength.
 */
class SoundEventEdges {
    private val lastSeq = IntArray(SoundEvent.COUNT)
    private val fired = BooleanArray(SoundEvent.COUNT)
    private val strength = FloatArray(SoundEvent.COUNT)

    fun step(events: SoundEvents) {
        for (event in SoundEvent.entries) {
            val i = event.ordinal
            val seq = events.seq(event)
            // A sequence back at 0 is an analysis reset, not an occurrence.
            fired[i] = seq != lastSeq[i] && seq != 0
            strength[i] = if (fired[i]) events.strength(event) else 0f
            lastSeq[i] = seq
        }
    }

    fun fired(event: SoundEvent): Boolean = fired[event.ordinal]

    /** The strength [event] fired with since the previous step; 0 when it did not fire. */
    fun strength(event: SoundEvent): Float = strength[event.ordinal]
}
