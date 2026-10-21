#pragma once

namespace geode::viz {

// The beat pulse every Style reads (uBeat and its scene-side copies): it follows the heard hit,
// but it RISES over an attack time instead of jumping, then falls linearly. Before the No-flash
// rule it took the hit's value in the frame the hit arrived, so a Style keying brightness on it
// (julia, plasma, aurora, Acid's overdrive, ...) stepped the whole frame in one frame.
class BeatEnvelope {
public:
    // A 0 -> 1 rise takes this long; larger peaks rise at the same rate.
    static constexpr float kAttackSeconds = 0.1f;
    static constexpr float kRisePerSecond = 1.0f / kAttackSeconds;
    // The fall the instant pulse always had: 3 per second.
    static constexpr float kFallPerSecond = 3.0f;

    // Feeds this frame's impulse (already scaled and clamped by the caller); returns the pulse.
    float step(float impulse, float dt);
    float value() const { return value_; }
    void reset();

private:
    float peak_ = 0.0f;
    float value_ = 0.0f;
};

}  // namespace geode::viz
