#include "geode/analysis/Decimator.hpp"

#include <algorithm>
#include <cmath>
#include <numbers>

namespace geode::analysis {

int decimationFor(int inputRateHz) {
    if (inputRateHz <= kMaxAnalysisRateHz) return 1;
    return (inputRateHz + kMaxAnalysisRateHz - 1) / kMaxAnalysisRateHz;
}

int analysisRateFor(int inputRateHz) { return inputRateHz / decimationFor(inputRateHz); }

Decimator::Decimator(int factor) { setFactor(factor); }

void Decimator::setFactor(int factor) {
    const int next = std::max(factor, 1);
    if (next == factor_ && !taps_.empty()) return;
    factor_ = next;
    taps_.clear();
    if (factor_ > 1) {
        const int count = kTapsPerFactor * factor_ + 1;
        const double centre = (count - 1) / 2.0;
        // Cycles per input sample.
        const double cutoff = kCutoff * 0.5 / factor_;
        double sum = 0.0;
        taps_.resize(static_cast<size_t>(count));
        for (int i = 0; i < count; ++i) {
            const double x = i - centre;
            const double sinc = x == 0.0 ? 2.0 * cutoff : std::sin(2.0 * std::numbers::pi * cutoff * x) / (std::numbers::pi * x);
            const double phase = 2.0 * std::numbers::pi * i / (count - 1);
            const double blackman = 0.42 - 0.5 * std::cos(phase) + 0.08 * std::cos(2.0 * phase);
            const double tap = sinc * blackman;
            taps_[static_cast<size_t>(i)] = static_cast<float>(tap);
            sum += tap;
        }
        // Unity gain at DC.
        for (float& tap : taps_) tap = static_cast<float>(tap / sum);
    }
    midHistory_.assign(taps_.size() * 2, 0.0f);
    sideHistory_.assign(taps_.size() * 2, 0.0f);
    reset();
}

void Decimator::reset() {
    std::fill(midHistory_.begin(), midHistory_.end(), 0.0f);
    std::fill(sideHistory_.begin(), sideHistory_.end(), 0.0f);
    pos_ = 0;
    phase_ = 0;
}

bool Decimator::push(float mid, float side, float& outMid, float& outSide) {
    if (factor_ <= 1) {
        outMid = mid;
        outSide = side;
        return true;
    }
    const size_t n = taps_.size();
    midHistory_[pos_] = mid;
    midHistory_[pos_ + n] = mid;
    sideHistory_[pos_] = side;
    sideHistory_[pos_ + n] = side;
    pos_ = pos_ + 1 == n ? 0 : pos_ + 1;
    if (++phase_ < factor_) return false;
    phase_ = 0;
    // The newest n samples, oldest first, start at pos_; the taps are symmetric, so their order is moot.
    const float* m = midHistory_.data() + pos_;
    const float* s = sideHistory_.data() + pos_;
    float accMid = 0.0f;
    float accSide = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        accMid += taps_[i] * m[i];
        accSide += taps_[i] * s[i];
    }
    outMid = accMid;
    outSide = accSide;
    return true;
}

}  // namespace geode::analysis
