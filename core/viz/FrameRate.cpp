#include "geode/viz/FrameRate.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::framerate {

namespace {
bool usable(float dt) { return std::isfinite(dt) && dt > 0.0f; }
}  // namespace

float referenceFrames(float dt) { return usable(dt) ? dt * kReferenceFps : 0.0f; }

float perFrameFactor(float perFrame, float dt) {
    if (!(perFrame > 0.0f)) return 0.0f;
    return std::pow(perFrame, referenceFrames(dt));
}

float advancePhase(float phase, float rate, float dt, float period) {
    const float advanced = usable(dt) && std::isfinite(rate) ? phase + rate * dt : phase;
    if (!(period > 0.0f)) return advanced;
    const float wrapped = std::fmod(advanced, period);
    return wrapped < 0.0f ? wrapped + period : wrapped;
}

FixedStepClock::FixedStepClock(float stepsPerSecond, int maxStepsPerFrame)
    : stepsPerSecond_(std::max(stepsPerSecond, 0.0f)), maxStepsPerFrame_(std::max(maxStepsPerFrame, 1)) {}

int FixedStepClock::advance(float dt) {
    if (!usable(dt)) return 0;
    const float earned = pending_ + dt * stepsPerSecond_;
    const float whole = std::floor(earned);
    const int steps = static_cast<int>(std::min(whole, static_cast<float>(maxStepsPerFrame_)));
    // What a capped frame could not run is dropped; only the fraction of the next step carries over.
    pending_ = earned - whole;
    return steps;
}

}  // namespace geode::viz::framerate
