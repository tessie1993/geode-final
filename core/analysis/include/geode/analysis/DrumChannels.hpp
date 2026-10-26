#pragma once
#include <array>
#include <vector>

#include "geode/analysis/LogBands.hpp"
#include "geode/analysis/OnsetPeakPicker.hpp"
#include "geode/analysis/SuperFlux.hpp"

namespace geode::analysis {

// Kick, snare and hat hits, from band levels on the shared scale (AdaptiveRange, in spans). Each reads the
// rise of the bands whose real centre lies in its range: kick 40-100 Hz; snare its body (150-300 Hz) and its
// rattle (2-6 kHz) together; hat 7-16 kHz. One sound rises in several ranges, so each channel also checks the
// others: a kick does not bring the rattle with it (a snare does), a snare has a body under its rattle (a
// hat has not), and a hat has no body rising with it. A hit must also be at least kMinStrength of that
// channel's recent hits.
//
// The kick reads its range's loudest band against a hold of its last peak that falls by kKickHoldFall spans
// a second: a kick's tail beating against the bass line under it swells again a moment later, and that swell
// stays under the hold where a new kick does not.
class DrumChannels {
public:
    static constexpr float kKickLowHz = 40.0f;
    static constexpr float kKickHighHz = 100.0f;
    static constexpr float kBodyLowHz = 150.0f;
    static constexpr float kBodyHighHz = 300.0f;
    static constexpr float kRattleLowHz = 2000.0f;
    static constexpr float kRattleHighHz = 6000.0f;
    static constexpr float kHatLowHz = 7000.0f;
    static constexpr float kHatHighHz = 16000.0f;
    static constexpr float kRefractorySeconds = 0.05f;
    static constexpr float kMinStrength = 0.5f;
    // A kick's rattle rises by less than this share of its kick range; a snare's by at least as much.
    static constexpr float kRattleShare = 0.5f;
    // A snare's body rises by at least this share of its rattle.
    static constexpr float kBodyShare = 0.15f;
    static constexpr float kKickHoldFall = 3.0f;

    DrumChannels(int bandCount, float hopRateHz, int sampleRateHz, int fftSize = 2048);

    int bandCount() const { return bandCount_; }
    float kick() const { return kick_; }
    float snare() const { return snare_; }
    float hat() const { return hat_; }
    float sensitivity() const { return pickers_[0].sensitivity(); }
    void setSensitivity(float value);
    // The bands of each range for a new sample rate, keeping the pickers' onset history.
    void setSampleRateHz(int sampleRateHz);
    // The first band of each range and one past its last: kick, body, rattle, hat.
    int rangeFrom(int range) const { return from_[range]; }
    int rangeTo(int range) const { return to_[range]; }
    void step(const float* bands);
    void reset();

private:
    enum Range { Kick, Body, Rattle, Hat, kRanges };
    static constexpr int kChannels = 3;

    float rise(int range, const float* bands);
    float kickRise(const float* bands);

    int bandCount_;
    float holdFallPerHop_;
    LogBands layout_;
    std::vector<OnsetPeakPicker> pickers_;
    std::vector<SuperFlux> fluxes_;
    std::array<int, kRanges> from_{};
    std::array<int, kRanges> to_{};
    bool kickSeen_ = false;
    float kickLevel_ = 0.0f;
    float kickHold_ = 0.0f;
    float kick_ = 0.0f;
    float snare_ = 0.0f;
    float hat_ = 0.0f;
};

}  // namespace geode::analysis
