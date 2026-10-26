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

}  // namespace geode::viz
