#include "geode/analysis/AdaptiveRange.hpp"

#include <algorithm>
#include <cmath>

namespace geode::analysis {

namespace {
float powerOf(float db) {
    return std::pow(10.0f, db / 10.0f);
}

float dbOf(double power) {
    return power <= 0.0 ? AdaptiveRange::kSilenceDb
                        : std::max(static_cast<float>(10.0 * std::log10(power)), AdaptiveRange::kSilenceDb);
}
}  // namespace

AdaptiveRange::AdaptiveRange(int bandCount, float warmupSeconds)
    : bandCount_(bandCount), warmupSeconds_(warmupSeconds), floorDb_(static_cast<size_t>(bandCount), kSilenceDb) {}

float AdaptiveRange::warmup() const {
    return std::clamp(adaptedSeconds_ / warmupSeconds_, 0.0f, 1.0f);
}

void AdaptiveRange::normalize(const float* inputDb, float dtSeconds, float* out, float* excess) {
    if (!floorsPrimed_) {
        for (int b = 0; b < bandCount_; b++) floorDb_[b] = std::min(inputDb[b], kNoiseCeilingDb);
        floorsPrimed_ = true;
    }

    bool adapted = false;
    int above = 0;
    double aboveDb = 0.0;
    for (int b = 0; b < bandCount_; b++) {
        const float x = inputDb[b];
        if (x > kSilenceDb) adapted = true;
        const float tau = x < floorDb_[b] ? kFloorFallSeconds : kFloorRiseSeconds;
        floorDb_[b] = std::min(follow(floorDb_[b], x, tau, dtSeconds), kNoiseCeilingDb);
        if (x > floorDb_[b] + kNoiseMarginDb) {
            above++;
            aboveDb += x;
        }
    }
    if (above >= kMinLevelBands) {
        const float level = static_cast<float>(aboveDb / above);
        if (!primed_) {
            levelDb_ = level;
            primed_ = true;
        } else {
            levelDb_ = follow(levelDb_, level, level > levelDb_ ? kLevelRiseSeconds : kLevelFallSeconds, dtSeconds);
        }
    }

    for (int b = 0; b < bandCount_; b++) {
        out[b] = scaled(inputDb[b], floorDb_[b]);
        if (excess) excess[b] = primed_ ? std::max(inputDb[b] - lowerDb(floorDb_[b]), 0.0f) / kSpanDb : 0.0f;
    }
    if (adapted && dtSeconds > 0.0f) adaptedSeconds_ += dtSeconds;
}

float AdaptiveRange::scaled(float levelDb, float floorDb) const {
    if (!primed_) return 0.0f;
    const float upper = levelDb_ + kHeadroomDb;
    const float lower = lowerDb(floorDb);
    if (upper <= lower) return 0.0f;
    return std::clamp((levelDb - lower) / (upper - lower), 0.0f, 1.0f);
}

float AdaptiveRange::scaledMean(const float* power, int from, int to) const {
    double level = 0.0;
    double floors = 0.0;
    for (int b = from; b < to; b++) {
        level += power[b];
        floors += powerOf(floorDb_[b]);
    }
    return scaled(dbOf(level / (to - from)), dbOf(floors / (to - from)));
}

float AdaptiveRange::floorPower(int band) const {
    return powerOf(floorDb_[band] + kNoiseMarginDb);
}

bool AdaptiveRange::heard(const float* power) const {
    double level = 0.0;
    double floors = 0.0;
    for (int b = 0; b < bandCount_; b++) {
        level += power[b];
        floors += powerOf(floorDb_[b]);
    }
    return level >= floors * powerOf(kPresenceDb);
}

void AdaptiveRange::reset() {
    std::fill(floorDb_.begin(), floorDb_.end(), kSilenceDb);
    floorsPrimed_ = false;
    primed_ = false;
    levelDb_ = kSilenceDb;
    adaptedSeconds_ = 0.0f;
}

float AdaptiveRange::lowerDb(float floorDb) const {
    return std::max(levelDb_ + kHeadroomDb - kSpanDb, floorDb + kNoiseMarginDb);
}

float AdaptiveRange::follow(float current, float target, float tauSeconds, float dtSeconds) {
    return current + (target - current) * (1.0f - std::exp(-dtSeconds / tauSeconds));
}

}  // namespace geode::analysis
