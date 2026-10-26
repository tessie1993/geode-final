#include "geode/viz/ReactionGraph.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::mod {

namespace {
constexpr float kRateSpan = kMaxRate - kMinRate;

// A rise or fall time the follower can use: anything broken or negative is no delay.
float durationOrNone(float s) { return std::isfinite(s) && s > 0.0f ? s : 0.0f; }

constexpr size_t kRate = static_cast<size_t>(ModulatorField::Rate);
constexpr size_t kDepth = static_cast<size_t>(ModulatorField::Depth);
constexpr size_t kPattern = static_cast<size_t>(ModulatorField::Pattern);

float rateFraction(float rate) {
    const float r = std::isnan(rate) ? kDefaultRate : std::clamp(rate, kMinRate, kMaxRate);
    return (r - kMinRate) / kRateSpan;
}

bool runs(const ModulatorSpec& spec) { return spec.start != StartMode::ByHand || spec.playing; }

constexpr double kPi = 3.141592653589793;
// An Automation that has not yet seen a beat block, so it does not fire on its first frame.
constexpr int64_t kUnseen = INT64_MIN;
// An Automation that has not moved yet: its spacing does not hold it back.
constexpr double kNever = -1.0;
constexpr float kSmoothA = 3.0f;
constexpr float kSmoothB = 2.0f;

// A plan's own value for `property`: a fraction, its starting value when it is not a number.
float ownFraction(Shared property, float value) { return std::isnan(value) ? toUnit(property, spec(property).initial) : unit(value); }

// The Palette's index for a fraction of its range, and back.
int paletteIndex(float fraction) { return static_cast<int>(fromUnit(Shared::Palette, fraction)); }
float paletteFraction(int index) { return toUnit(Shared::Palette, static_cast<float>(index)); }
int paletteCount() { return static_cast<int>(spec(Shared::Palette).max) + 1; }
int wrapPalette(int64_t index) {
    const int64_t count = paletteCount();
    return static_cast<int>(((index % count) + count) % count);
}
}  // namespace

bool silent(float level) { return level < kSilenceLevel; }

float raisedThreshold(float threshold, float sinceSeconds) {
    const float since = std::isfinite(sinceSeconds) ? std::max(sinceSeconds, 0.0f) : 0.0f;
    const float room = std::max(1.0f - threshold, 0.0f);
    return threshold + room * kThresholdRise * std::exp2(-since / kThresholdHalfLifeSeconds);
}

float crossfade(float elapsedSeconds, float lengthSeconds) {
    if (!(lengthSeconds > 0.0f)) return 1.0f;
    const float x = unit(elapsedSeconds / lengthSeconds);
    return x * x * (kSmoothA - kSmoothB * x);
}

float macroLink(float base, float low, float high, float value) {
    const float v = unit(value);
    const float b = unit(base);
    if (v < kMacroRest) return unit(low) + (b - unit(low)) * (v / kMacroRest);
    return b + (unit(high) - b) * ((v - kMacroRest) / (1.0f - kMacroRest));
}

CustomizationPlan CustomizationPlan::defaults() {
    CustomizationPlan plan;
    for (size_t p = 0; p < kSharedCount; ++p) plan.shared[p] = toUnit(static_cast<Shared>(p), sharedSpecs()[p].initial);
    plan.macros.fill(kMacroRest);
    return plan;
}

int CustomizationPlan::add(const ReactionSpec& reaction) {
    if (reactionCount < 0 || reactionCount >= kMaxReactions) return -1;
    reactions[static_cast<size_t>(reactionCount)] = reaction;
    return reactionCount++;
}

int CustomizationPlan::add(const ModulatorSpec& modulator) {
    if (modulatorCount < 0 || modulatorCount >= kMaxModulators) return -1;
    modulators[static_cast<size_t>(modulatorCount)] = modulator;
    return modulatorCount++;
}

int CustomizationPlan::add(const MacroLinkSpec& link) {
    if (macroLinkCount < 0 || macroLinkCount >= kMaxMacroLinks) return -1;
    macroLinks[static_cast<size_t>(macroLinkCount)] = link;
    return macroLinkCount++;
}

