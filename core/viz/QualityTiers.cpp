#include "geode/viz/QualityTiers.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::quality {

Budgets resolve(const std::array<Declared, kTierCount>& declared) {
    Budgets out;
    for (int t = 0; t < kTierCount; ++t) {
        const Declared& d = declared[static_cast<size_t>(t)];
        Budget& b = out[static_cast<size_t>(t)];
        const float wanted = d.stepScale && std::isfinite(*d.stepScale) ? *d.stepScale : kDefaultStepScale[static_cast<size_t>(t)];
        b.stepScale = std::clamp(wanted, kMinStepScale, kMaxStepScale);
        b.effectsOff = d.effectsOff;
        if (t == 0) {
            b = Budget{};
            continue;
        }
        const Budget& cooler = out[static_cast<size_t>(t - 1)];
        b.stepScale = std::min(b.stepScale, cooler.stepScale);
        b.effectsOff |= cooler.effectsOff;
    }
    return out;
}

const Budgets& defaults() {
    static const Budgets kDefaults = resolve({});
    return kDefaults;
}

std::optional<int> tierNamed(std::string_view name) {
    for (int t = 0; t < kTierCount; ++t) {
        if (kTierNames[static_cast<size_t>(t)] == name) return t;
    }
    return std::nullopt;
}

int marchSteps(int detailSteps, float stepScale) {
    const float share = std::isfinite(stepScale) ? std::clamp(stepScale, 0.0f, kMaxStepScale) : kMaxStepScale;
    const int scaled = static_cast<int>(std::lround(static_cast<float>(detailSteps) * share));
    return std::max(std::min(scaled, detailSteps), std::min(kMinMarchSteps, detailSteps));
}

void FrameRateStep::step(float dt, int tier) {
    if (!std::isfinite(dt) || dt <= 0.0f) return;
    const bool hot = tier >= kEngageTier;
    if (hot == engaged_) {
        dwellSeconds_ = 0.0f;
        return;
    }
    dwellSeconds_ += dt;
    if (dwellSeconds_ >= (engaged_ ? kReleaseSeconds : kEngageSeconds)) {
        engaged_ = hot;
        dwellSeconds_ = 0.0f;
    }
}

void FrameRateStep::reset() {
    engaged_ = false;
    dwellSeconds_ = 0.0f;
}

}  // namespace geode::viz::quality
