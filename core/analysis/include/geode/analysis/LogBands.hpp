#pragma once
#include <vector>

namespace geode::analysis {

// The spectrum folded into bandCount bands from minHz to maxHz: logarithmic where the FFT resolves it,
// one bin apart where it does not. Every band averages at least kMinBins bins, so no band is the
// power of a single bin (which jumps by several dB from hop to hop on any noise-like sound); at the
// low end neighbouring bands share bins. The frequencies a band reports are those of the bins it
// really averages, not nominal edges.
class LogBands {
public:
    static constexpr float kDefaultMinHz = 30.0f;
    static constexpr float kDefaultMaxHz = 16000.0f;
    static constexpr float kPinkTiltDbPerOctave = 3.0103f;
    static constexpr float kTiltReferenceHz = 1000.0f;
    static constexpr int kMinBins = 3;

    LogBands(int bandCount, int fftSize, int sampleRateHz, float minHz = kDefaultMinHz, float maxHz = kDefaultMaxHz,
             float tiltDbPerOctave = kPinkTiltDbPerOctave);

    int bandCount() const { return bandCount_; }
    int sampleRateHz() const { return sampleRateHz_; }
    void setSampleRateHz(int value);
    int firstBin(int band) const { return firstBin_[band]; }
    int lastBin(int band) const { return lastBin_[band]; }
    // The edges of the bins the band averages, and their centre.
    float lowerHz(int band) const;
    float upperHz(int band) const;
    float centreHz(int band) const;
    void energyDb(const float* magnitudes, float* out) const;
    void energy(const float* magnitudes, float* out) const;

private:
    void rebuild();
    float binHz() const { return static_cast<float>(sampleRateHz_) / fftSize_; }

    int bandCount_;
    int fftSize_;
    int sampleRateHz_;
    float minHz_;
    float maxHz_;
    float tiltDbPerOctave_;
    int binCount_;
    std::vector<int> firstBin_;
    std::vector<int> lastBin_;
    std::vector<float> tiltWeight_;
    float magnitudeScale_;
};

}  // namespace geode::analysis
