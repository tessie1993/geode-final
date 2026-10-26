#pragma once
#include <cstddef>
#include <vector>

namespace geode::analysis {

// The highest rate the analysis runs at. Its FFT size, bands and hop are tuned for 44.1-48 kHz, so a
// hi-res source (88.2-192 kHz) is brought down to it first: otherwise every band shifts and the tempo
// reads x0.64 at 192 kHz.
constexpr int kMaxAnalysisRateHz = 48000;

// The whole factor a source at inputRateHz is divided by so the analysis runs at or below
// kMaxAnalysisRateHz: 1 up to 48 kHz, 2 for 88.2 and 96 kHz, 4 for 176.4 and 192 kHz.
int decimationFor(int inputRateHz);

// The rate the analysis runs at for a source at inputRateHz.
int analysisRateFor(int inputRateHz);

// Divides the rate of a mid/side pair of signals by a whole factor, through a windowed-sinc low-pass
// (Blackman, kTapsPerFactor taps per unit of factor) that removes what would otherwise fold back below
// the new Nyquist frequency. A factor of 1 passes the samples through untouched.
class Decimator {
public:
    static constexpr int kTapsPerFactor = 32;
    // The cut-off (-6 dB) as a fraction of the output rate's Nyquist frequency. At 48 kHz out: flat to
    // 16 kHz, -5 dB at 20 kHz, -63 dB at 24 kHz, and below -75 dB for anything that would fold back
    // under 22 kHz.
    static constexpr double kCutoff = 0.84;

    explicit Decimator(int factor = 1);

    // A new factor starts over from silence; the same factor changes nothing.
    void setFactor(int factor);
    int factor() const { return factor_; }
    size_t taps() const { return taps_.size(); }
    // Starts over from silence.
    void reset();
    // Takes one input sample of each signal. True, with the output pair, when it completes an output sample.
    bool push(float mid, float side, float& outMid, float& outSide);

private:
    int factor_ = 1;
    int phase_ = 0;
    size_t pos_ = 0;
    std::vector<float> taps_;
    // Each history twice over, so the newest taps_.size() samples are always contiguous from pos_.
    std::vector<float> midHistory_;
    std::vector<float> sideHistory_;
};

}  // namespace geode::analysis
