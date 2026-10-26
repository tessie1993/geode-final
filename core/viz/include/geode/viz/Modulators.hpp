#pragma once
#include <array>
#include <cstdint>

#include "geode/viz/Reactions.hpp"

namespace geode::viz::mod {

// A Modulator (CONTEXT.md): a generated value 0..1 that moves targets the way a Sound signal does.
// The kinds and their start settings are the ones decided on #11 (and the two beat Modulators of #319)
// and modelled in :feature:customization:api (Modulators.kt). Whether one runs at all (it does not
// until a Reaction listens to it) is the ReactionGraph's call; this class is one Modulator's clock and
// shape.
enum class ModulatorKind : uint8_t { Lfo, HitEnvelope, StepSequencer, RandomDrift, ToggleOnBeat, RandomOnBeat };
enum class Wave : uint8_t { Sine, Triangle, Saw, Square, Random };
enum class StartMode : uint8_t { WhilePlaying, OnSound, ByHand };

constexpr float kMinRate = 0.25f;
constexpr float kDefaultRate = 1.0f;
constexpr float kMaxRate = 4.0f;
constexpr int kMaxSteps = 16;
// The shortest period, step or envelope stage the engine runs, whatever it is handed.
constexpr float kMinPeriodSeconds = 0.05f;
constexpr float kDefaultPeriodSeconds = 2.0f;
// An OnSound start fires when its signal rises through this.
constexpr float kStartThreshold = 0.5f;
// A beat Modulator counts a beat each time the Beat signal rises above this: any beat heard at all.
constexpr float kBeatHeard = 0.01f;

struct ModulatorSpec {
    ModulatorKind kind = ModulatorKind::Lfo;
    // Lfo
    Wave wave = Wave::Sine;
    // Lfo: one cycle. RandomDrift: about how long one wander takes.
    float periodSeconds = kDefaultPeriodSeconds;
    // Lfo and StepSequencer: one cycle (or one step) per beat, once the tempo is known.
    bool beatSync = false;
    // HitEnvelope
    SoundSignal signal = SoundSignal::Hit;
    float attackSeconds = 0.01f;
    float releaseSeconds = 0.3f;
    float threshold = 0.5f;
    // StepSequencer
    std::array<float, kMaxSteps> steps{};
    int stepCount = 0;
    float stepSeconds = 0.25f;
    // ToggleOnBeat and RandomOnBeat (#319) use two of the fields above, so the plan keeps its size:
    // `stepCount` is how many heard beats pass between changes (1 when below 1), and `attackSeconds` the
    // glide to each new value, which a full 0 -> 1 swing takes (0 jumps). The toggle flips between 0
    // and 1; the random one moves to a new value drawn from `seed`, so a Render repeats. Neither assumes
    // a tempo: they count the beats the analysis reports.

    StartMode start = StartMode::WhilePlaying;
    SoundSignal startSignal = SoundSignal::Beat;
    // The play and stop of a ByHand Modulator.
    bool playing = false;
    float rate = kDefaultRate;
    float depth = 1.0f;
    // Seeds the Random wave and the drift, so a Render gives the same frames every time.
    uint32_t seed = 1;
};

// This frame's rate, depth and pattern after what other Reactions push onto them (loops included).
// A value below 0, or not a number, means the Modulator's own setting (pattern: none).
struct ModulatorDrive {
    static constexpr float kOwn = -1.0f;
    float rate = kOwn;
    float depth = kOwn;
    float pattern = kOwn;
};

class Modulator {
public:
    // Advances by `dt` and returns the value 0..1.
    float tick(const ModulatorSpec& spec, const ModulatorDrive& drive, const SignalFrame& frame, float dt);
    float value() const { return value_; }
    void reset();

private:
    float lfo(const ModulatorSpec& spec, float cycles, float pattern);
    float envelope(const ModulatorSpec& spec, const SignalFrame& frame, float rate, float dt);
    float sequence(const ModulatorSpec& spec, float cycles, float pattern);
    float drift(float segments);
    float onBeat(const ModulatorSpec& spec, const SignalFrame& frame, float rate, float pattern, float dt);
    float random();
    void restart();

    float phase_ = 0.0f;
    float value_ = 0.0f;
    float held_ = 0.0f;
    float envelope_ = 0.0f;
    bool releasing_ = true;
    bool armed_ = true;
    bool startArmed_ = true;
    float driftFrom_ = 0.0f;
    float driftTo_ = 0.0f;
    // The beat Modulators: beats counted since the last change and the value they glide to.
    int beats_ = 0;
    float beatTarget_ = 0.0f;
    uint32_t rng_ = 0;
    bool seeded_ = false;
};

}  // namespace geode::viz::mod
