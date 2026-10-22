#pragma once
#include <array>
#include <cstddef>
#include <cstdint>

#include "geode/viz/Modulators.hpp"
#include "geode/viz/Reactions.hpp"
#include "geode/viz/SharedCore.hpp"

namespace geode::viz::mod {

// The per-frame evaluation of a Customization's Reactions and Modulators (ADR-0002: the Kotlin
// reducer owns the Customization, C++ evaluates its Reactions every frame).
//
// Each frame, in this order:
//   1. every Sound signal is normalised by its trim;
//   2. each Modulator some Reaction listens to runs, with the rate, depth and pattern the Reactions
//      on it gave last frame; one nobody listens to does not run;
//   3. the targets move, in this order: the Automations set or wander their targets' own values;
//      the Reactions on a Macro move its value; every Macro link moves its property from the Macro's
//      value (Evaluation.macro); then the other Reactions, in their order, each risen and fallen,
//      push their targets: a Shared core property, an Extra, or a Modulator's rate, depth or pattern;
//   4. the safety clamp runs last and unconditionally: every value finite and inside its range,
//      and a luminous property never rises faster than the No-flash minimum (a full swing over
//      kNoFlashMinRiseSeconds), whatever moved it. The No-flash limiter then bounds the frame.
//
// Loops are allowed (#11): a Modulator may move another, or itself, through its rate, depth or
// pattern. A Modulator's own fields take hold one frame later, so a loop is a feedback with one
// frame of delay rather than a recursion, and the clamp keeps every turn of it in range.
//
// Every array is fixed-size, so a plan crosses the native bridge as one snapshot field (PlanCodec).

constexpr int kMaxReactions = 128;
constexpr int kMaxModulators = 16;
constexpr int kMaxMacroLinks = 64;
constexpr int kMaxAutomations = 16;
// The beat the Automations count in before the tempo is known (120 bpm).
constexpr float kUnknownBeatSeconds = 0.5f;
// The longest frame the graph steps over: a stall does not jump every Modulator ahead at once.
constexpr float kMaxFrameSeconds = 0.1f;

enum class ModulatorField : uint8_t { Rate, Depth, Pattern };
constexpr size_t kModulatorFieldCount = 3;

struct Source {
    enum class Kind : uint8_t { Sound, Modulator };
    Kind kind = Kind::Sound;
    uint8_t index = 0;

    static Source sound(SoundSignal s) { return {Kind::Sound, static_cast<uint8_t>(s)}; }
    static Source modulator(int m) { return {Kind::Modulator, static_cast<uint8_t>(m)}; }
};

struct Target {
    enum class Kind : uint8_t { None, Shared, Extra, Macro, Modulator };
    Kind kind = Kind::None;
    uint8_t index = 0;
    ModulatorField field = ModulatorField::Rate;

