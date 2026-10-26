#include "geode/viz/AudioInputs.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numbers>

namespace geode::viz {

namespace {

// As ShaderScene's own audio clamp: drive may raise a level past 1, never past this.
constexpr float kLevelClamp = 1.5f;
// The quietest level a texel is measured at, in dB, so a bin of exact silence has a finite level.
constexpr float kFloorDb = -200.0f;

float slewTo(float current, float target, float dt, float riseHz, float fallHz) {
    const float hz = target > current ? riseHz : fallHz;
    return current + (target - current) * (1.0f - std::exp(-std::max(dt, 0.0f) * hz));
}

float level(float value, float drive) { return std::clamp(value * drive, 0.0f, kLevelClamp); }

// A scope sample as the texture holds it: 0.5 is silence.
float scopeTexel(float value, float drive) { return std::clamp(value * drive * 0.5f + 0.5f, 0.0f, 1.0f); }

float sane(float value) { return std::isfinite(value) ? value : 0.0f; }

}  // namespace

AudioInputs::AudioInputs() : fft_(kFftSize), fftIn_(kFftSize, 0.0f), previousSpan_(kScopeFrames, 0.0f) {
    const float binHz = kRateHz / static_cast<float>(kFftSize);
    for (int i = 0; i <= kWidth; ++i) {
        // Edge i lies half a texel below texel i's centre, on the same log scale.
        const float position = (static_cast<float>(i) - 0.5f) / static_cast<float>(kWidth - 1);
        edgeBins_[static_cast<size_t>(i)] = kLowHz * std::pow(kHighHz / kLowHz, position) / binHz;
    }
    for (int i = 0; i < kWidth; ++i) tiltDb_[static_cast<size_t>(i)] = kTiltDbPerOctave * std::log2(texelHz(i) / kTiltPivotHz);
}

float AudioInputs::texelHz(int i) {
    return kLowHz * std::pow(kHighHz / kLowHz, static_cast<float>(i) / static_cast<float>(kWidth - 1));
}

void AudioInputs::acceptStereo(const float* interleaved, int frames) {
    if (!interleaved || frames <= 0) return;
    const int n = std::min(frames, kFftSize);
    const float* from = interleaved + static_cast<size_t>(frames - n) * 2;
    stereo_.resize(static_cast<size_t>(n) * 2);
    std::transform(from, from + static_cast<size_t>(n) * 2, stereo_.begin(), sane);
    frames_ = n;
    fresh_ = true;
}

void AudioInputs::reset() {
    instant_.fill(0.0f);
    spectrum_.fill(0.0f);
    peak_.fill(0.0f);
    peakHold_.fill(0.0f);
    scopeMid_.fill(0.0f);
    scopeLeft_.fill(0.0f);
    scopeRight_.fill(0.0f);
    ceilingDb_ = kMinCeilingDb;
    hasPrevious_ = false;
    scopeOffset_ = 0;
    fresh_ = false;
    frames_ = 0;
}

void AudioInputs::update(float dt, float drive, const GeodeFeatureFrame& features, float* out) {
    bool silent = false;
    if (fresh_) {
        mid_.resize(static_cast<size_t>(frames_));
        double energy = 0.0;
        for (int i = 0; i < frames_; ++i) {
            const float m = 0.5f * (stereo_[static_cast<size_t>(2 * i)] + stereo_[static_cast<size_t>(2 * i + 1)]);
            mid_[static_cast<size_t>(i)] = m;
            energy += static_cast<double>(m) * m;
        }
        silent = std::sqrt(energy / frames_) < kSilenceRms;
        measure(dt, silent);
        align();
        fresh_ = false;
    } else {
        silent = !(sane(features.rms) >= kSilenceRms);
        fallBack(features);
    }
    for (size_t i = 0; i < kWidth; ++i) spectrum_[i] = slewTo(spectrum_[i], instant_[i], dt, kAttackHz, kDecayHz);
    holdPeaks(dt, silent);
    if (!out) return;
    for (size_t i = 0; i < kWidth; ++i) {
        const float spectrum = level(spectrum_[i], drive);
        out[kPeakRow * kWidth + i] = level(peak_[i], drive);
        out[kSpectrumRow * kWidth + i] = spectrum;
        out[(kSpectrumRow + 1) * kWidth + i] = spectrum;
        out[kScopeLeftRow * kWidth + i] = scopeTexel(scopeLeft_[i], drive);
        out[kScopeRightRow * kWidth + i] = scopeTexel(scopeRight_[i], drive);
        const float mid = scopeTexel(scopeMid_[i], drive);
        out[kScopeRow * kWidth + i] = mid;
        out[(kScopeRow + 1) * kWidth + i] = mid;
        out[kInstantRow * kWidth + i] = level(instant_[i], drive);
    }
}

void AudioInputs::measure(float dt, bool silent) {
    if (silent) {
        instant_.fill(0.0f);
        ceilingDb_ = std::max(ceilingDb_ - kCeilingFallDbPerSecond * std::max(dt, 0.0f), kMinCeilingDb);
        return;
    }
    const int n = frames_;
    std::fill(fftIn_.begin(), fftIn_.end(), 0.0f);
    for (int i = 0; i < n; ++i) {
        const float hann = n > 1 ? 0.5f - 0.5f * std::cos(2.0f * std::numbers::pi_v<float> * static_cast<float>(i) / static_cast<float>(n - 1)) : 1.0f;
        fftIn_[static_cast<size_t>(i)] = mid_[static_cast<size_t>(i)] * hann;
    }
    fft_.compute(fftIn_.data());
    const std::vector<float>& mags = fft_.magnitudes();
    const int lastBin = static_cast<int>(mags.size()) - 1;
    // A full-scale sine under a Hann window of n samples peaks at n / 4: it reads 1, 0 dBFS.
    const float toAmplitude = n > 1 ? 4.0f / static_cast<float>(n) : 1.0f;
    std::array<float, kWidth> db{};
    float loudest = kFloorDb;
    for (size_t i = 0; i < kWidth; ++i) {
        const float lo = edgeBins_[i];
        const float hi = edgeBins_[i + 1];
        const int first = static_cast<int>(std::ceil(lo));
        const int last = std::min(static_cast<int>(std::ceil(hi)) - 1, lastBin);
        float magnitude = 0.0f;
        if (last >= first) {
            // A texel that spans bins reads its loudest.
            for (int k = first; k <= last; ++k) magnitude = std::max(magnitude, mags[static_cast<size_t>(k)]);
        } else {
            // A texel narrower than a bin reads between its neighbours.
            const float at = std::clamp(0.5f * (lo + hi), 0.0f, static_cast<float>(lastBin));
            const int below = std::min(static_cast<int>(at), lastBin - 1);
            const float t = at - static_cast<float>(below);
            magnitude = mags[static_cast<size_t>(below)] * (1.0f - t) + mags[static_cast<size_t>(below + 1)] * t;
        }
        const float amplitude = magnitude * toAmplitude;
        db[i] = (amplitude > 0.0f ? std::max(20.0f * std::log10(amplitude), kFloorDb) : kFloorDb) + tiltDb_[i];
        loudest = std::max(loudest, db[i]);
    }
    // One ceiling for the whole spectrum: up at once to the loudest texel, down slowly, never under the floor.
    ceilingDb_ = std::max({ceilingDb_ - kCeilingFallDbPerSecond * std::max(dt, 0.0f), loudest, kMinCeilingDb});
    const float bottom = ceilingDb_ - kRangeDb;
    for (size_t i = 0; i < kWidth; ++i) instant_[i] = std::clamp((db[i] - bottom) / kRangeDb, 0.0f, 1.0f);
}

void AudioInputs::holdPeaks(float dt, bool silent) {
    // Through silence the peaks stay where they are.
    if (silent) return;
    const float step = std::max(dt, 0.0f);
    for (size_t i = 0; i < kWidth; ++i) {
        const float now = spectrum_[i];
        if (now >= peak_[i]) {
            peak_[i] = now;
            peakHold_[i] = kPeakHoldSeconds;
        } else if (peakHold_[i] > 0.0f) {
            peakHold_[i] = std::max(peakHold_[i] - step, 0.0f);
        } else {
            peak_[i] = std::max(now, peak_[i] - kPeakFallPerSecond * step);
        }
    }
}

void AudioInputs::align() {
    const int n = frames_;
    const int span = std::min(kScopeFrames, n);
    const int offsets = n - span + 1;
    int best = -1;
    double previousEnergy = 0.0;
    if (hasPrevious_ && static_cast<int>(previousSpan_.size()) == span) {
        for (const float v : previousSpan_) previousEnergy += static_cast<double>(v) * v;
    }
    if (previousEnergy > static_cast<double>(span) * kSilenceRms * kSilenceRms) {
        // The span of this window least different from the last one shown: a periodic sound stands still.
        // The squared difference, not the correlation, which a span of a fractional number of periods biases.
        double bestCost = std::numeric_limits<double>::infinity();
        for (int o = 0; o < offsets; ++o) {
            double cost = 0.0;
            for (int k = 0; k < span; ++k) {
                const double d = static_cast<double>(mid_[static_cast<size_t>(o + k)]) - previousSpan_[static_cast<size_t>(k)];
                cost += d * d;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = o;
            }
        }
    }
    if (best < 0) {
        // Nothing to match (the first window, or one after silence): start at a rising zero crossing.
        best = (offsets - 1) / 2;
        for (int o = 1; o < offsets; ++o) {
            if (mid_[static_cast<size_t>(o - 1)] < 0.0f && mid_[static_cast<size_t>(o)] >= 0.0f) {
                best = o;
                break;
            }
        }
    }
    scopeOffset_ = best;
    previousSpan_.assign(mid_.begin() + best, mid_.begin() + best + span);
    hasPrevious_ = true;
    // The span stretched over the row by linear interpolation, the same offset for both channels.
    for (int i = 0; i < kWidth; ++i) {
        const float at = span > 1 ? static_cast<float>(i) * static_cast<float>(span - 1) / static_cast<float>(kWidth - 1) : 0.0f;
        const int below = std::min(static_cast<int>(at), std::max(span - 2, 0));
        const int above = std::min(below + 1, span - 1);
        const float t = at - static_cast<float>(below);
        const size_t a = static_cast<size_t>(best + below);
        const size_t b = static_cast<size_t>(best + above);
        const size_t texel = static_cast<size_t>(i);
        scopeLeft_[texel] = stereo_[2 * a] * (1.0f - t) + stereo_[2 * b] * t;
        scopeRight_[texel] = stereo_[2 * a + 1] * (1.0f - t) + stereo_[2 * b + 1] * t;
        scopeMid_[texel] = mid_[a] * (1.0f - t) + mid_[b] * t;
    }
}

void AudioInputs::fallBack(const GeodeFeatureFrame& features) {
    // No window this frame (an offline Render): the analysis' bands stretched over the spectrum and its
    // waveform as a mono scope, as before.
    for (int i = 0; i < kWidth; ++i) {
        const float at = static_cast<float>(i) * static_cast<float>(GEODE_BAND_COUNT - 1) / static_cast<float>(kWidth - 1);
        const int below = std::min(static_cast<int>(at), GEODE_BAND_COUNT - 2);
        const float t = at - static_cast<float>(below);
        const float band = sane(features.bands[below]) * (1.0f - t) + sane(features.bands[below + 1]) * t;
        instant_[static_cast<size_t>(i)] = std::clamp(band, 0.0f, 1.0f);
        const float wat = static_cast<float>(i) * static_cast<float>(GEODE_WAVEFORM_POINTS - 1) / static_cast<float>(kWidth - 1);
        const int wbelow = std::min(static_cast<int>(wat), GEODE_WAVEFORM_POINTS - 2);
        const float wt = wat - static_cast<float>(wbelow);
        const float wave = sane(features.waveform[wbelow]) * (1.0f - wt) + sane(features.waveform[wbelow + 1]) * wt;
        scopeMid_[static_cast<size_t>(i)] = wave;
        scopeLeft_[static_cast<size_t>(i)] = wave;
        scopeRight_[static_cast<size_t>(i)] = wave;
    }
    hasPrevious_ = false;
}

}  // namespace geode::viz
