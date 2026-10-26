#pragma once
#include <vector>

namespace geode::analysis {

// Peak-profile whitening: each band over its own recent peak, so a quiet band's onset counts as much as a
// loud one's. A band is never lifted from below its floor (the caller's noise floor, or kRelativeFloorDb
// under the loudest band's peak, whichever is higher), so noise, hiss and leakage stay small; the ratio is
// then log-compressed, as onset detectors read a log spectrum.
class AdaptiveWhitening {
public:
    static constexpr float kDefaultFloor = 1e-12f;
    static constexpr float kRelativeFloorDb = 40.0f;
    static constexpr float kLogCompression = 3.0f;

    explicit AdaptiveWhitening(int bandCount, float peakDecaySeconds = 2.0f, float floor = kDefaultFloor);
    int bandCount() const { return bandCount_; }
    // input and floors are band powers (linear); out is 0..1.
    void whiten(const float* input, const float* floors, float dtSeconds, float* out);
    void reset();

private:
    int bandCount_;
    float peakDecaySeconds_;
    float floor_;
    std::vector<float> profile_;
};

}  // namespace geode::analysis
