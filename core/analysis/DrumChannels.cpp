#include "geode/analysis/DrumChannels.hpp"

#include <algorithm>

namespace geode::analysis {

namespace {
constexpr float kRangeHz[] = {DrumChannels::kKickLowHz,   DrumChannels::kKickHighHz,   DrumChannels::kBodyLowHz,
                              DrumChannels::kBodyHighHz,  DrumChannels::kRattleLowHz, DrumChannels::kRattleHighHz,
                              DrumChannels::kHatLowHz,    DrumChannels::kHatHighHz};
}  // namespace

DrumChannels::DrumChannels(int bandCount, float hopRateHz, int sampleRateHz, int fftSize)
    : bandCount_(bandCount), holdFallPerHop_(kKickHoldFall / hopRateHz), layout_(bandCount, fftSize, sampleRateHz) {
    for (int c = 0; c < kChannels; c++) pickers_.emplace_back(hopRateHz, 1.5f, 3.0f, kRefractorySeconds, 0.03f, kMinStrength);
    setSampleRateHz(sampleRateHz);
}

void DrumChannels::setSensitivity(float value) {
    for (auto& p : pickers_) p.setSensitivity(value);
}

void DrumChannels::setSampleRateHz(int sampleRateHz) {
    layout_.setSampleRateHz(sampleRateHz);
    fluxes_.clear();
    for (int r = 0; r < kRanges; r++) {
        // Bands rise in frequency, so each range is the run of bands from the first centre at or above its
        // low edge to the first at or above its high edge.
        int from = 0;
        while (from < bandCount_ && layout_.centreHz(from) < kRangeHz[r * 2]) from++;
        int to = from;
        while (to < bandCount_ && layout_.centreHz(to) < kRangeHz[r * 2 + 1]) to++;
        from_[r] = from;
        to_[r] = to;
        fluxes_.emplace_back(std::max(to - from, 1));
    }
}

float DrumChannels::rise(int range, const float* bands) {
    return to_[range] > from_[range] ? fluxes_[range].next(bands + from_[range]) : 0.0f;
}

float DrumChannels::kickRise(const float* bands) {
    float level = 0.0f;
    for (int b = from_[Kick]; b < to_[Kick]; b++) level = std::max(level, bands[b]);
    const float rise = kickSeen_ ? std::max(level - std::max(kickLevel_, kickHold_), 0.0f) : 0.0f;
    kickSeen_ = true;
    kickLevel_ = level;
    kickHold_ = std::max(level, kickHold_ - holdFallPerHop_);
    return rise;
}

void DrumChannels::step(const float* bands) {
    const float kick = kickRise(bands);
    const float body = rise(Body, bands);
    const float rattle = rise(Rattle, bands);
    const float hat = rise(Hat, bands);
    const int bodyBands = to_[Body] - from_[Body];
    const int rattleBands = to_[Rattle] - from_[Rattle];
    // The snare's two ranges as one: the mean rise over all their bands.
    const float snare = (body * bodyBands + rattle * rattleBands) / static_cast<float>(std::max(bodyBands + rattleBands, 1));

    const bool kicked = pickers_[0].accept(kick, rattle < kRattleShare * kick);
    const bool snared = pickers_[1].accept(snare, rattle >= kRattleShare * kick && body >= kBodyShare * rattle);
    const bool hatted = pickers_[2].accept(hat, hat > body);
    kick_ = kicked ? pickers_[0].strength() : 0.0f;
    snare_ = snared ? pickers_[1].strength() : 0.0f;
    hat_ = hatted ? pickers_[2].strength() : 0.0f;
}

void DrumChannels::reset() {
    for (auto& p : pickers_) p.reset();
    for (auto& f : fluxes_) f.reset();
    kickSeen_ = false;
    kickLevel_ = 0.0f;
    kickHold_ = 0.0f;
    kick_ = 0.0f;
    snare_ = 0.0f;
    hat_ = 0.0f;
}

}  // namespace geode::analysis
