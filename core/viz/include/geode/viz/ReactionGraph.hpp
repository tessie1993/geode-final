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
//   3. the targets move, in this order: the Automations set or add to their targets' own values;
//      the Reactions on a Macro move its value (a Modulator linked to a Macro makes it drift around
//      the person's value, Evaluation.drift); every Macro link moves its property from the Macro's
//      value (Evaluation.macro); then the other Reactions, in their order, each risen and fallen,
//      push their targets: a Shared core property, an Extra, or a Modulator's rate, depth or pattern;
//   4. the safety clamp runs unconditionally: every value finite and inside 0..1, and a luminous
//      property never rises faster than the No-flash minimum (a full swing over
//      kNoFlashMinRiseSeconds), whatever moved it.
//
// Everything here is normalised (#54, owner decision 2026-10-22): the plan's values and the result
// are fractions 0..1 of each target's range. The Normaliser then maps them into the Style family's
// own ranges, before the uniforms are set, and its range clamp runs last. The No-flash limiter then
// bounds the frame.
//
// Loops are allowed (#11): a Modulator may move another, or itself, through its rate, depth or
// pattern. A Modulator's own fields take hold one frame later, so a loop is a feedback with one
// frame of delay rather than a recursion, and the clamp keeps every turn of it in range.
//
// Every array is fixed-size, so a plan crosses the native bridge as one snapshot field (PlanCodec).

constexpr int kMaxReactions = 128;
constexpr int kMaxModulators = 16;
constexpr int kMaxMacroLinks = 64;
// Room for every Automation (#41): Style switching, palette changes, one per Camera-move property
// and one per lane target (Automations.MAX_LANES lanes of MAX_LANE_TARGETS).
constexpr int kMaxAutomations = 48;
// The longest frame the graph steps over: a stall does not jump every Modulator ahead at once.
constexpr float kMaxFrameSeconds = 0.1f;
// The Level (0..1, as heard, before the trims) under which Style switching takes the music for silence
// (#361), and how long silence lasts before switching's spacing starts again when sound returns. The
// one place to retarget silence: to the analysis' measured noise floor once it reports one (#278).
// Automation.SILENCE_LEVEL and SILENCE_SECONDS in :feature:customization:core mirror them.
constexpr float kSilenceLevel = 0.02f;
constexpr float kSilenceSeconds = 2.5f;

// Whether Style switching takes a frame whose Level is `level` for silence.
bool silent(float level);

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

// Behaviour the Customization performs by itself over the music (CONTEXT.md, Automation; #41), with
// the meaning `Automation` in :feature:customization:core gives it. Everything is live: beats are the
// whole beats the live analysis has reported, counted from 0 at the start of the music; until it
// reports a tempo the beat clock holds and nothing beat-timed moves. No tempo is ever assumed.
//
// A cadence (StyleSwitch, Palette) fires on its trigger, and never sooner than `spacingSeconds`
// (played time) after its last move:
//   EveryBeats   on each beat past 0 that is a multiple of `everyBeats` (EveryBars: bars * 4);
//   BigMoments   when the louder of Surge and Change rises past `threshold`, once each time.
// A `held` cadence never moves, and its spacing starts again from the moment it is released: the
// person's Hold, or Customize being open (#282), so a switch never lands straight after either.
// Style switching also waits during silence (#361): never while the Level heard (before the trims) is
// under kSilenceLevel, and once that has lasted kSilenceSeconds of play, its spacing starts again when
// sound returns. A pause, the gap between tracks or a quiet intro never switches.
//
//   StyleSwitch  no target: counts each move into Evaluated::stylesDue, from which the reducer moves
//                to the next Style (CustomizationEvent.StyleDue) and the engine crossfades;
//   Palette      each move steps the Palette on by one from the person's, wrapping round, and
//                crossfades into it over `fadeSeconds` through Palette 2 and the Palette mix (never a
//                cut; Automation.paletteFrame);
//   Camera       adds `amount` (signed, a fraction of its property's range) times an eased swell,
//                sin^2 over each span of `everyBeats` beats, on top of the Camera setting; with
//                `alternate`, the other way every other span (Automation.camera);
//   Lane         holds its target at step (beat / everyBeats) of its steps: one target of a lane,
//                stepped by beat (1) or bar (4) (Automation.step).
enum class AutomationKind : uint8_t { StyleSwitch, Palette, Camera, Lane };
enum class TriggerKind : uint8_t { EveryBeats, BigMoments };

struct AutomationSpec {
    AutomationKind kind = AutomationKind::Lane;
    Target target;
    float amount = 0.25f;
    int everyBeats = 4;
    TriggerKind trigger = TriggerKind::EveryBeats;
    float threshold = 0.6f;
    float spacingSeconds = 0.0f;
    float fadeSeconds = 2.0f;
    bool alternate = false;
    bool held = false;
    std::array<float, kMaxSteps> steps{};
    int stepCount = 0;
};

// How far a crossfade of `lengthSeconds` is, 0..1, `elapsedSeconds` into it: a smooth S-curve
// (Automation.crossfade). A length of 0 or less is already done.
float crossfade(float elapsedSeconds, float lengthSeconds);

// What the engine is handed: the Customization's own values and its links.
struct CustomizationPlan {
    // Fractions 0..1 of each property's range (normalised; the Normaliser maps them per family).
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
    // Fractions 0..1, clamped: what the Normaliser maps into the Style family's ranges.
    std::array<float, kSharedCount> unitShared{};
    // The same in the Shared core's own ranges (fromUnit): the family ranges of a Style that declares
    // none of its own.
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
    // The Automations' clocks: beats reported, and seconds played. Per Automation: the beat block it
    // last saw, how often it moved, whether a big moment may fire, when it last moved, and when its
    // spacing started (its last move, or the moment a hold let go).
    double beats_ = 0.0;
    double seconds_ = 0.0;
    // How long the music has been silent, in seconds played (#361).
    double quietSeconds_ = 0.0;
    std::array<int64_t, kMaxAutomations> lastBlock_{};
    std::array<uint32_t, kMaxAutomations> due_{};
    std::array<bool, kMaxAutomations> armed_{};
    std::array<double, kMaxAutomations> lastMove_{};
    std::array<double, kMaxAutomations> spacedFrom_{};
    bool primed_ = false;
    Evaluated out_;
};

}  // namespace geode::viz::mod
