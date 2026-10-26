#include "geode/analysis/LogBands.hpp"

#include <algorithm>
#include <cmath>

#include "geode/analysis/AdaptiveRange.hpp"

namespace geode::analysis {

LogBands::LogBands(int bandCount, int fftSize, int sampleRateHz, float minHz, float maxHz, float tiltDbPerOctave)
    : bandCount_(bandCount),
      fftSize_(fftSize),
      sampleRateHz_(sampleRateHz),
      minHz_(minHz),
      maxHz_(maxHz),
      tiltDbPerOctave_(tiltDbPerOctave),
      binCount_(fftSize / 2 + 1),
      firstBin_(static_cast<size_t>(bandCount)),
      lastBin_(static_cast<size_t>(bandCount)),
      tiltWeight_(static_cast<size_t>(binCount_)),
      magnitudeScale_(2.0f / fftSize) {
    rebuild();
}

void LogBands::setSampleRateHz(int value) {
    if (value != sampleRateHz_) {
        sampleRateHz_ = value;
        rebuild();
    }
}

float LogBands::lowerHz(int band) const {
    return (static_cast<float>(firstBin_[band]) - 0.5f) * binHz();
}

float LogBands::upperHz(int band) const {
    return (static_cast<float>(lastBin_[band]) + 0.5f) * binHz();
}

float LogBands::centreHz(int band) const {
    return 0.5f * static_cast<float>(firstBin_[band] + lastBin_[band]) * binHz();
}

void LogBands::energyDb(const float* magnitudes, float* out) const {
    energy(magnitudes, out);
    for (int b = 0; b < bandCount_; b++) {
        const float mean = out[b];
        out[b] = mean <= 0.0f ? AdaptiveRange::kSilenceDb : std::max(10.0f * std::log10(mean), AdaptiveRange::kSilenceDb);
    }
}

void LogBands::energy(const float* magnitudes, float* out) const {
    for (int b = 0; b < bandCount_; b++) {
        double power = 0.0;
        const int from = firstBin_[b];
        const int to = lastBin_[b];
        for (int k = from; k <= to; k++) {
            const float m = magnitudes[k] * magnitudeScale_;
            power += static_cast<double>(m) * m * tiltWeight_[k];
        }
        out[b] = static_cast<float>(power / (to - from + 1));
    }
}

void LogBands::rebuild() {
    const float nyquist = sampleRateHz_ / 2.0f;
    const float top = std::min(maxHz_, nyquist);
    const float bottom = std::min(minHz_, top * 0.5f);
    const float binWidth = binHz();

    const double logBottom = std::log(static_cast<double>(bottom));
    const double logTop = std::log(static_cast<double>(top));
    int cursor = 1;
    for (int b = 0; b < bandCount_; b++) {
        const float lo = static_cast<float>(std::exp(logBottom + (logTop - logBottom) * b / bandCount_));
        const float hi = static_cast<float>(std::exp(logBottom + (logTop - logBottom) * (b + 1) / bandCount_));
        const int wantFirst = std::max(cursor, static_cast<int>(lo / binWidth));
        const int first = std::min(wantFirst, binCount_ - 1);
        const int last = std::min(std::max(first, static_cast<int>(hi / binWidth)), binCount_ - 1);
        firstBin_[b] = first;
        // Where the log spacing is finer than the FFT, the bands step one bin apart but each still averages
        // kMinBins bins, sharing them with its neighbours, rather than following one bin's noise.
        lastBin_[b] = std::min(std::max(last, first + kMinBins - 1), binCount_ - 1);
        cursor = std::min(last + 1, binCount_ - 1);
    }

    const float exponent = tiltDbPerOctave_ / kPinkTiltDbPerOctave;
    for (int k = 0; k < binCount_; k++) {
        const float hz = k * binWidth;
        tiltWeight_[k] = (exponent == 0.0f || hz <= 0.0f)
            ? 1.0f
            : static_cast<float>(std::pow(static_cast<double>(hz / kTiltReferenceHz), static_cast<double>(exponent)));
    }
}

}  // namespace geode::analysis
