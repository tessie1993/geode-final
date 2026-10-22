#pragma once
#include <cstddef>

#include "geode/api/geode_api.h"
#include "geode/viz/ReactionGraph.hpp"

namespace geode::viz::mod {

// A CustomizationPlan as the snapshot's `plan` field carries it (core/bridge/schema/native_bridge.json):
// kPlanFloats floats, written by NativePlan in :feature:customization:core. Enums and indices are
// whole floats, flags 0 or 1, times in seconds, values as the plan holds them. In order:
//
//   header       carried (1 when the snapshot carries a plan), then the Reaction, Modulator, Macro
//                link and Automation counts
//   shared       kSharedCount, native units
//   extras       kExtraCount
//   macros       kMacroCount
//   trims        kSoundSignalCount pairs of gain, floor
//   reactions    kMaxReactions of kReactionFloats: source kind, source index, target kind, target
//                index, target field, amount, invert, low, high, curve, rise, fall
//   modulators   kMaxModulators of kModulatorFloats: kind, wave, period, beat sync, signal, attack,
//                release, threshold, kMaxSteps steps, step count, step length, start, start signal,
//                playing, rate, depth, seed
//   macro links  kMaxMacroLinks of kMacroLinkFloats: macro, target kind, target index, target field,
//                low, high
//   automations  kMaxAutomations of kAutomationFloats: kind, target kind, target index, target field,
//                amount, every (beats), on big moments, step count, kMaxSteps steps
//
// Target kinds and source kinds are Target::Kind and Source::Kind in order.
constexpr size_t kPlanHeaderFloats = 5;
constexpr size_t kReactionFloats = 12;
constexpr size_t kModulatorFloats = 16 + kMaxSteps;
constexpr size_t kMacroLinkFloats = 6;
constexpr size_t kAutomationFloats = 8 + kMaxSteps;
// The snapshot schema reads this literal.
constexpr size_t kPlanFloats = 2940;
static_assert(kPlanFloats == kPlanHeaderFloats + kSharedCount + kExtraCount + kMacroCount + 2 * kSoundSignalCount +
                                 kMaxReactions * kReactionFloats + kMaxModulators * kModulatorFloats +
                                 kMaxMacroLinks * kMacroLinkFloats + kMaxAutomations * kAutomationFloats);

// Reads `count` floats into `out`. False, with `out` untouched, when they are not kPlanFloats or do
// not carry a plan. Anything out of range is kept in range; the graph ignores what targets nothing.
bool decodePlan(const float* data, size_t count, CustomizationPlan& out);

// One feature frame as the Sound signals a Reaction listens to, before the normaliser.
SignalFrame signalFrame(const GeodeFeatureFrame& features);

}  // namespace geode::viz::mod
