package dev.geode.visuals

import dev.geode.engine.bridge.CustomizationSnapshot
import dev.geode.engine.bridge.EngineFailure
import dev.geode.engine.bridge.NativeSnapshots
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.core.NativePlan
import dev.geode.render.bridge.SceneParamsCodec
import dev.geode.render.scene.SceneParams

/**
 * Customization's way into every live Visual the app draws (ADR-0002, ADR-0004, decision #194): the
 * Customization's own plan in one snapshot, handed to each Visual's exchange. The app's Visual and the
 * wallpaper each [open] their own exchange, since an exchange has one drawing reader; each gets the
 * newest snapshot at once, and every later one as Customization's handlers [offer] it.
 *
 * An exchange is never freed while a Visual might still read it: one let go with [release] waits here
 * for the next Visual that opens one, which is sent the newest snapshot again.
 */
class VisualSnapshots {
    private val open = mutableListOf<NativeSnapshots>()
    private val free = ArrayDeque<NativeSnapshots>()
    private var latest: CustomizationSnapshot? = null
    private var revision = 0

    /** Sends [snapshot] to every open exchange; the first refusal, if any. */
    @Synchronized
    fun offer(snapshot: CustomizationSnapshot): EngineFailure? {
        latest = snapshot
        return open.map { publish(it, snapshot) }.firstOrNull { it != null }
    }

    /** An exchange for one more Visual, holding the newest snapshot already. */
    @Synchronized
    fun open(): NativeSnapshots {
        val exchange = free.removeFirstOrNull() ?: NativeSnapshots()
        open += exchange
        latest?.let { publish(exchange, it) }
        return exchange
    }

    /** Lets [exchange] go once its Visual no longer reads it. */
    @Synchronized
    fun release(exchange: NativeSnapshots) {
        if (open.remove(exchange)) free += exchange
    }

    // Each crossing gets the next revision, so an exchange that is opened again takes the newest snapshot.
    private fun publish(
        exchange: NativeSnapshots,
        snapshot: CustomizationSnapshot,
    ): EngineFailure? = exchange.offer(snapshot.copy(revision = ++revision))

    companion object {
        // The analysis's own starting tuning (ReactiveAnalyzer): the Visual's exchange has no audio reader.
        private const val SOUND_SENSITIVITY = 3f
        private const val SOUND_REFRACTORY_MS = 60f
        private const val SOUND_ATTACK_SECONDS = 0.02f
        private const val SOUND_RELEASE_SECONDS = 0.15f

        // The scene parameters at their defaults: the Customization reaches the engine through its plan alone.
        private val defaultParams: List<Float> =
            FloatArray(SceneParamsCodec.FIELDS).also { SceneParamsCodec.pack(SceneParams.DEFAULT, it) }.toList()
        private val noLfos = List(CustomizationSnapshot.LFO_COUNT) { 0f }
        private val noEnvelopes = List(CustomizationSnapshot.ADSR_COUNT) { 0f }

        /**
         * [customization] as the engine reads it: its plan ([NativePlan]), with the scene parameters at
         * their defaults and the old LFO and envelope slots off, as no old parameter is mapped (#194).
         */
        fun snapshotOf(
            customization: Customization,
            reducedMotion: Boolean,
        ): CustomizationSnapshot =
            CustomizationSnapshot(
                revision = 0,
                reducedMotion = reducedMotion,
                params = defaultParams,
                lfo = noLfos,
                adsr = noEnvelopes,
                plan = NativePlan.encode(customization),
                soundSensitivity = SOUND_SENSITIVITY,
                soundRefractoryMs = SOUND_REFRACTORY_MS,
                soundAttackSeconds = SOUND_ATTACK_SECONDS,
                soundReleaseSeconds = SOUND_RELEASE_SECONDS,
            )
    }
}
