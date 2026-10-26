#include "geode/viz/PlanCodec.hpp"

#include <gtest/gtest.h>

#include <algorithm>
#include <cstddef>
#include <iterator>
#include <limits>
#include <vector>

namespace geode::viz::mod {
namespace {

// Where each part starts (PlanCodec.hpp), as NativePlan in :feature:customization:core writes it.
constexpr size_t kSharedAt = kPlanHeaderFloats;
constexpr size_t kExtrasAt = kSharedAt + kSharedCount;
constexpr size_t kMacrosAt = kExtrasAt + kExtraCount;
constexpr size_t kTrimsAt = kMacrosAt + kMacroCount;
constexpr size_t kReactionsAt = kTrimsAt + 2 * kSoundSignalCount;
constexpr size_t kModulatorsAt = kReactionsAt + kMaxReactions * kReactionFloats;
constexpr size_t kLinksAt = kModulatorsAt + kMaxModulators * kModulatorFloats;
constexpr size_t kAutomationsAt = kLinksAt + kMaxMacroLinks * kMacroLinkFloats;
constexpr size_t kLayerAt = kAutomationsAt + kMaxAutomations * kAutomationFloats;
constexpr size_t kStyleAt = kLayerAt + kLayerFloats;
constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();

std::vector<float> carried() {
    std::vector<float> data(kPlanFloats, 0.0f);
    data[0] = 1.0f;
    return data;
}

CustomizationPlan decoded(const std::vector<float>& data) {
    CustomizationPlan plan;
    EXPECT_TRUE(decodePlan(data.data(), data.size(), plan));
    return plan;
}

TEST(PlanCodec, TakesOnlyAWholePlanThatIsCarried) {
    CustomizationPlan plan;
    plan.reactionCount = 3;
    std::vector<float> data = carried();
    EXPECT_FALSE(decodePlan(data.data(), data.size() - 1, plan));
    EXPECT_FALSE(decodePlan(nullptr, data.size(), plan));
    data[0] = 0.0f;
    EXPECT_FALSE(decodePlan(data.data(), data.size(), plan));
    data[0] = kNaN;
    EXPECT_FALSE(decodePlan(data.data(), data.size(), plan));
    EXPECT_EQ(plan.reactionCount, 3);
}

TEST(PlanCodec, ReadsTheCountsAndValues) {
    std::vector<float> data = carried();
    data[1] = 2.0f;
    data[2] = 1.0f;
    data[3] = 3.0f;
    data[4] = 4.0f;
    data[kSharedAt + static_cast<size_t>(Shared::Zoom)] = 2.5f;
    data[kExtrasAt + 2] = 0.75f;
    data[kMacrosAt + 1] = 0.25f;
    data[kTrimsAt + 2 * static_cast<size_t>(SoundSignal::Bass)] = 2.0f;
    data[kTrimsAt + 2 * static_cast<size_t>(SoundSignal::Bass) + 1] = 0.3f;
    const CustomizationPlan plan = decoded(data);
    EXPECT_EQ(plan.reactionCount, 2);
    EXPECT_EQ(plan.modulatorCount, 1);
    EXPECT_EQ(plan.macroLinkCount, 3);
    EXPECT_EQ(plan.automationCount, 4);
    EXPECT_EQ(plan.shared[static_cast<size_t>(Shared::Zoom)], 2.5f);
    EXPECT_EQ(plan.extras[2], 0.75f);
    EXPECT_EQ(plan.macros[1], 0.25f);
    EXPECT_EQ(plan.trims[static_cast<size_t>(SoundSignal::Bass)].gain, 2.0f);
    EXPECT_EQ(plan.trims[static_cast<size_t>(SoundSignal::Bass)].floor, 0.3f);
}

TEST(PlanCodec, ReadsLayerTwoBeforeTheStyle) {
    EXPECT_EQ(kStyleAt + kStyleFloats, kPlanFloats);
    std::vector<float> data = carried();
    data[kLayerAt] = 3997830.0f;
    data[kLayerAt + 1] = 5.0f;
    data[kLayerAt + 2] = 2.0f;
    const CustomizationPlan plan = decoded(data);
    EXPECT_EQ(plan.layer.style, 3997830u);
    EXPECT_EQ(plan.layer.blend, layer::Blend::Overlay);
    EXPECT_EQ(plan.layer.influence, layer::Influence::Swirl);
}

TEST(PlanCodec, AnythingButAWhole24BitNameIsNoLayerTwo) {
    for (const float style : {0.0f, 0.5f, -3.0f, 12.5f, 16777216.0f, kNaN, std::numeric_limits<float>::infinity()}) {
        std::vector<float> data = carried();
        data[kLayerAt] = style;
        data[kLayerAt + 1] = 42.0f;
        data[kLayerAt + 2] = -1.0f;
        const CustomizationPlan plan = decoded(data);
        EXPECT_EQ(plan.layer.style, layer::kNoStyle) << style;
        EXPECT_EQ(plan.layer.blend, layer::Blend::Normal);
        EXPECT_EQ(plan.layer.influence, layer::Influence::None);
    }
    std::vector<float> data = carried();
    data[kLayerAt] = 1.0f;
    EXPECT_EQ(decoded(data).layer.style, 1u);
    data[kLayerAt] = 16777215.0f;
    EXPECT_EQ(decoded(data).layer.style, 16777215u);
}

TEST(PlanCodec, ReadsTheStyleTheCustomizationIsForLast) {
    std::vector<float> data = carried();
    EXPECT_EQ(decoded(data).style, layer::kNoStyle) << "unnamed";
    data[kStyleAt] = 3997830.0f;
    EXPECT_EQ(decoded(data).style, 3997830u);
    EXPECT_EQ(decoded(data).layer.style, layer::kNoStyle) << "not Layer 2's";
    for (const float style : {0.5f, -3.0f, 12.5f, 16777216.0f, kNaN}) {
        data[kStyleAt] = style;
        EXPECT_EQ(decoded(data).style, layer::kNoStyle) << style;
    }
    data[kStyleAt] = 16777215.0f;
    EXPECT_EQ(decoded(data).style, 16777215u);
}

TEST(PlanCodec, NamesThePlansStyleWithoutDecodingIt) {
    std::vector<float> data = carried();
    data[kStyleAt] = 3997830.0f;
    EXPECT_EQ(geode::viz::mod::planStyle(data.data(), data.size()), 3997830u);
    data[kStyleAt] = 2.5f;
    EXPECT_EQ(geode::viz::mod::planStyle(data.data(), data.size()), layer::kNoStyle);
    data[kStyleAt] = 3997830.0f;
    EXPECT_EQ(geode::viz::mod::planStyle(data.data(), data.size() - 1), layer::kNoStyle) << "not a plan's size";
    EXPECT_EQ(geode::viz::mod::planStyle(nullptr, data.size()), layer::kNoStyle);
    data[0] = 0.0f;
    EXPECT_EQ(geode::viz::mod::planStyle(data.data(), data.size()), layer::kNoStyle) << "carries no plan";
}

TEST(PlanCodec, ReadsAReactionInItsPlace) {
    std::vector<float> data = carried();
    const size_t at = kReactionsAt + kReactionFloats;
    const float reaction[kReactionFloats] = {1, 2, 4, 3, 1, 0.5f, 1, 0.1f, 0.9f, 3, 0.15f, 0.4f};
    std::copy(std::begin(reaction), std::end(reaction), data.begin() + static_cast<std::ptrdiff_t>(at));
    const ReactionSpec r = decoded(data).reactions[1];
    EXPECT_EQ(r.source.kind, Source::Kind::Modulator);
    EXPECT_EQ(r.source.index, 2);
    EXPECT_EQ(r.target.kind, Target::Kind::Modulator);
    EXPECT_EQ(r.target.index, 3);
    EXPECT_EQ(r.target.field, ModulatorField::Depth);
    EXPECT_EQ(r.shape.amount, 0.5f);
    EXPECT_TRUE(r.shape.invert);
    EXPECT_EQ(r.shape.low, 0.1f);
    EXPECT_EQ(r.shape.high, 0.9f);
    EXPECT_EQ(r.shape.curve, Curve::SCurve);
    EXPECT_EQ(r.riseSeconds, 0.15f);
    EXPECT_EQ(r.fallSeconds, 0.4f);
}

TEST(PlanCodec, ReadsAModulatorInItsPlace) {
    std::vector<float> data = carried();
    const size_t at = kModulatorsAt + kModulatorFloats;
    const float head[8] = {2, 3, 1.5f, 1, 6, 0.02f, 0.4f, 0.6f};
    std::copy(std::begin(head), std::end(head), data.begin() + static_cast<std::ptrdiff_t>(at));
    data[at + 8] = 0.25f;
    data[at + 8 + kMaxSteps - 1] = 0.75f;
    const float tail[8] = {16, 0.125f, 1, 9, 1, 2.0f, 0.5f, 42};
    std::copy(std::begin(tail), std::end(tail), data.begin() + static_cast<std::ptrdiff_t>(at + 8 + kMaxSteps));
    const ModulatorSpec m = decoded(data).modulators[1];
    EXPECT_EQ(m.kind, ModulatorKind::StepSequencer);
    EXPECT_EQ(m.wave, Wave::Square);
    EXPECT_EQ(m.periodSeconds, 1.5f);
    EXPECT_TRUE(m.beatSync);
    EXPECT_EQ(m.signal, SoundSignal::Kick);
    EXPECT_EQ(m.attackSeconds, 0.02f);
    EXPECT_EQ(m.releaseSeconds, 0.4f);
    EXPECT_EQ(m.threshold, 0.6f);
    EXPECT_EQ(m.steps[0], 0.25f);
    EXPECT_EQ(m.steps[kMaxSteps - 1], 0.75f);
    EXPECT_EQ(m.stepCount, 16);
    EXPECT_EQ(m.stepSeconds, 0.125f);
    EXPECT_EQ(m.start, StartMode::OnSound);
    EXPECT_EQ(m.startSignal, SoundSignal::Beat);
    EXPECT_TRUE(m.playing);
    EXPECT_EQ(m.rate, 2.0f);
    EXPECT_EQ(m.depth, 0.5f);
    EXPECT_EQ(m.seed, 42u);
}

TEST(PlanCodec, ReadsAMacroLinkAndAnAutomationInTheirPlaces) {
    std::vector<float> data = carried();
    const float link[kMacroLinkFloats] = {5, 1, 12, 0, 0.8f, 0.2f};
    std::copy(std::begin(link), std::end(link), data.begin() + static_cast<std::ptrdiff_t>(kLinksAt + kMacroLinkFloats));
    const float automation[13] = {2, 1, 3, 0, -0.3f, 4, 1, 0.7f, 8, 2.5f, 1, 1, 3};
    const size_t automationAt = kAutomationsAt + kAutomationFloats;
    std::copy(std::begin(automation), std::end(automation), data.begin() + static_cast<std::ptrdiff_t>(automationAt));
    data[automationAt + 13 + 2] = 0.6f;
    const CustomizationPlan plan = decoded(data);
    const MacroLinkSpec& l = plan.macroLinks[1];
    EXPECT_EQ(l.macro, 5);
    EXPECT_EQ(l.target.kind, Target::Kind::Shared);
    EXPECT_EQ(l.target.index, 12);
    EXPECT_EQ(l.low, 0.8f);
    EXPECT_EQ(l.high, 0.2f);
    const AutomationSpec& a = plan.automations[1];
    EXPECT_EQ(a.kind, AutomationKind::Camera);
    EXPECT_EQ(a.target.kind, Target::Kind::Shared);
    EXPECT_EQ(a.target.index, 3);
    EXPECT_EQ(a.amount, -0.3f);
    EXPECT_EQ(a.everyBeats, 4);
    EXPECT_EQ(a.trigger, TriggerKind::BigMoments);
    EXPECT_EQ(a.threshold, 0.7f);
    EXPECT_EQ(a.spacingSeconds, 8.0f);
    EXPECT_EQ(a.fadeSeconds, 2.5f);
    EXPECT_TRUE(a.alternate);
    EXPECT_TRUE(a.held);
    EXPECT_EQ(a.stepCount, 3);
    EXPECT_FALSE(plan.automations[0].held);
    EXPECT_EQ(a.steps[2], 0.6f);
}

TEST(PlanCodec, KeepsCountsKindsAndIndicesInRange) {
    std::vector<float> data = carried();
    data[1] = 1e9f;
    data[2] = -4.0f;
    data[3] = kNaN;
    data[4] = std::numeric_limits<float>::infinity();
    data[kReactionsAt] = 7.0f;
    data[kReactionsAt + 3] = 1e6f;
    data[kModulatorsAt + kModulatorFloats - 1] = 0.0f;
    data[kLinksAt] = 40.0f;
    data[kAutomationsAt] = 99.0f;
    data[kAutomationsAt + 5] = 0.0f;
    const CustomizationPlan plan = decoded(data);
    EXPECT_EQ(plan.reactionCount, kMaxReactions);
    EXPECT_EQ(plan.modulatorCount, 0);
    EXPECT_EQ(plan.macroLinkCount, 0);
    EXPECT_EQ(plan.automationCount, 0);
    EXPECT_EQ(plan.reactions[0].source.kind, Source::Kind::Modulator);
    EXPECT_EQ(plan.reactions[0].target.index, 255) << "out of every array, so the graph leaves it out";
    EXPECT_EQ(plan.modulators[0].seed, 1u);
    EXPECT_EQ(plan.macroLinks[0].macro, kMacroCount) << "no Macro";
    EXPECT_EQ(plan.automations[0].kind, AutomationKind::Lane);
    EXPECT_EQ(plan.automations[0].everyBeats, 1);
}

TEST(PlanCodec, AnEncodedPlanDrivesTheGraph) {
    std::vector<float> data = carried();
    // Neutral trims, as NativePlan writes them: a gain of 0 would silence every Sound signal.
    for (size_t s = 0; s < kSoundSignalCount; ++s) data[kTrimsAt + 2 * s] = 1.0f;
    data[1] = 1.0f;
    data[kReactionsAt + 1] = static_cast<float>(SoundSignal::Bass);
    data[kReactionsAt + 2] = 1.0f;
    data[kReactionsAt + 3] = static_cast<float>(Shared::Vignette);
    data[kReactionsAt + 5] = 0.5f;
    data[kReactionsAt + 8] = 1.0f;
    ReactionGraph graph;
    SignalFrame heard;
    heard.level[static_cast<size_t>(SoundSignal::Bass)] = 1.0f;
    EXPECT_NEAR(graph.evaluate(decoded(data), heard, 1.0f / 60.0f)[Shared::Vignette], 0.5f, 1e-4f);
}

TEST(SignalFrame, MapsTheFeatureFrameOntoTheSoundSignals) {
    GeodeFeatureFrame f{};
    f.rms = 0.3f;
    f.bass = 2.0f;
    f.snare = 0.4f;
    f.drop = 1.0f;
    f.sectionBoundary = 0.7f;
    f.stereoPan = -1.0f;
    f.beat = 1.0f;
    f.macroEnergy = kNaN;
    f.bpm = 120.0f;
    f.pulseConfidence = 1.0f;
    const SignalFrame s = signalFrame(f);
    EXPECT_EQ(s[SoundSignal::Level], 0.3f);
    EXPECT_EQ(s[SoundSignal::Bass], 1.0f);
    EXPECT_EQ(s[SoundSignal::MidHit], 0.4f);
    EXPECT_EQ(s[SoundSignal::Surge], 1.0f);
    EXPECT_EQ(s[SoundSignal::Change], 0.7f);
    EXPECT_EQ(s[SoundSignal::StereoPan], 0.0f);
    EXPECT_EQ(s[SoundSignal::Beat], 1.0f);
    EXPECT_EQ(s[SoundSignal::Energy], 0.0f);
    EXPECT_EQ(s.beatSeconds, 0.5f);
    f.stereoPan = 0.0f;
    f.bpm = 0.0f;
    EXPECT_EQ(signalFrame(f)[SoundSignal::StereoPan], 0.5f);
    EXPECT_EQ(signalFrame(f).beatSeconds, 0.0f);
}

}  // namespace
}  // namespace geode::viz::mod
