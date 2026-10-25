package dev.geode.feature.customization.handlers

import dev.geode.engine.bridge.CustomizationSnapshot
import dev.geode.engine.bridge.EngineFailure
import dev.geode.engine.bridge.NativeStatus
import dev.geode.feature.customization.api.Customization

/**
 * Hands a Customization to the engine as one snapshot (ADR-0002, ADR-0004): nothing crosses per
 * frame, only a changed Customization.
 *
 * - [snapshotOf] builds the whole snapshot for a Customization. It is `:app`'s to give, because the
 *   snapshot also carries what is not Customization's: the engine's scene parameters for the drawing
 *   path that the plan does not drive yet, the reduce-motion flag and the Sound signal tuning. Its
 *   `plan` field is the Customization itself, encoded by `NativePlan` (#54).
 * - [offer] carries it across the bridge: `SnapshotPublisher.offer` or `NativeSnapshots.offer`.
 *
 * Each snapshot whose content changed gets the next revision. A snapshot refused because another one
 * was being published at that moment ([NativeStatus.BUSY]) is offered again, a few times, before the
 * refusal counts.
 */
class EnginePublisher(
    private val snapshotOf: (Customization) -> CustomizationSnapshot,
    private val offer: (CustomizationSnapshot) -> EngineFailure?,
) {
    private var revision = 0

    // The content of the last snapshot the engine took, its revision set to 0.
    private var taken: CustomizationSnapshot? = null

    /** Sends [customization] unless the engine already has the same snapshot; the failure, if it refused. */
    @Synchronized
    fun publish(customization: Customization): EngineFailure? {
        val content = snapshotOf(customization).copy(revision = 0)
        if (content == taken) return null
        revision++
        val snapshot = content.copy(revision = revision)
        var failure = offer(snapshot)
        var tries = 1
        while (failure.isBusy() && tries < BUSY_TRIES) {
            failure = offer(snapshot)
            tries++
        }
        if (failure == null) taken = content
        return failure
    }

    private fun EngineFailure?.isBusy(): Boolean = this is EngineFailure.SnapshotRefused && status == NativeStatus.BUSY

    private companion object {
        /** How many times a snapshot is offered while the engine is busy with another. */
        const val BUSY_TRIES = 3
    }
}
