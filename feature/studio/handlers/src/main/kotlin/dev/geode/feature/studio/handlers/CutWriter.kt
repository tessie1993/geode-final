package dev.geode.feature.studio.handlers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the Timeline's cut one write at a time, the newest winning (#318). The store runs each save on
 * a background thread of its own, so two saves may start in either order; each carries the number the
 * reducer gave it ([offer]'s revision), and a save older than one already written, or than one waiting,
 * is never written over it. Saves that pile up while one is written collapse into the newest. [read]
 * waits for what was offered before it to be written, so a cut read back is never older than the last
 * one kept.
 */
internal class CutWriter<T>(
    private val write: suspend (T) -> Unit,
) {
    private val lock = Mutex()
    private val waiting = Any()

    // Guarded by [waiting]: the newest value offered and not yet taken to be written.
    private var newest: Offer<T>? = null

    // Guarded by [lock]: the revision of the last value written.
    private var written = 0L

    /** Keeps [value] as revision [revision], unless a newer one is written or waits. It throws when the write fails. */
    suspend fun offer(
        revision: Long,
        value: T,
    ) {
        synchronized(waiting) {
            val held = newest
            if (held == null || revision > held.revision) newest = Offer(revision, value)
        }
        lock.withLock { flush() }
    }

    /** [block]'s result once everything offered so far is written. */
    suspend fun <R> read(block: suspend () -> R): R =
        lock.withLock {
            flush()
            block()
        }

    /** Writes the newest waiting value, if it is newer than the last written. Called under [lock]. */
    private suspend fun flush() {
        val next = synchronized(waiting) { newest.also { newest = null } } ?: return
        if (next.revision <= written) return
        write(next.value)
        written = next.revision
    }

    private class Offer<T>(
        val revision: Long,
        val value: T,
    )
}