    static Target shared(Shared p) { return {Kind::Shared, static_cast<uint8_t>(p), ModulatorField::Rate}; }
    static Target extra(int i) { return {Kind::Extra, static_cast<uint8_t>(i), ModulatorField::Rate}; }
    static Target macro(int i) { return {Kind::Macro, static_cast<uint8_t>(i), ModulatorField::Rate}; }
    static Target modulator(int m, ModulatorField f) { return {Kind::Modulator, static_cast<uint8_t>(m), f}; }
};

struct ReactionSpec {
    Source source;
    Target target;
    ReactionShape shape;
    float riseSeconds = 0.0f;
    float fallSeconds = 0.0f;
};

// One property a Macro moves (MacroLink): at Macro 0 the target sits at `low`, at kMacroRest at its
// own value, at 1 at `high`. A Macro or empty target moves nothing.
struct MacroLinkSpec {
    uint8_t macro = 0;
    Target target;
    float low = 0.0f;
    float high = 1.0f;
};

// Where a Macro at `value` puts a link's target whose own value is `base` (Evaluation.macro).
float macroLink(float base, float low, float high, float value);

// Behaviour the Customization performs by itself over the music (CONTEXT.md, Automation): the kinds
// of Automations in :feature:customization:api, in its order, with the meaning `Automation` in
// :feature:customization:core gives them. Beats are counted whole from 0 at the start of the music.
// A cadence (StyleSwitch, Palette) is due on each Surge or Change when `onBigMoments`, else on each
// beat past 0 that is a multiple of `everyBeats` (EveryBars: bars * 4).
//   StyleSwitch  no target: counts each due moment into Evaluated::stylesDue, from which the reducer
//                moves to the next Style (CustomizationEvent.StyleDue) and the engine crossfades;
//   Palette      moves the Palette to the next one, wrapping round, each due moment;
//   Camera       pushes its Camera property `amount` of its range on every `everyBeats`th beat,
//                easing back over the beat;
//   Sequence     holds its target at step (beat / everyBeats) of its steps;
//   Autopilot    moves its Macro `amount` (the width) either side of the person's value, one swing
//                every `everyBeats` (bars * 4).
enum class AutomationKind : uint8_t { StyleSwitch, Palette, Camera, Sequence, Autopilot };

struct AutomationSpec {
    AutomationKind kind = AutomationKind::Sequence;
    Target target;
    float amount = 0.25f;
    int everyBeats = 4;
    bool onBigMoments = false;
    std::array<float, kMaxSteps> steps{};
    int stepCount = 0;
};

// What the engine is handed: the Customization's own values and its links.
struct CustomizationPlan {
    // Native units, as the sliders hold them.
    std::array<float, kSharedCount> shared{};
    // Fractions 0..1.
    std::array<float, kExtraCount> extras{};
    std::array<float, kMacroCount> macros{};
    std::array<SignalTrim, kSoundSignalCount> trims{};
    std::array<ReactionSpec, kMaxReactions> reactions{};
    int reactionCount = 0;
    std::array<ModulatorSpec, kMaxModulators> modulators{};
    int modulatorCount = 0;
    std::array<MacroLinkSpec, kMaxMacroLinks> macroLinks{};
    int macroLinkCount = 0;
    std::array<AutomationSpec, kMaxAutomations> automations{};
    int automationCount = 0;

    // Every property at its starting value, Macros at rest, neutral trims, no links.
    static CustomizationPlan defaults();
    // Appends one and returns its index, or -1 when the plan is full.
    int add(const ReactionSpec& reaction);
    int add(const ModulatorSpec& modulator);
    int add(const MacroLinkSpec& link);
    int add(const AutomationSpec& automation);
};

// One frame's result.
struct Evaluated {
    // Native units, clamped.
    std::array<float, kSharedCount> shared{};
    // Fractions 0..1.
    std::array<float, kExtraCount> extras{};
    std::array<float, kMacroCount> macros{};
    std::array<float, kMaxModulators> modulators{};
    std::array<bool, kMaxModulators> running{};
    // How many times Style switching has been due since the last reset; the host raises StyleDue
    // each time it goes up.
    uint32_t stylesDue = 0;

    float operator[](Shared p) const { return shared[static_cast<size_t>(p)]; }
};

class ReactionGraph {
public:
    // `raw` holds this frame's Sound signals before the normaliser.
    const Evaluated& evaluate(const CustomizationPlan& plan, const SignalFrame& raw, float dt);
    void reset();

private:
    using FieldValues = std::array<float, kModulatorFieldCount>;

    std::array<Follower, kMaxReactions> followers_{};
    std::array<Modulator, kMaxModulators> modulators_{};
    // What the Reactions on each Modulator's fields gave last frame, or ModulatorDrive::kOwn.
    std::array<FieldValues, kMaxModulators> drive_{};
    // Each Shared property's fraction last frame, from which a luminous one may only rise so fast.
    std::array<float, kSharedCount> lastShared_{};
    // The Automations' clock, in beats; the beat block each cadence last saw; how often each was due.
    double beats_ = 0.0;
    std::array<int64_t, kMaxAutomations> lastBlock_{};
    std::array<uint32_t, kMaxAutomations> due_{};
    bool bigMomentArmed_ = true;
    bool primed_ = false;
    Evaluated out_;
};

}  // namespace geode::viz::mod
