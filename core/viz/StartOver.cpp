#include "geode/viz/StartOver.hpp"

#include <algorithm>

namespace geode::viz::startover {

Step Request::step(const Frame& frame) {
    if (!asked_) return Step::None;
    if (!frame.drawing) {
        asked_ = false;
        return Step::None;
    }
    if (frame.changing) return Step::None;
    if (frame.milkdrop) {
        asked_ = false;
        return Step::ReloadPreset;
    }
    if (!frame.built) return Step::Build;
    asked_ = false;
    return Step::Fade;
}

float fadeSeconds(int64_t changeMs) {
    const float seconds = static_cast<float>(changeMs) / 1000.0f;
    return std::clamp(seconds, kMinFadeSeconds, kMaxFadeSeconds);
}

}  // namespace geode::viz::startover
