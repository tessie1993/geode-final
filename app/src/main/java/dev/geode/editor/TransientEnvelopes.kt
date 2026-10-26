package dev.geode.editor

import dev.geode.analysis.AudioFeatures
import dev.geode.analysis.FeatureTimeline
import dev.geode.feature.studio.api.edit.TransientEnvelope
import dev.geode.feature.studio.api.edit.TransientSource

/** The song's offline [timeline] as auto-cut's envelope (#318): one measured channel, [source], from each frame. */
fun transientEnvelopeOf(
    timeline: FeatureTimeline,
    source: TransientSource = TransientSource.TRANSIENT,
): TransientEnvelope =
    TransientEnvelope(
        timesMs = timeline.frames.map { it.timeMs },
        strength = timeline.frames.map { source.read(it.features) },
    )

private fun TransientSource.read(features: AudioFeatures): Float =
    when (this) {
        TransientSource.TRANSIENT -> features.transient
        TransientSource.SPECTRAL_FLUX -> features.flux
        TransientSource.ONSET -> features.onset
        TransientSource.KICK -> features.kick
        TransientSource.SNARE -> features.snare
        TransientSource.HAT -> features.hat
    }
