#include "geode/viz/StyleTransition.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::transition {

namespace {

// splitmix64's finaliser: every bit of the seed and the index reaches every bit of the draw.
uint64_t mix(uint64_t x) {
    x += 0x9E3779B97F4A7C15ULL;
    x = (x ^ (x >> 30U)) * 0xBF58476D1CE4E5B9ULL;
    x = (x ^ (x >> 27U)) * 0x94D049BB133111EBULL;
    return x ^ (x >> 31U);
}

}  // namespace

int slotOf(const std::string& id) {
    for (int i = 0; i < kLookCount; ++i) {
        if (id == kSafeLooks[static_cast<size_t>(i)]) return i;
    }
    return -1;
}

bool isSafe(const std::string& id) { return id == kRandom || slotOf(id) >= 0; }

std::string safeLook(const std::string& requested) { return isSafe(requested) ? requested : kSafeLooks[kFadeSlot]; }

int lookSlot(const std::string& requested, uint64_t seed, uint32_t index, int previous) {
    if (requested != kRandom) {
        const int slot = slotOf(requested);
        return slot >= 0 ? slot : kFadeSlot;
    }
    const uint64_t draw = mix(seed ^ mix(index));
    if (previous < 0 || previous >= kLookCount) return static_cast<int>(draw % static_cast<uint64_t>(kLookCount));
    // One of the other looks: a step of 1 to kLookCount - 1 on from the last.
    const auto step = static_cast<int>(draw % static_cast<uint64_t>(kLookCount - 1)) + 1;
    return (previous + step) % kLookCount;
}

void DownbeatGate::arm() {
    if (waiting_) return;
    waiting_ = true;
    waited_ = 0.0f;
}

bool DownbeatGate::tempoHeard(const GeodeFeatureFrame& frame) {
    return frame.bpm > 0.0f && std::isfinite(frame.bpm) && frame.pulseConfidence >= kTempoHeardConfidence;
}

float DownbeatGate::maxWait(float bpm) {
    if (!(bpm > 0.0f) || !std::isfinite(bpm)) return 0.0f;
    return std::min((kBeatsPerBar + 1.0f) * 60.0f / bpm, kMaxWaitSeconds);
}

bool DownbeatGate::step(const GeodeFeatureFrame& frame, float dt) {
    if (!waiting_) return false;
    waited_ += std::max(dt, 0.0f);
    const bool starts = !tempoHeard(frame) || frame.downbeat > 0.0f || waited_ >= maxWait(frame.bpm);
    if (starts) waiting_ = false;
    return starts;
}

void Progress::start(float seconds) {
    seconds_ = std::isfinite(seconds) ? std::max(seconds, kMinSeconds) : kMinSeconds;
    clock_ = 0.0f;
    lead_ = 0.0f;
    shown_ = 0.0f;
}

float Progress::step(float dt, float kick) {
    const float span = std::max(dt, 0.0f);
    clock_ = std::min(clock_ + span / seconds_, 1.0f);
    if (kick > 0.0f) lead_ = std::min({std::max(lead_, clock_) + kKickStep, clock_ + kMaxLead, 1.0f});
    const float target = std::max(clock_, lead_);
    // Fast enough to glide a kick's step in kGlideSeconds and to keep up with the clock of a short change.
    const float rate = std::max(kKickStep / kGlideSeconds, 2.0f / seconds_);
    shown_ = std::min(target, shown_ + rate * span);
    return shown_;
}

bool LookHold::offer(uint32_t style, uint32_t drawn) {
    holding_ = style != 0U && drawn != 0U && style != drawn;
    style_ = style;
    unasked_ = 0.0f;
    held_ = 0.0f;
    return !holding_;
}

bool LookHold::step(uint32_t drawn, uint32_t requested, float dt) {
    if (!holding_) return false;
    const float span = std::max(dt, 0.0f);
    unasked_ = requested == style_ ? 0.0f : unasked_ + span;
    held_ += span;
    if (drawn == style_ || unasked_ >= kRequestGraceSeconds || held_ >= kMaxHoldSeconds) holding_ = false;
    return !holding_;
}

float ease(float progress) {
    const float x = std::clamp(progress, 0.0f, 1.0f);
    return x * x * x * (x * (x * 6.0f - 15.0f) + 10.0f);
}

}  // namespace geode::viz::transition
