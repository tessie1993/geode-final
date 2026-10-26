#include "geode/viz/DisplayEvents.hpp"

namespace geode::viz {

GeodeFeatureFrame DisplayEvents::frame(const GeodeFeatureFrame& heard, bool live) {
    std::array<bool, GEODE_EVENT_COUNT> fired{};
    std::array<float, GEODE_EVENT_COUNT> strength{};
    for (int i = 0; i < GEODE_EVENT_COUNT; ++i) {
        const float seq = heard.events[i].seq;
        // A sequence back at 0 is an analysis reset, not an occurrence.
        fired[i] = live && seq != lastSeq_[i] && seq != 0.0f;
        strength[i] = fired[i] ? heard.events[i].strength : 0.0f;
        if (live) lastSeq_[i] = seq;
    }
    GeodeFeatureFrame out = heard;
    out.beat = fired[GEODE_EVENT_BEAT] ? 1.0f : 0.0f;
    out.beatStrength = strength[GEODE_EVENT_BEAT];
    out.transient = strength[GEODE_EVENT_TRANSIENT];
    out.kick = strength[GEODE_EVENT_KICK];
    out.snare = strength[GEODE_EVENT_SNARE];
    out.hat = strength[GEODE_EVENT_HAT];
    out.downbeat = fired[GEODE_EVENT_DOWNBEAT] ? 1.0f : 0.0f;
    out.sectionBoundary = fired[GEODE_EVENT_SECTION_BOUNDARY] ? 1.0f : 0.0f;
    out.drop = fired[GEODE_EVENT_DROP] ? 1.0f : 0.0f;
    out.arrival = fired[GEODE_EVENT_ARRIVAL] ? 1.0f : 0.0f;
    return out;
}

}  // namespace geode::viz
