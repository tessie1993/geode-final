package dev.geode.engine.bridge

/**
 * Sends the Customization to the engine as a [CustomizationSnapshot], and only when it changed
 * (ADR-0004): the same snapshot offered twice crosses the bridge once. [publish] carries the encoded
 * bytes across and returns a [NativeStatus] code; a refused snapshot is reported through [failures]
 * and offered again next time, so nothing is lost to a moment of [NativeStatus.BUSY].
 */
class SnapshotPublisher(
    private val publish: (ByteArray) -> Int,
    private val failures: FailureRelay = NativeFailures,
) {
    private var sent: CustomizationSnapshot? = null

    /** The last snapshot the engine accepted, null before the first. */
    @Synchronized
    fun accepted(): CustomizationSnapshot? = sent

    /** Sends [snapshot] unless it is the one the engine already has; null when it is there now. */
    @Synchronized
    fun offer(snapshot: CustomizationSnapshot): EngineFailure? {
        if (snapshot == sent) return null
        val failure = failures.check(publish(snapshot.encode()), CALL)
        if (failure == null) sent = snapshot
        return failure
    }

    private companion object {
        const val CALL = "snapshotsPublish"
    }
}
