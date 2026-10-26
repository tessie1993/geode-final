#include "geode/analysis/AdaptiveWhitening.hpp"

#include <algorithm>
#include <cmath>

namespace geode::analysis {

namespace {
const float kRelativeFloor = std::pow(10.0f, -AdaptiveWhitening::kRelativeFloorDb / 10.0f);
const float kCompressionScale = 1.0f / std::log1p(AdaptiveWhitening::kLogCompression);
}  // namespace

AdaptiveWhitening::AdaptiveWhitening(int bandCount, float peakDecaySeconds, float floor)
    : bandCount_(bandCount), peakDecaySeconds_(peakDecaySeconds), floor_(floor), profile_(static_cast<size_t>(bandCount)) {}

void AdaptiveWhitening::whiten(const float* input, const float* floors, float dtSeconds, float* out) {
    const float decay = std::exp(-dtSeconds / peakDecaySeconds_);
    const float loudest = *std::max_element(profile_.begin(), profile_.end()) * decay;
    const float relative = std::max(loudest * kRelativeFloor, floor_);
    for (int b = 0; b < bandCount_; b++) {
        const float x = input[b];
        const float peak = std::max(std::max(x, std::max(floors[b], relative)), profile_[b] * decay);
        profile_[b] = peak;
        out[b] = std::log1p(kLogCompression * x / peak) * kCompressionScale;
    }
}

void AdaptiveWhitening::reset() {
    std::fill(profile_.begin(), profile_.end(), 0.0f);
}

}  // namespace geode::analysis
