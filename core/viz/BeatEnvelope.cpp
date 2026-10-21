#include "geode/viz/BeatEnvelope.hpp"

#include <algorithm>

namespace geode::viz {

float BeatEnvelope::step(float impulse, float dt) {
    const float elapsed = std::max(dt, 0.0f);
    // The peak is the old instant pulse: it takes the hit at once and decays linearly.
    peak_ = std::max(std::max(impulse, peak_ - elapsed * kFallPerSecond), 0.0f);
    // The value chases it upwards at a bounded rate and follows it straight down.
    value_ = std::min(peak_, value_ + elapsed * kRisePerSecond);
    return value_;
}

void BeatEnvelope::reset() {
    peak_ = 0.0f;
    value_ = 0.0f;
}

}  // namespace geode::viz
