#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "geode/analysis/Chromagram.hpp"
#include "geode/analysis/Decimator.hpp"
#include "geode/analysis/KeyDetector.hpp"
#include "geode/analysis/ReactiveAnalyzer.hpp"
#include "geode/api/geode_api.h"

namespace geode::analysis {

// The live and offline analysis. push() takes audio at the source's rate (sampleRateHz); a source above
// kMaxAnalysisRateHz is decimated to it by a whole factor first (Decimator), so the bands, the hop and
// the tempo mean the same at 44.1, 48, 96 and 192 kHz. analyze() takes windows already at the analysis'
// rate.
class AnalysisSession {
public:
    AnalysisSession(int sampleRateHz, int fftSize, float hopRateHz);

    // The source's rate: what push() is given from now on.
    void setSampleRateHz(int value);
    // The rate the analysis runs at, and the factor the source's rate is divided by to reach it.
    int analysisRateHz() const { return sampleRateHz_; }
    int decimation() const { return decimator_.factor(); }
    void setTuning(float sensitivity, float refractoryMs, float attackSeconds, float releaseSeconds);
    void reset();
    void analyze(const float* mid, const float* side, size_t frames, float dtSeconds, GeodeFeatureFrame& out);
    void push(const float* interleaved, size_t frames, int channels);
    bool pull(GeodeFeatureFrame& out);
    std::string key() const { return key_.finish(); }

private:
    void append(const float* mid, const float* side, size_t frames);
    void fill(const float* mid, const float* side, GeodeFeatureFrame& out) const;
    void sequence(GeodeFeatureFrame& out);
    void averagedWaveform(const float* mid, float* out) const;
    void decimatedWaveform(const float* mid, float* out) const;

    // The source's rate, and the analysis' (sampleRateHz_ = inputRateHz_ / decimation()).
    int inputRateHz_;
    int sampleRateHz_;
    int fftSize_;
    float hopRateHz_;
    int hopSamples_;
    ReactiveAnalyzer analyzer_;
    Chromagram chroma_;
    KeyDetector key_;
    std::vector<float> spectrum_;
    std::vector<float> buffer_;
    std::vector<float> sideBuffer_;
    size_t buffered_ = 0;
    Decimator decimator_;
    // One push's mid and side at the analysis' rate, before they join the buffers.
    std::vector<float> midIn_;
    std::vector<float> sideIn_;
    std::array<uint32_t, GEODE_EVENT_COUNT> eventSeq_{};
    std::array<float, GEODE_EVENT_COUNT> eventStrength_{};
};

}  // namespace geode::analysis
