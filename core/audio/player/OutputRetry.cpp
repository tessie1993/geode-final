#include "geode/audio/player/OutputRetry.hpp"

#include <algorithm>

namespace geode::audio::player {

void OutputRetry::begin(int64_t nowMs) {
    if (pending_) return;
    pending_ = true;
    attempts_ = 0;
    nextAtMs_ = nowMs;
}

void OutputRetry::reset() {
    pending_ = false;
    attempts_ = 0;
    nextAtMs_ = 0;
}

bool OutputRetry::failed(int64_t nowMs) {
    if (!pending_) begin(nowMs);
    ++attempts_;
    if (attempts_ >= kMaxAttempts) {
        pending_ = false;
        return true;
    }
    const int64_t delay = std::min(kFirstDelayMs << (attempts_ - 1), kMaxDelayMs);
    nextAtMs_ = nowMs + delay;
    return false;
}

}  // namespace geode::audio::player
