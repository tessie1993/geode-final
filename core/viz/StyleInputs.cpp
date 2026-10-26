#include "geode/viz/StyleInputs.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::inputs {

namespace {

// A full-avalanche 32-bit mix (two multiply-xorshift rounds).
std::uint32_t mix(std::uint32_t x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

constexpr std::uint32_t kGolden = 0x9e3779b9u;
constexpr int kChannels = 4;
constexpr int kByteShift = 24;
constexpr float kUnitScale = 1.0f / 16777216.0f;
constexpr int kUnitShift = 8;
constexpr std::uint32_t kFnvOffset = 2166136261u;
constexpr std::uint32_t kFnvPrime = 16777619u;

float byteOf(std::uint32_t hash) { return static_cast<float>(hash >> kByteShift); }

float smooth(float t) { return t * t * (3.0f - 2.0f * t); }

// Shortest signed step from `from` to `to` on the unit circle, in [-0.5, 0.5).
float wrappedDelta(float from, float to) {
    float d = std::fmod(to - from + 0.5f, 1.0f);
    if (d < 0.0f) d += 1.0f;
    return d - 0.5f;
}

}  // namespace

std::optional<Input> named(std::string_view name) {
    for (int i = 0; i < kInputCount; ++i) {
        if (kInputNames[static_cast<size_t>(i)] == name) return static_cast<Input>(i);
    }
    return std::nullopt;
}

std::uint32_t hashCell(std::uint32_t seed, int channel, int x, int y, int z) {
    std::uint32_t h = mix(seed + kGolden);
    h = mix(h ^ static_cast<std::uint32_t>(channel));
    h = mix(h ^ static_cast<std::uint32_t>(x));
    h = mix(h ^ static_cast<std::uint32_t>(y));
    return mix(h ^ static_cast<std::uint32_t>(z));
}

int lattice(Input input) {
    switch (input) {
        case Input::NoiseLq: return 1;
        case Input::NoiseMq: return kMqLattice;
        case Input::NoiseHq: return kHqLattice;
        case Input::NoiseVolume: return 1;
        default: return 0;
    }
}

std::vector<std::uint8_t> noisePlane(std::uint32_t seed, int size, int lattice) {
    if (size <= 0 || lattice <= 0 || size % lattice != 0) return {};
    const int cells = size / lattice;
    std::vector<std::uint8_t> out(static_cast<size_t>(size) * static_cast<size_t>(size) * kChannels);
    for (int y = 0; y < size; ++y) {
        const int y0 = y / lattice;
        const int y1 = (y0 + 1) % cells;
        const float sy = smooth(static_cast<float>(y % lattice) / static_cast<float>(lattice));
        for (int x = 0; x < size; ++x) {
            const int x0 = x / lattice;
            const int x1 = (x0 + 1) % cells;
            const float sx = smooth(static_cast<float>(x % lattice) / static_cast<float>(lattice));
            const size_t at = (static_cast<size_t>(y) * static_cast<size_t>(size) + static_cast<size_t>(x)) * kChannels;
            for (int c = 0; c < kChannels; ++c) {
                const float v00 = byteOf(hashCell(seed, c, x0, y0, 0));
                const float v10 = byteOf(hashCell(seed, c, x1, y0, 0));
                const float v01 = byteOf(hashCell(seed, c, x0, y1, 0));
                const float v11 = byteOf(hashCell(seed, c, x1, y1, 0));
                const float bottom = v00 + (v10 - v00) * sx;
                const float top = v01 + (v11 - v01) * sx;
                out[at + static_cast<size_t>(c)] = static_cast<std::uint8_t>(std::lround(bottom + (top - bottom) * sy));
            }
        }
    }
    return out;
}

std::vector<std::uint8_t> noiseVolume(std::uint32_t seed, int size) {
    if (size <= 0) return {};
    const size_t side = static_cast<size_t>(size);
    std::vector<std::uint8_t> out(side * side * side * kChannels);
    for (int z = 0; z < size; ++z) {
        for (int y = 0; y < size; ++y) {
            for (int x = 0; x < size; ++x) {
                const size_t at = ((static_cast<size_t>(z) * side + static_cast<size_t>(y)) * side + static_cast<size_t>(x)) * kChannels;
                for (int c = 0; c < kChannels; ++c) {
                    out[at + static_cast<size_t>(c)] = static_cast<std::uint8_t>(hashCell(seed, c, x, y, z) >> kByteShift);
                }
            }
        }
    }
    return out;
}

bool needsHistory(Set set) { return has(set, Input::PreviousFrame) || blurChainLength(set) > 0; }

int blurChainLength(Set set) {
    if (has(set, Input::Blur3)) return 4;
    if (has(set, Input::Blur2)) return 3;
    if (has(set, Input::Blur1)) return 2;
    return 0;
}

int blurSource(int level, int built) {
    if (level < 1 || level > kBlurLevels || built <= 0) return -1;
    return std::min(level, built - 1);
}

std::uint32_t visitSeed(std::uint32_t base, std::string_view styleId) {
    std::uint32_t name = kFnvOffset;
    for (const char ch : styleId) name = (name ^ static_cast<std::uint8_t>(ch)) * kFnvPrime;
    return hashCell(base, 0, static_cast<int>(name), 0, 0);
}

std::array<float, 4> visitRandom(std::uint32_t seed, std::uint32_t visit) {
    std::array<float, 4> out{};
    for (int c = 0; c < kChannels; ++c) {
        out[static_cast<size_t>(c)] = static_cast<float>(hashCell(seed, c, static_cast<int>(visit), 0, 0) >> kUnitShift) * kUnitScale;
    }
    return out;
}

void VisitClock::beginVisit(std::uint32_t seed) {
    ++visits_;
    frame_ = 0;
    dt_ = 0.0f;
    age_ = 0.0f;
    random_ = visitRandom(seed, visits_);
}

void VisitClock::step(float dt) {
    dt_ = std::isfinite(dt) && dt > 0.0f ? dt : 0.0f;
    frame_ = (frame_ + 1u) % kFrameWrap;
    age_ = std::min(age_ + dt_, kStyleAgeMax);
}

void BarClock::step(float barPhase, float confidence, float bpm, float dt) {
    if (!std::isfinite(dt) || dt <= 0.0f) return;
    if (std::isfinite(bpm) && bpm > 0.0f) rate_ = std::clamp(bpm / 60.0f / kBeatsPerBar, kMinBarsPerSecond, kMaxBarsPerSecond);
    const float steady = rate_ * dt;
    const bool locked = std::isfinite(confidence) && std::isfinite(barPhase) && confidence >= kLockConfidence;
    float advance = steady;
    if (locked) advance += wrappedDelta(phase_, std::clamp(barPhase, 0.0f, 1.0f)) * (1.0f - std::exp(-dt * kLockHz));
    advance = std::clamp(advance, steady * kMinPace, steady * kMaxPace);
    phase_ = std::fmod(phase_ + advance, 1.0f);
    const float target = locked ? std::min(confidence, 1.0f) : 0.0f;
    confidence_ += (target - confidence_) * (1.0f - std::exp(-dt * kConfidenceHz));
}

void BarClock::reset() {
    phase_ = 0.0f;
    confidence_ = 0.0f;
    rate_ = kDefaultBarsPerSecond;
}

}  // namespace geode::viz::inputs
