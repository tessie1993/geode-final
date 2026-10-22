#include "geode/viz/ReactionGraph.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::mod {

namespace {
constexpr float kRateSpan = kMaxRate - kMinRate;

float seconds(float s) { return std::isfinite(s) && s > 0.0f ? s : 0.0f; }

float rateFraction(float rate) {
    const float r = std::isnan(rate) ? kDefaultRate : std::clamp(rate, kMinRate, kMaxRate);
    return (r - kMinRate) / kRateSpan;
}

bool runs(const ModulatorSpec& spec) { return spec.start != StartMode::ByHand || spec.playing; }
}  // namespace

CustomizationPlan CustomizationPlan::defaults() {
    CustomizationPlan plan;
    for (size_t p = 0; p < kSharedCount; ++p) plan.shared[p] = sharedSpecs()[p].initial;
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

void ReactionGraph::reset() {
    for (auto& f : followers_) f.reset();
    for (auto& m : modulators_) m.reset();
    primed_ = false;
    out_ = Evaluated{};
}

const Evaluated& ReactionGraph::evaluate(const CustomizationPlan& plan, const SignalFrame& raw, float dt) {
    const float step = std::isfinite(dt) && dt > 0.0f ? std::min(dt, kMaxFrameSeconds) : 0.0f;
    const int reactionCount = std::clamp(plan.reactionCount, 0, kMaxReactions);
    const int modulatorCount = std::clamp(plan.modulatorCount, 0, kMaxModulators);

    // 1. The normaliser.
    SignalFrame frame = raw;
    for (size_t s = 0; s < kSoundSignalCount; ++s) frame.level[s] = normalise(raw.level[s], plan.trims[s]);

    // The shared values as fractions of their ranges, where every Reaction works.
    std::array<float, kSharedCount> shared{};
    for (size_t p = 0; p < kSharedCount; ++p) shared[p] = toUnit(static_cast<Shared>(p), plan.shared[p]);
    if (!primed_) {
        primed_ = true;
        for (auto& d : drive_) d.fill(ModulatorDrive::kOwn);
        lastShared_ = shared;
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
        if (pushed[0] >= 0.0f) drive.rate = kMinRate + pushed[0] * kRateSpan;
        drive.depth = pushed[1];
        drive.pattern = pushed[2];
        out_.modulators[i] = modulators_[i].tick(spec, drive, frame, step);
        out_.running[i] = runs(spec);
        fields[i] = {rateFraction(spec.rate), unit(spec.depth), 0.0f};
    }

    // 3. The Reactions, in order.
    std::array<float, kExtraCount> extras{};
    for (size_t e = 0; e < kExtraCount; ++e) extras[e] = unit(plan.extras[e]);
    std::array<float, kMacroCount> macros{};
    for (size_t k = 0; k < kMacroCount; ++k) macros[k] = unit(plan.macros[k]);
    std::array<std::array<bool, kModulatorFieldCount>, kMaxModulators> touched{};
    for (int r = 0; r < reactionCount; ++r) {
        const ReactionSpec& reaction = plan.reactions[static_cast<size_t>(r)];
        Follower& follower = followers_[static_cast<size_t>(r)];
        float input = 0.0f;
        if (reaction.source.kind == Source::Kind::Sound && reaction.source.index < kSoundSignalCount) {
            input = frame.level[reaction.source.index];
        } else if (reaction.source.kind == Source::Kind::Modulator && reaction.source.index < modulatorCount) {
            input = out_.modulators[reaction.source.index];
        } else {
            follower.reset();
            continue;
        }
        const Target& target = reaction.target;
        float* slot = nullptr;
        switch (target.kind) {
            case Target::Kind::None: break;
            case Target::Kind::Shared:
                if (target.index < kSharedCount) slot = &shared[target.index];
                break;
            case Target::Kind::Extra:
                if (target.index < kExtraCount) slot = &extras[target.index];
                break;
            case Target::Kind::Macro:
                if (target.index < kMacroCount) slot = &macros[target.index];
                break;
            case Target::Kind::Modulator:
                if (target.index < modulatorCount && static_cast<size_t>(target.field) < kModulatorFieldCount) {
                    slot = &fields[target.index][static_cast<size_t>(target.field)];
                    touched[target.index][static_cast<size_t>(target.field)] = true;
                }
                break;
        }
        if (!slot) {
            follower.reset();
            continue;
        }
        const float followed = follower.step(input, seconds(reaction.riseSeconds), seconds(reaction.fallSeconds), step);
        *slot = react(*slot, followed, reaction.shape);
    }

    // 4. The safety clamp, last and unconditional.
    const float riseLimit = step / kNoFlashMinRiseSeconds;
    for (size_t p = 0; p < kSharedCount; ++p) {
        float u = unit(shared[p]);
        if (sharedSpecs()[p].luminous) u = std::min(u, lastShared_[p] + riseLimit);
        lastShared_[p] = u;
        out_.shared[p] = fromUnit(static_cast<Shared>(p), u);
    }
    for (size_t e = 0; e < kExtraCount; ++e) out_.extras[e] = unit(extras[e]);
    for (size_t k = 0; k < kMacroCount; ++k) out_.macros[k] = unit(macros[k]);
    for (int m = 0; m < modulatorCount; ++m) {
        const auto i = static_cast<size_t>(m);
        if (!listened[i]) continue;
        for (size_t f = 0; f < kModulatorFieldCount; ++f) drive_[i][f] = touched[i][f] ? unit(fields[i][f]) : ModulatorDrive::kOwn;
    }
    return out_;
}

}  // namespace geode::viz::mod
