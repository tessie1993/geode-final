#pragma once
#include <array>
#include <cstddef>
#include <cstdint>

namespace geode::viz::mod {

// The Sound signals a Reaction can listen to, in SoundSignal's order in :feature:customization:api.
enum class SoundSignal : uint8_t {
    Level, Bass, Mid, Treble, Brightness, Hit, Kick, MidHit, Hat, Beat, Energy, BuildUp, Surge, Change, StereoWidth, StereoPan,
};
constexpr size_t kSoundSignalCount = 16;

// What one frame heard: every Sound signal already normalised to 0..1, the beat's length once the
// tempo is known (0 before), and whether the music plays.
struct SignalFrame {
    std::array<float, kSoundSignalCount> level{};
    float beatSeconds = 0.0f;
    bool playing = true;

    float operator[](SoundSignal s) const { return level[static_cast<size_t>(s)]; }
};

// The per-frame shaping of a Reaction (CONTEXT.md): the normaliser that brings a Sound signal to
// 0..1, the curve, the rise and fall, and the push onto the target within the Reaction's range.
// Every value here is a fraction 0..1 of the target's own range, so one shape works on any target.
//
// The meaning is owned by the Kotlin reducer (ADR-0002): these functions give the numbers
// feature/customization/core's Evaluation gives, and reactions_test.cpp holds them to it.

// The normaliser's limits, as SignalTrim in :feature:customization:api.
constexpr float kMinGain = 0.0f;
constexpr float kMaxGain = 4.0f;
constexpr float kMaxFloor = 0.9f;

// The shortest rise a Reaction on a luminous property may have (NO_FLASH_MIN_RISE, 150 ms). The
// engine applies it whatever it is handed: the safety clamp is unconditional.
constexpr float kNoFlashMinRiseSeconds = 0.15f;

struct SignalTrim {
    float gain = 1.0f;
    float floor = 0.0f;
};

enum class Curve : uint8_t { Linear, Exponential, Logarithmic, SCurve };

struct ReactionShape {
    float amount = 1.0f;
    bool invert = false;
    float low = 0.0f;
    float high = 1.0f;
    Curve curve = Curve::Linear;
};

// `value` kept to 0..1; not-a-number reads as 0.
float unit(float value);
// `raw` scaled by the trim's gain, below its floor 0, the rest stretched back over 0..1.
float normalise(float raw, SignalTrim trim);
// `input` (clamped to 0..1) bent by `c`.
float curve(float input, Curve c);
// Where the Reaction puts its target whose value is `base` when its (risen and fallen) input is
// `input`: the curved input times the amount, added (taken away when inverted), kept in low..high.
float react(float base, float input, const ReactionShape& shape);

// Where a Modulator linked to a Macro puts the Macro whose hand-set value is `base`
// (Evaluation.drift): around that value, not only above it. The curved input's distance from the
// middle, times the amount, is added (taken away when inverted), kept in low..high. This is how a
// Macro drifts by itself; there is no separate autopilot (#41).
float drift(float base, float input, const ReactionShape& shape);
// The middle a drift swings around: MACRO_REST.
constexpr float kMacroRestFraction = 0.5f;

// Rise and fall: follows its input at a bounded speed. `riseSeconds` is the time a full 0 -> 1 swing
// takes upwards and `fallSeconds` downwards; 0 follows at once. Linear on purpose: it puts a hard
// bound on how far one frame can move a target, which the No-flash rule relies on.
class Follower {
public:
    float step(float input, float riseSeconds, float fallSeconds, float dt);
    float value() const { return value_; }
    void reset(float value = 0.0f) { value_ = unit(value); }

private:
    float value_ = 0.0f;
};

}  // namespace geode::viz::mod
