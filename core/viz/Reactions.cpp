#include "geode/viz/Reactions.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::mod {

namespace {
constexpr float kSCurveA = 3.0f;
constexpr float kSCurveB = 2.0f;

float gainOf(float gain) { return std::isnan(gain) ? 1.0f : std::clamp(gain, kMinGain, kMaxGain); }
float floorOf(float floor) { return std::isnan(floor) ? 0.0f : std::clamp(floor, 0.0f, kMaxFloor); }

// How far one frame may move toward the input: a full swing over `seconds`, or all the way at 0.
float stepLimit(float seconds, float dt) { return seconds > 0.0f ? dt / seconds : 1.0f; }
}  // namespace

float unit(float value) { return std::isnan(value) ? 0.0f : std::clamp(value, 0.0f, 1.0f); }

float normalise(float raw, SignalTrim trim) {
    const float floor = floorOf(trim.floor);
    const float scaled = raw * gainOf(trim.gain);
    if (std::isnan(scaled) || scaled <= floor) return 0.0f;
    return std::clamp((scaled - floor) / (1.0f - floor), 0.0f, 1.0f);
}

float curve(float input, Curve c) {
    const float x = unit(input);
    switch (c) {
        case Curve::Linear: return x;
        case Curve::Exponential: return x * x;
        case Curve::Logarithmic: return std::sqrt(x);
        case Curve::SCurve: return x * x * (kSCurveA - kSCurveB * x);
    }
    return x;
}

float react(float base, float input, const ReactionShape& shape) {
    const float low = std::min(unit(shape.low), unit(shape.high));
    const float high = std::max(unit(shape.low), unit(shape.high));
    const float push = curve(input, shape.curve) * unit(shape.amount);
    const float moved = shape.invert ? base - push : base + push;
    return std::isnan(moved) ? low : std::clamp(moved, low, high);
}

float Follower::step(float input, float riseSeconds, float fallSeconds, float dt) {
    if (std::isnan(input) || !(dt > 0.0f)) return value_;
    const float target = std::clamp(input, 0.0f, 1.0f);
    if (target > value_) {
        value_ = std::min(target, value_ + stepLimit(riseSeconds, dt));
    } else {
        value_ = std::max(target, value_ - stepLimit(fallSeconds, dt));
    }
    return value_;
}

}  // namespace geode::viz::mod
