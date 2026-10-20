#pragma once
#include <array>
#include <vector>

#include "geode/analysis/LogBands.hpp"
#include "geode/analysis/OnsetPeakPicker.hpp"
#include "geode/analysis/SuperFlux.hpp"

namespace geode::analysis {

class DrumChannels {
public:
    DrumChannels(int bandCount, float hopRateHz, int sampleRateHz, float minHz = LogBands::kDefaultMinHz,
                 float maxHz = LogBands::kDefaultMaxHz);

    int bandCount() const { return bandCount_; }
    float kick() const { return kick_; }
    float snare() const { return snare_; }
    float hat() const { return hat_; }
    float sensitivity() const { return pickers_[0].sensitivity(); }
    void setSensitivity(float value);
    // Recomputes the per-channel band range (from_/to_) for a new sample rate in place, keeping the pickers'
    // and fluxes' onset history instead of discarding it by reallocating a new DrumChannels.
    void setSampleRateHz(int sampleRateHz);
    void step(const float* bands);
    void reset();

private:
    static constexpr int kChannels = 3;
    static constexpr float kEdges[kChannels * 2] = {30.0f, 120.0f, 120.0f, 900.0f, 4000.0f, 16000.0f};

    int bandCount_;
    int sampleRateHz_;
    float minHz_;
    float maxHz_;
    std::vector<OnsetPeakPicker> pickers_;
    std::vector<SuperFlux> fluxes_;
    std::vector<std::vector<float>> slices_;
    std::array<int, kChannels> from_{};
    std::array<int, kChannels> to_{};
    float kick_ = 0.0f;
    float snare_ = 0.0f;
    float hat_ = 0.0f;
};

}  // namespace geode::analysis
