#pragma once
#include <array>
#include <vector>

#include "geode/analysis/Spectrum.hpp"
#include "geode/api/geode_api.h"

namespace geode::viz {

// The audio inputs every shader Style reads from uAudioTex (lib_scene_uniforms.glsl): a log-frequency spectrum
// normalised on one scale, with a peak-hold row, and an oscilloscope that stands still (#300).
//
// The texture is kWidth texels by kRows rows. Rows 1-2 (read at y = 0.25) are the spectrum and rows 5-6
// (read at y = 0.75) the scope's mid, so a Style that samples the old two-row layout at 0.25 and 0.75
// reads them unchanged, with or without linear filtering between rows.
//
//   row 0     peak hold: each texel's highest recent level, held, then falling; frozen through silence
//   rows 1-2  spectrum: 30 Hz to 16 kHz on a log scale, smoothed with a fast attack and a slower decay
//   row 3     scope, left channel      } one fixed span of the window, aligned to the previous frame by
//   row 4     scope, right channel     } searching on the mid, the same offset for left and right;
//   rows 5-6  scope, mid               } 0.5 is silence, 0 and 1 full scale
//   row 7     spectrum, unsmoothed
//
// The spectrum is measured on the frame's stereo window. Its levels are in dB below a ceiling that
// follows the loudest texel (rising at once, falling kCeilingFallDbPerSecond), so the whole spectrum
// shares one scale and keeps its shape: bass-heavy music looks bass-heavy. The ceiling never drops
// under kMinCeilingDb, and anything kRangeDb under it reads 0, so a quiet room is not stretched to fill
// the picture. A frame with no window (an offline Render) reads the analysis' bands and waveform
// instead.
class AudioInputs {
public:
    static constexpr int kWidth = 512;
    static constexpr int kRows = 8;
    static constexpr int kPeakRow = 0;
    static constexpr int kSpectrumRow = 1;
    static constexpr int kScopeLeftRow = 3;
    static constexpr int kScopeRightRow = 4;
    static constexpr int kScopeRow = 5;
    static constexpr int kInstantRow = 7;

    static constexpr int kFftSize = 1024;
    // The window's rate is taken to be 48 kHz; at 44.1 kHz every frequency reads 9% high.
    static constexpr float kRateHz = 48000.0f;
    static constexpr float kLowHz = 30.0f;
    static constexpr float kHighHz = 16000.0f;
    static constexpr float kRangeDb = 60.0f;
    static constexpr float kMinCeilingDb = -24.0f;
    static constexpr float kCeilingFallDbPerSecond = 6.0f;
    // Pink noise, whose level falls 3 dB an octave, reads level across the spectrum.
    static constexpr float kTiltDbPerOctave = 3.0f;
    static constexpr float kTiltPivotHz = 1000.0f;
    // A window quieter than this (about -80 dBFS RMS) is silence.
    static constexpr float kSilenceRms = 1e-4f;
    static constexpr float kAttackHz = 30.0f;
    static constexpr float kDecayHz = 5.0f;
    static constexpr float kPeakHoldSeconds = 0.6f;
    static constexpr float kPeakFallPerSecond = 0.6f;
    // The scope's span of each window, in frames: 6 ms at 48 kHz, leaving as many offsets to align on.
    static constexpr int kScopeFrames = 288;

    AudioInputs();

    // This frame's stereo window, interleaved: what the frame draws.
    void acceptStereo(const float* interleaved, int frames);
    // Advances by dt and writes the kRows rows of kWidth texels into out, the levels scaled by drive.
    void update(float dt, float drive, const GeodeFeatureFrame& features, float* out);
    void reset();

    // The centre frequency of spectrum texel i.
    static float texelHz(int i);

    const std::array<float, kWidth>& spectrum() const { return spectrum_; }
    const std::array<float, kWidth>& instant() const { return instant_; }
    const std::array<float, kWidth>& peaks() const { return peak_; }
    const std::array<float, kWidth>& scopeMid() const { return scopeMid_; }
    const std::array<float, kWidth>& scopeLeft() const { return scopeLeft_; }
    const std::array<float, kWidth>& scopeRight() const { return scopeRight_; }
    float ceilingDb() const { return ceilingDb_; }
    // Where in the last window the scope's span began.
    int scopeOffset() const { return scopeOffset_; }

private:
    void measure(float dt, bool silent);
    void holdPeaks(float dt, bool silent);
    void align();
    void fallBack(const GeodeFeatureFrame& features);

    analysis::Spectrum fft_;
    std::vector<float> fftIn_;
    std::vector<float> stereo_;
    std::vector<float> mid_;
    int frames_ = 0;
    bool fresh_ = false;
    // Each texel's span of FFT bins, as fractional bin positions of its edges, and its tilt in dB.
    std::array<float, kWidth + 1> edgeBins_{};
    std::array<float, kWidth> tiltDb_{};
    std::array<float, kWidth> instant_{};
    std::array<float, kWidth> spectrum_{};
    std::array<float, kWidth> peak_{};
    std::array<float, kWidth> peakHold_{};
    float ceilingDb_ = kMinCeilingDb;
    std::array<float, kWidth> scopeMid_{};
    std::array<float, kWidth> scopeLeft_{};
    std::array<float, kWidth> scopeRight_{};
    std::vector<float> previousSpan_;
    bool hasPrevious_ = false;
    int scopeOffset_ = 0;
};

}  // namespace geode::viz
