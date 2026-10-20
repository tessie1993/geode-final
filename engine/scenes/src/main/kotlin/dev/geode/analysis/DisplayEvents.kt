package dev.geode.analysis

import dev.geode.engine.audio.SoundEvent
import dev.geode.engine.audio.SoundEventEdges

/**
 * Turns the newest live frame into the one a display frame should draw: each one-hop event appears
 * on exactly one display frame, at any frame rate.
 *
 * The live analysis publishes a frame per hop and the renderer reads the newest once per display
 * frame, so at 30 fps half the hops are never read and at 120 fps each is read twice. Rather than
 * holding impulses for a few hops (which repeated them for every reader that does not edge-detect),
 * this edge-detects on the frame's sequenced [AudioFeatures.events] and writes each event that fired
 * since the previous display frame into its one-hop field, with the strength it fired with. Every
 * other frame carries no event. One instance per reader, stepped once per display frame.
 */
class DisplayEvents {
    private val edges = SoundEventEdges()

    fun frame(f: AudioFeatures): AudioFeatures {
        edges.step(f.events)
        return f.copy(
            beat = edges.fired(SoundEvent.BEAT),
            beatStrength = edges.strength(SoundEvent.BEAT),
            transient = edges.strength(SoundEvent.TRANSIENT),
            kick = edges.strength(SoundEvent.KICK),
            snare = edges.strength(SoundEvent.SNARE),
            hat = edges.strength(SoundEvent.HAT),
            downbeat = edges.fired(SoundEvent.DOWNBEAT),
            sectionBoundary = edges.fired(SoundEvent.SECTION_BOUNDARY),
            drop = edges.fired(SoundEvent.DROP),
            arrival = edges.fired(SoundEvent.ARRIVAL),
        )
    }
}
