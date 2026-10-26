#include "geode/audio/feed/FeatureHistory.hpp"

namespace geode::audio::feed {

void FeatureHistory::clear() {
    std::lock_guard<std::mutex> guard(lock_);
    next_ = 0;
    count_ = 0;
}

void FeatureHistory::publish(uint32_t epoch, uint64_t stamp, const GeodeFeatureFrame& frame) {
    std::lock_guard<std::mutex> guard(lock_);
    Slot& slot = slots_[next_];
    slot.epoch = epoch;
    slot.stamp = stamp;
    slot.frame = frame;
    next_ = (next_ + 1) % kSlots;
    if (count_ < kSlots) ++count_;
}

bool FeatureHistory::at(uint32_t epoch, uint64_t heard, GeodeFeatureFrame& out) const {
    std::lock_guard<std::mutex> guard(lock_);
    // Newest first: the first match is the latest frame the listener has reached.
    for (size_t back = 1; back <= count_; ++back) {
        const Slot& slot = slots_[(next_ + kSlots - back) % kSlots];
        if (slot.epoch != epoch || slot.stamp > heard) continue;
        out = slot.frame;
        return true;
    }
    return false;
}

size_t FeatureHistory::size() const {
    std::lock_guard<std::mutex> guard(lock_);
    return count_;
}

}  // namespace geode::audio::feed
