package dev.geode.engine.bridge

/**
 * Owns one native snapshot exchange (geode_snapshots): Kotlin offers the Customization here, and the
 * Visual and the analysis it is attached to pick it up at the start of their next frame without a
 * lock. Attach it with [GeodeNative.vizAttachSnapshots] and [GeodeNative.analysisAttachSnapshots],
 * and detach from both before [close].
 */
class NativeSnapshots : AutoCloseable {
    /** 0 once closed, or when the engine could not create the exchange. */
    @Volatile
    var handle: Long = GeodeNative.snapshotsCreate()
        private set

    private val publisher = SnapshotPublisher({ wire -> publishNow(wire) })

    /** Sends [snapshot] when it differs from the last one the engine accepted. */
    fun offer(snapshot: CustomizationSnapshot): EngineFailure? = publisher.offer(snapshot)

    @Synchronized
    override fun close() {
        val closing = handle
        if (closing == 0L) return
        handle = 0L
        NativeFailures.check(GeodeNative.snapshotsDestroy(closing), "snapshotsDestroy")
    }

    @Synchronized
    private fun publishNow(wire: ByteArray): Int = GeodeNative.snapshotsPublish(handle, wire)
}
