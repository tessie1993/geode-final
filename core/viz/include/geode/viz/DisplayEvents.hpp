#pragma once
#include <array>

#include "geode/api/geode_api.h"

namespace geode::viz {

// Port of DisplayEvents.kt for the native audio feed: turns the analysis frame heard now into the one
// a display frame draws, so each one-hop event appears on exactly one display frame at any frame rate.
//
// The analysis runs a hop at a time and the Visual reads once per display frame, so a one-hop impulse
// can fall between two reads, or be read twice. This edge-detects on the frame's sequenced events and
// writes each event that fired since the previous display frame into its one-hop field, with the
// strength it fired with; every other frame carries no event. One instance per reader, stepped once
// per display frame.
class DisplayEvents {
public:
    // `live` is false while nothing is heard: no event fires then, and the sequence numbers seen so far
    // are kept, so the silence (an empty frame, every sequence at 0) is not taken for a reset and the
    // first frame heard again does not fire every event at once.
    GeodeFeatureFrame frame(const GeodeFeatureFrame& heard, bool live);
    void reset() { lastSeq_.fill(0.0f); }

private:
    std::array<float, GEODE_EVENT_COUNT> lastSeq_{};
};

// The frame with every one-hop event taken out: no beat, transient, drum hit, downbeat, section
// boundary, drop or arrival fires in it. The sequenced events are kept as they were.
GeodeFeatureFrame withoutEvents(const GeodeFeatureFrame& frame);

// Port of SignalHold.kt for the native audio feed: freeze reactions (#366). While held, every display
// frame gets the Sound signals as they stood on the first held frame, with no one-hop event in them, so
// Reactions and Automations stay where they are and nothing fires while the Style's own motion goes
// on. Releasing it hands the live frames straight back. Stepped once per display frame after
// DisplayEvents, which keeps running underneath so events that fire during the hold are dropped.
class SignalHold {
public:
    GeodeFeatureFrame frame(const GeodeFeatureFrame& shown, bool held);
    // Whether the last frame was held, and the frozen one is what it showed.
    bool holding() const { return holding_; }

private:
    bool holding_ = false;
    GeodeFeatureFrame frozen_{};
};

}  // namespace geode::viz
