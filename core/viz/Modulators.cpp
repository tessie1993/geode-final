#include "geode/viz/Modulators.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::mod {

namespace {
constexpr float kTau = 6.2831853f;
constexpr float kHalf = 0.5f;
// 24 bits of the generator, as a fraction 0..1.
constexpr int kRandomShift = 8;
constexpr float kRandomScale = 1.0f / 16777216.0f;

float own(float driven, float fallback) { return driven >= 0.0f ? driven : fallback; }

float rateOf(float rate) { return std::isnan(rate) ? kDefaultRate : std::clamp(rate, kMinRate, kMaxRate); }

// A period, step or cycle length the engine can run: not a number is the default, and nothing
// is shorter than the shortest period.
float lengthOf(float seconds) { return std::isnan(seconds) ? kDefaultPeriodSeconds : std::max(seconds, kMinPeriodSeconds); }

float beatOr(const ModulatorSpec& spec, const SignalFrame& frame, float seconds) {
    return spec.beatSync && frame.beatSeconds > 0.0f ? lengthOf(frame.beatSeconds) : lengthOf(seconds);
}

// How far one envelope stage moves in `dt`: a full swing over `seconds`, or all of it at once.
float stageStep(float seconds, float advance) { return seconds > 0.0f ? advance / seconds : 1.0f; }

float smoothstep(float x) { return x * x * (3.0f - 2.0f * x); }

float fract(float x) { return x - std::floor(x); }
}  // namespace

float Modulator::random() {
    rng_ ^= rng_ << 13;
    rng_ ^= rng_ >> 17;
    rng_ ^= rng_ << 5;
    return static_cast<float>(rng_ >> kRandomShift) * kRandomScale;
}

void Modulator::reset() { *this = Modulator{}; }

void Modulator::restart() {
    driftFrom_ = driftFrom_ + (driftTo_ - driftFrom_) * smoothstep(phase_);
    phase_ = 0.0f;
    releasing_ = false;
    beats_ = 0;
}

float Modulator::tick(const ModulatorSpec& spec, const ModulatorDrive& drive, const SignalFrame& frame, float dt) {
    if (!seeded_) {
        seeded_ = true;
        rng_ = spec.seed != 0 ? spec.seed : 1u;
        held_ = random();
        driftFrom_ = 0.0f;
        driftTo_ = random();
    }
    const bool active = spec.start == StartMode::ByHand ? spec.playing : frame.playing;
    if (!active) {
        if (spec.start == StartMode::ByHand) reset();
        return value_;
    }
    if (spec.start == StartMode::OnSound) {
        const bool hot = frame[spec.startSignal] >= kStartThreshold;
        if (hot && startArmed_) restart();
        startArmed_ = !hot;
    }
    const float step = std::isfinite(dt) && dt > 0.0f ? dt : 0.0f;
    const float rate = rateOf(own(drive.rate, spec.rate));
    const float depth = unit(own(drive.depth, spec.depth));
    const float pattern = unit(own(drive.pattern, 0.0f));
    const float advance = step * rate;
    float shape = 0.0f;
    switch (spec.kind) {
        case ModulatorKind::Lfo: shape = lfo(spec, advance / beatOr(spec, frame, spec.periodSeconds), pattern); break;
        case ModulatorKind::HitEnvelope: shape = envelope(spec, frame, rate, step); break;
        case ModulatorKind::StepSequencer: {
            const int count = std::clamp(spec.stepCount, 0, kMaxSteps);
            if (count > 0) shape = sequence(spec, advance / (beatOr(spec, frame, spec.stepSeconds) * static_cast<float>(count)), pattern);
            break;
        }
        case ModulatorKind::RandomDrift: shape = drift(advance / lengthOf(spec.periodSeconds)); break;
        case ModulatorKind::ToggleOnBeat:
        case ModulatorKind::RandomOnBeat: shape = onBeat(spec, frame, rate, pattern, step); break;
    }
    value_ = unit(depth * shape);
    return value_;
}

float Modulator::lfo(const ModulatorSpec& spec, float cycles, float pattern) {
    phase_ += cycles;
    if (phase_ >= 1.0f) {
        phase_ = fract(phase_);
        held_ = random();
    }
    const float p = fract(phase_ + pattern);
    switch (spec.wave) {
        case Wave::Sine: return kHalf - kHalf * std::cos(kTau * p);
        case Wave::Triangle: return 1.0f - std::fabs(2.0f * p - 1.0f);
        case Wave::Saw: return p;
        case Wave::Square: return p < kHalf ? 0.0f : 1.0f;
        case Wave::Random: return held_;
    }
    return 0.0f;
}

float Modulator::envelope(const ModulatorSpec& spec, const SignalFrame& frame, float rate, float dt) {
    const bool hot = frame[spec.signal] >= unit(spec.threshold);
    if (hot && armed_) releasing_ = false;
    armed_ = !hot;
    const float advance = dt * rate;
    if (!releasing_) {
        envelope_ += stageStep(spec.attackSeconds, advance);
        if (envelope_ >= 1.0f) {
            envelope_ = 1.0f;
            releasing_ = true;
        }
    } else {
        envelope_ = std::max(envelope_ - stageStep(spec.releaseSeconds, advance), 0.0f);
    }
    return envelope_;
}

float Modulator::sequence(const ModulatorSpec& spec, float cycles, float pattern) {
    const int count = std::clamp(spec.stepCount, 1, kMaxSteps);
    phase_ = fract(phase_ + cycles);
    const int shift = static_cast<int>(std::lround(pattern * static_cast<float>(count)));
    const int index = (static_cast<int>(std::floor(phase_ * static_cast<float>(count))) + shift) % count;
    return unit(spec.steps[static_cast<size_t>(std::clamp(index, 0, count - 1))]);
}

float Modulator::drift(float segments) {
    phase_ += segments;
    if (phase_ >= 1.0f) {
        phase_ = fract(phase_);
        driftFrom_ = driftTo_;
        driftTo_ = random();
    }
    return driftFrom_ + (driftTo_ - driftFrom_) * smoothstep(phase_);
}

float Modulator::onBeat(const ModulatorSpec& spec, const SignalFrame& frame, float rate, float pattern, float dt) {
    const bool hot = frame[SoundSignal::Beat] > kBeatHeard;
    if (hot && armed_) {
        const int every = std::clamp(spec.stepCount, 1, kMaxSteps);
        // The pattern moves which beat of the `every` brings the change.
        const int shift = static_cast<int>(std::lround(pattern * static_cast<float>(every)));
        beats_ = (beats_ + 1) % every;
        if ((beats_ + shift) % every == 0) {
            beatTarget_ = spec.kind == ModulatorKind::ToggleOnBeat ? 1.0f - beatTarget_ : random();
        }
    }
    armed_ = !hot;
    // Glides, never steps: a full swing takes the glide, scaled by the rate.
    const float most = stageStep(spec.attackSeconds, dt * rate);
    envelope_ += std::clamp(beatTarget_ - envelope_, -most, most);
    return envelope_;
}

}  // namespace geode::viz::mod
