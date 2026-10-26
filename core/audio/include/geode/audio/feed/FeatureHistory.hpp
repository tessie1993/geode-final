#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>

#include "geode/api/geode_api.h"

namespace geode::audio::feed {

// The analysis frames of the last couple of seconds, each stamped with the feed frame its window is
// centred on, so the Visual can draw the frame that matches the audio being heard rather than the
// newest one, which describes audio still on its way to the speaker.
//
// One writer (the analysis) and any number of readers; a lock guards it, held only for a copy.
class FeatureHistory {
public:
    // At the analysis' 60 hops a second, a little over two seconds: more than any source runs ahead.
    static constexpr size_t kSlots = 128;

    void clear();
    void publish(uint32_t epoch, uint64_t stamp, const GeodeFeatureFrame& frame);
    // The newest frame of `epoch` stamped at or before `heard`; false when there is none.
    bool at(uint32_t epoch, uint64_t heard, GeodeFeatureFrame& out) const;
    size_t size() const;

private:
    struct Slot {
        uint32_t epoch = 0;
        uint64_t stamp = 0;
        GeodeFeatureFrame frame{};
    };

    mutable std::mutex lock_;
    std::array<Slot, kSlots> slots_{};
    size_t next_ = 0;
    size_t count_ = 0;
};

}  // namespace geode::audio::feed
