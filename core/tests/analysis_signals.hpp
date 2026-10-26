#pragma once
// Synthetic sound for the analysis tests: deterministic noise, click tracks, drums, tones and a bass line,
// and a runner that feeds a signal through ReactiveAnalyzer hop by hop the way AnalysisSession does.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "geode/analysis/ReactiveAnalyzer.hpp"

namespace analysis_signals {

constexpr int kRate = 48000;
constexpr float kHopRate = 60.0f;
constexpr int kHop = 800;  // kRate / kHopRate
constexpr int kFft = 2048;
constexpr int kBands = 64;
constexpr double kPi = 3.14159265358979323846;

// Uniform noise in [-0.5, 0.5), the same sequence for the same seed.
class Lcg {
public:
    explicit Lcg(uint32_t seed) : state_(seed) {}
    float next() {
        state_ = state_ * 1664525u + 1013904223u;
        return static_cast<float>(state_ >> 8) / 16777216.0f - 0.5f;
    }

private:
    uint32_t state_;
};

inline size_t samplesOf(double seconds) {
    return static_cast<size_t>(seconds * kRate);
}

inline std::vector<float> scaledTo(std::vector<float> x, float dbfs) {
    double sum = 0.0;
    for (const float v : x) sum += static_cast<double>(v) * v;
    const double gain = std::pow(10.0, dbfs / 20.0) / std::sqrt(sum / static_cast<double>(x.size()));
    for (auto& v : x) v = static_cast<float>(v * gain);
    return x;
}

inline std::vector<float> uniform(size_t n, uint32_t seed) {
    Lcg lcg(seed);
    std::vector<float> out(n);
    for (auto& v : out) v = lcg.next();
    return out;
}

inline std::vector<float> white(double seconds, float dbfs, uint32_t seed) {
    return scaledTo(uniform(samplesOf(seconds), seed), dbfs);
}

// Pink noise (a three-pole approximation of -3 dB per octave).
inline std::vector<float> pink(double seconds, float dbfs, uint32_t seed) {
    std::vector<float> out = uniform(samplesOf(seconds), seed);
    double b0 = 0.0, b1 = 0.0, b2 = 0.0;
    for (auto& v : out) {
        const double x = v * std::sqrt(12.0);
        b0 = 0.99765 * b0 + x * 0.0990460;
        b1 = 0.96300 * b1 + x * 0.2965164;
        b2 = 0.57000 * b2 + x * 1.0526913;
        v = static_cast<float>(b0 + b1 + b2 + x * 0.1848);
    }
    return scaledTo(out, dbfs);
}

inline std::vector<float> tone(double seconds, double hz, float dbfs) {
    std::vector<float> out(samplesOf(seconds));
    const double amplitude = std::pow(10.0, dbfs / 20.0) * std::sqrt(2.0);
    for (size_t i = 0; i < out.size(); i++) out[i] = static_cast<float>(std::sin(2.0 * kPi * hz * i / kRate) * amplitude);
    return out;
}

inline size_t sampleAt(double seconds) {
    return static_cast<size_t>(std::floor(seconds * kRate + 0.5));
}

// Adds a 1 ms click every beat from `start`, and returns the click times.
inline std::vector<double> addClicks(std::vector<float>& out, double bpm, double start = 0.25) {
    std::vector<double> times;
    for (int k = 0;; k++) {
        const double t = start + k * 60.0 / bpm;
        const size_t at = sampleAt(t);
        if (at >= out.size()) break;
        for (size_t i = at; i < std::min(at + 48, out.size()); i++) out[i] += 0.9f;
        times.push_back(t);
    }
    return times;
}

// A kick: a sine falling from 120 to 45 Hz, decaying over 80 ms.
inline std::vector<float> kick() {
    std::vector<float> out(samplesOf(0.25));
    double phase = 0.0;
    for (size_t i = 0; i < out.size(); i++) {
        const double t = static_cast<double>(i) / kRate;
        phase += 2.0 * kPi * (45.0 + 75.0 * std::exp(-t / 0.06)) / kRate;
        out[i] = static_cast<float>(std::sin(phase) * std::exp(-t / 0.08));
    }
    return out;
}

// A snare: a 200 Hz body and a burst of noise.
inline std::vector<float> snare(uint32_t seed = 7) {
    std::vector<float> out = uniform(samplesOf(0.2), seed);
    for (size_t i = 0; i < out.size(); i++) {
        const double t = static_cast<double>(i) / kRate;
        out[i] = static_cast<float>(0.7 * std::sin(2.0 * kPi * 200.0 * t) * std::exp(-t / 0.05) + out[i] * std::exp(-t / 0.06));
    }
    return out;
}

// A hat: high-passed noise, decaying over 15 ms.
inline std::vector<float> hat(uint32_t seed = 9) {
    const std::vector<float> noise = uniform(samplesOf(0.06) + 1, seed);
    std::vector<float> out(samplesOf(0.06));
    for (size_t i = 0; i < out.size(); i++) {
        const double t = static_cast<double>(i) / kRate;
        out[i] = static_cast<float>(0.6 * (noise[i + 1] - noise[i]) * std::exp(-t / 0.015));
    }
    return out;
}

inline void place(std::vector<float>& out, const std::vector<float>& sound, const std::vector<double>& times, float gain = 1.0f) {
    for (const double t : times) {
        const size_t at = sampleAt(t);
        for (size_t i = 0; i < sound.size() && at + i < out.size(); i++) out[at + i] += gain * sound[i];
    }
}

// A held bass line: each note runs into the next with a 20 ms glide; four harmonics.
inline std::vector<float> legatoBass(double seconds, const std::vector<double>& notes, double noteSeconds, double start, double amplitude) {
    std::vector<float> out(samplesOf(seconds));
    const double glide = 1.0 - std::exp(-1.0 / (0.02 * kRate));
    double hz = notes[0];
    double phase = 0.0;
    for (size_t i = 0; i < out.size(); i++) {
        const double t = static_cast<double>(i) / kRate;
        const auto note = static_cast<size_t>(std::max(t - start, 0.0) / noteSeconds);
        hz += (notes[note % notes.size()] - hz) * glide;
        phase += 2.0 * kPi * hz / kRate;
        const double sound = std::sin(phase) + std::sin(2.0 * phase) / 2.0 + std::sin(3.0 * phase) / 3.0 + std::sin(4.0 * phase) / 4.0;
        out[i] = static_cast<float>(amplitude * std::min(t / 0.05, 1.0) * sound);
    }
    return out;
}

inline std::vector<float> sum(std::vector<float> a, const std::vector<float>& b) {
    for (size_t i = 0; i < a.size() && i < b.size(); i++) a[i] += b[i];
    return a;
}

inline std::vector<double> every(double start, double step, double end) {
    std::vector<double> times;
    for (int k = 0; start + k * step < end; k++) times.push_back(start + k * step);
    return times;
}

// What one hop of analysis reported, at the time its window ends.
struct Reading {
    double seconds;
    float rms;
    float bass;
    float mid;
    float treble;
    float loudestBand;
    float flux;
    bool beat;
    float kick;
    float snare;
    float hat;
    float bpm;
    float confidence;
    float beatPhase;
    bool drop;
    bool arrival;
};

// Analyses `pcm` hop by hop (48 kHz, 60 hops a second, the app's window and bands).
inline std::vector<Reading> analyse(const std::vector<float>& pcm, float refractoryMs = 60.0f) {
    geode::analysis::ReactiveAnalyzer analyzer(kBands, kFft, kRate, kHopRate);
    analyzer.setRefractoryMs(refractoryMs);
    const float dt = static_cast<float>(kHop) / kRate;
    std::vector<Reading> readings;
    for (size_t at = 0; at + kFft <= pcm.size(); at += kHop) {
        analyzer.analyze(pcm.data() + at, dt);
        const auto& bands = analyzer.bands();
        readings.push_back({static_cast<double>(at + kFft) / kRate, analyzer.rms(), analyzer.bass(), analyzer.mid(),
                            analyzer.treble(), *std::max_element(bands.begin(), bands.end()), analyzer.fluxValue(),
                            analyzer.beat(), analyzer.kick(), analyzer.snare(), analyzer.hat(), analyzer.bpm(),
                            analyzer.pulseConfidence(), analyzer.beatPhase(), analyzer.drop(), analyzer.arrival()});
    }
    return readings;
}

// Whether `t` falls within [from, from + window] of one of `times`.
inline bool within(double t, const std::vector<double>& times, double window = 0.1, double early = 0.0) {
    for (const double at : times) {
        if (t >= at - early && t <= at + window) return true;
    }
    return false;
}

}  // namespace analysis_signals