int CustomizationPlan::add(const AutomationSpec& automation) {
    if (automationCount < 0 || automationCount >= kMaxAutomations) return -1;
    automations[static_cast<size_t>(automationCount)] = automation;
    return automationCount++;
}

void ReactionGraph::reset() {
    for (auto& f : followers_) f.reset();
    for (auto& m : modulators_) m.reset();
    beats_ = 0.0;
    seconds_ = 0.0;
    quietSeconds_ = 0.0;
    lastBlock_.fill(kUnseen);
    due_.fill(0);
    armed_.fill(true);
    lastMove_.fill(kNever);
    spacedFrom_.fill(kNever);
    primed_ = false;
    out_ = Evaluated{};
}

const Evaluated& ReactionGraph::evaluate(const CustomizationPlan& plan, const SignalFrame& raw, float dt) {
    const float step = std::isfinite(dt) && dt > 0.0f ? std::min(dt, kMaxFrameSeconds) : 0.0f;
    const int reactionCount = std::clamp(plan.reactionCount, 0, kMaxReactions);
    const int modulatorCount = std::clamp(plan.modulatorCount, 0, kMaxModulators);
    const int linkCount = std::clamp(plan.macroLinkCount, 0, kMaxMacroLinks);
    const int automationCount = std::clamp(plan.automationCount, 0, kMaxAutomations);

    // 1. The normaliser.
    SignalFrame frame = raw;
    for (size_t s = 0; s < kSoundSignalCount; ++s) frame.level[s] = normalise(raw.level[s], plan.trims[s]);

    // The shared values as fractions of their ranges, where every Reaction works.
    std::array<float, kSharedCount> shared{};
    for (size_t p = 0; p < kSharedCount; ++p) shared[p] = ownFraction(static_cast<Shared>(p), plan.shared[p]);
    if (!primed_) {
        primed_ = true;
        for (auto& d : drive_) d.fill(ModulatorDrive::kOwn);
        lastShared_ = shared;
        lastBlock_.fill(kUnseen);
        armed_.fill(true);
        lastMove_.fill(kNever);
        spacedFrom_.fill(kNever);
    }

    // 2. The Modulators: only those some Reaction listens to run.
    std::array<bool, kMaxModulators> listened{};
    for (int r = 0; r < reactionCount; ++r) {
        const Source& source = plan.reactions[static_cast<size_t>(r)].source;
        if (source.kind == Source::Kind::Modulator && source.index < modulatorCount) listened[source.index] = true;
    }
    std::array<FieldValues, kMaxModulators> fields{};
    for (int m = 0; m < kMaxModulators; ++m) {
        const auto i = static_cast<size_t>(m);
        const ModulatorSpec& spec = plan.modulators[i];
        if (m >= modulatorCount || !listened[i]) {
            modulators_[i].reset();
            drive_[i].fill(ModulatorDrive::kOwn);
            out_.modulators[i] = 0.0f;
            out_.running[i] = false;
            continue;
        }
        const FieldValues& pushed = drive_[i];
        ModulatorDrive drive;
        if (pushed[kRate] >= 0.0f) drive.rate = kMinRate + pushed[kRate] * kRateSpan;
        drive.depth = pushed[kDepth];
        drive.pattern = pushed[kPattern];
        out_.modulators[i] = modulators_[i].tick(spec, drive, frame, step);
        out_.running[i] = runs(spec);
        fields[i][kRate] = rateFraction(spec.rate);
        fields[i][kDepth] = unit(spec.depth);
        fields[i][kPattern] = 0.0f;
    }

    // 3. The targets move.
    std::array<float, kExtraCount> extras{};
    for (size_t e = 0; e < kExtraCount; ++e) extras[e] = unit(plan.extras[e]);
    std::array<float, kMacroCount> macros{};
    for (size_t k = 0; k < kMacroCount; ++k) macros[k] = unit(plan.macros[k]);
    std::array<std::array<bool, kModulatorFieldCount>, kMaxModulators> touched{};
    const auto slotFor = [&](const Target& target) -> float* {
        switch (target.kind) {
            case Target::Kind::None: break;
            case Target::Kind::Shared:
                if (target.index < kSharedCount) return &shared[target.index];
                break;
            case Target::Kind::Extra:
                if (target.index < kExtraCount) return &extras[target.index];
                break;
            case Target::Kind::Macro:
                if (target.index < kMacroCount) return &macros[target.index];
                break;
            case Target::Kind::Modulator:
                if (target.index < modulatorCount && static_cast<size_t>(target.field) < kModulatorFieldCount) {
                    touched[target.index][static_cast<size_t>(target.field)] = true;
                    return &fields[target.index][static_cast<size_t>(target.field)];
                }
                break;
        }
        return nullptr;
    };

    // The Automations, on the beat clock. It runs only on the tempo the live analysis reports, while
    // the music plays: until a tempo is reported the clock holds, and nothing beat-timed moves.
    if (frame.playing) {
        seconds_ += step;
        if (frame.beatSeconds > 0.0f) beats_ += step / std::max(frame.beatSeconds, kMinPeriodSeconds);
    }
    const float bigMoment = std::max(frame[SoundSignal::Surge], frame[SoundSignal::Change]);
    // Silence, from the Level as heard: a trim must not turn a quiet room into music (#361).
    const bool quiet = silent(raw[SoundSignal::Level]);
    if (frame.playing) quietSeconds_ = quiet ? quietSeconds_ + step : 0.0;
    const bool longQuiet = quietSeconds_ >= kSilenceSeconds;
    const auto beat = static_cast<int64_t>(std::floor(beats_));
    for (int a = 0; a < kMaxAutomations; ++a) {
        const auto i = static_cast<size_t>(a);
        if (a >= automationCount) {
            lastBlock_[i] = kUnseen;
            due_[i] = 0;
            armed_[i] = true;
            lastMove_[i] = kNever;
            spacedFrom_[i] = kNever;
            continue;
        }
        const AutomationSpec& automation = plan.automations[i];
        const int every = std::max(automation.everyBeats, 1);
        const int64_t block = beat / every;
        const bool switching = automation.kind == AutomationKind::StyleSwitch;
        bool fired = false;
        if (automation.trigger == TriggerKind::BigMoments) {
            float threshold = std::isnan(automation.threshold) ? 1.0f : automation.threshold;
            if (switching && lastMove_[i] >= 0.0) threshold = raisedThreshold(threshold, static_cast<float>(seconds_ - lastMove_[i]));
            fired = armed_[i] && bigMoment >= threshold;
            armed_[i] = bigMoment < threshold;
        } else {
            fired = lastBlock_[i] != kUnseen && block != lastBlock_[i] && block > 0;
        }
        lastBlock_[i] = block;
        const double spacing = std::isfinite(automation.spacingSeconds) ? std::max(automation.spacingSeconds, 0.0f) : 0.0;
        if (automation.held || (switching && longQuiet)) spacedFrom_[i] = seconds_;
        const bool spaced = spacedFrom_[i] < 0.0 || seconds_ - spacedFrom_[i] >= spacing;
        const bool moves = fired && !automation.held && !(switching && quiet) && spaced;
        if (moves) spacedFrom_[i] = seconds_;
        switch (automation.kind) {
            case AutomationKind::StyleSwitch:
                if (moves) {
                    lastMove_[i] = seconds_;
                    ++out_.stylesDue;
                }
                break;
            case AutomationKind::Palette: {
                if (moves) {
                    lastMove_[i] = seconds_;
                    ++due_[i];
                }
                if (due_[i] == 0) break;
                const int own = paletteIndex(shared[static_cast<size_t>(Shared::Palette)]);
                const int now = wrapPalette(static_cast<int64_t>(own) + due_[i]);
                const float mix = crossfade(static_cast<float>(seconds_ - lastMove_[i]), automation.fadeSeconds);
                if (mix >= 1.0f) {
                    shared[static_cast<size_t>(Shared::Palette)] = paletteFraction(now);
                } else {
                    shared[static_cast<size_t>(Shared::Palette)] = paletteFraction(wrapPalette(static_cast<int64_t>(own) + due_[i] - 1));
                    shared[static_cast<size_t>(Shared::Palette2)] = paletteFraction(now);
                    shared[static_cast<size_t>(Shared::PaletteMix)] = mix;
                }
                break;
            }
            case AutomationKind::Camera: {
                float* slot = beats_ > 0.0 ? slotFor(automation.target) : nullptr;
                if (!slot) break;
                const double round = std::floor(beats_ / every);
                const auto swell = static_cast<float>(std::pow(std::sin(kPi * (beats_ / every - round)), 2.0));
                const bool odd = static_cast<int64_t>(round) % 2 == 1;
                const float amount = std::isnan(automation.amount) ? 0.0f : std::clamp(automation.amount, -1.0f, 1.0f);
                *slot = unit(*slot + (automation.alternate && odd ? -amount : amount) * swell);
                break;
            }
            case AutomationKind::Lane: {
                const int steps = std::clamp(automation.stepCount, 0, kMaxSteps);
                float* slot = steps > 0 ? slotFor(automation.target) : nullptr;
                if (slot) *slot = unit(automation.steps[static_cast<size_t>(block % steps)]);
                break;
            }
        }
    }

    const auto applyReaction = [&](int r) {
        const ReactionSpec& reaction = plan.reactions[static_cast<size_t>(r)];
        Follower& follower = followers_[static_cast<size_t>(r)];
        float input = 0.0f;
        if (reaction.source.kind == Source::Kind::Sound && reaction.source.index < kSoundSignalCount) {
            input = frame.level[reaction.source.index];
        } else if (reaction.source.kind == Source::Kind::Modulator && reaction.source.index < modulatorCount) {
            input = out_.modulators[reaction.source.index];
        } else {
            follower.reset();
            return;
        }
        float* slot = slotFor(reaction.target);
        if (!slot) {
            follower.reset();
            return;
        }
        const float followed = follower.step(input, durationOrNone(reaction.riseSeconds), durationOrNone(reaction.fallSeconds), step);
        const bool drifts = reaction.source.kind == Source::Kind::Modulator && reaction.target.kind == Target::Kind::Macro;
        *slot = drifts ? drift(*slot, followed, reaction.shape) : react(*slot, followed, reaction.shape);
    };
    const auto onMacro = [&](int r) { return plan.reactions[static_cast<size_t>(r)].target.kind == Target::Kind::Macro; };

    // The Reactions on a Macro first, so its links follow the moved value.
    for (int r = 0; r < reactionCount; ++r) {
        if (onMacro(r)) applyReaction(r);
    }
    // The Macro links. A link onto a Macro moves nothing: only Reactions and Automations move a Macro.
    for (int l = 0; l < linkCount; ++l) {
        const MacroLinkSpec& link = plan.macroLinks[static_cast<size_t>(l)];
        if (link.macro >= kMacroCount || link.target.kind == Target::Kind::Macro) continue;
        if (float* slot = slotFor(link.target)) *slot = macroLink(*slot, link.low, link.high, macros[link.macro]);
    }
    // The other Reactions, in their order.
    for (int r = 0; r < reactionCount; ++r) {
        if (!onMacro(r)) applyReaction(r);
    }

    // 4. The safety clamp, last and unconditional.
    const float riseLimit = step / kNoFlashMinRiseSeconds;
    for (size_t p = 0; p < kSharedCount; ++p) {
        float u = unit(shared[p]);
        if (sharedSpecs()[p].luminous) u = std::min(u, lastShared_[p] + riseLimit);
        lastShared_[p] = u;
        out_.unitShared[p] = u;
        out_.shared[p] = fromUnit(static_cast<Shared>(p), u);
    }
    for (size_t e = 0; e < kExtraCount; ++e) out_.extras[e] = unit(extras[e]);
    for (size_t k = 0; k < kMacroCount; ++k) out_.macros[k] = unit(macros[k]);
    out_.layer = plan.layer;
    for (int m = 0; m < modulatorCount; ++m) {
        const auto i = static_cast<size_t>(m);
        if (!listened[i]) continue;
        for (size_t f = 0; f < kModulatorFieldCount; ++f) drive_[i][f] = touched[i][f] ? unit(fields[i][f]) : ModulatorDrive::kOwn;
    }
    return out_;
}

}  // namespace geode::viz::mod
