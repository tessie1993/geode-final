#include "geode/viz/ReactionGraph.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <limits>

namespace geode::viz::mod {
namespace {

constexpr float kTolerance = 1e-4f;
constexpr float kFrameDt = 1.0f / 60.0f;

SignalFrame heard(SoundSignal signal, float raw) {
    SignalFrame f;
    f.level[static_cast<size_t>(signal)] = raw;
    return f;
}

ReactionSpec fromSound(SoundSignal signal, Target target, float amount = 1.0f) {
    ReactionSpec r;
    r.source = Source::sound(signal);
    r.target = target;
    r.shape.amount = amount;
    return r;
}

ReactionSpec fromModulator(int modulator, Target target, float amount = 1.0f) {
    ReactionSpec r;
    r.source = Source::modulator(modulator);
    r.target = target;
    r.shape.amount = amount;
    return r;
}

ModulatorSpec saw(float periodSeconds) {
    ModulatorSpec m;
    m.kind = ModulatorKind::Lfo;
    m.wave = Wave::Saw;
    m.periodSeconds = periodSeconds;
    return m;
}

TEST(SharedCore, TheTableFollowsTheKotlinModel) {
    EXPECT_EQ(kSharedCount, 58u);
    EXPECT_STREQ(spec(Shared::Zoom).name, "ZOOM");
    EXPECT_STREQ(spec(Shared::Invert).name, "INVERT");
    EXPECT_EQ(spec(Shared::Brightness).min, 0.2f);
    EXPECT_EQ(spec(Shared::Brightness).max, 1.25f);
    EXPECT_TRUE(spec(Shared::Brightness).luminous);
    EXPECT_TRUE(spec(Shared::Glow).luminous);
    EXPECT_FALSE(spec(Shared::Vignette).luminous);
    EXPECT_EQ(spec(Shared::Glitch).max, 0.25f);
    EXPECT_EQ(spec(Shared::Symmetry).kind, ValueKind::Stepped);
}

TEST(SharedCore, UnitAndNativeValuesConvertBothWays) {
    EXPECT_NEAR(toUnit(Shared::Zoom, 1.65f), 0.5f, kTolerance);
    EXPECT_NEAR(fromUnit(Shared::Zoom, 0.5f), 1.65f, kTolerance);
    EXPECT_EQ(fromUnit(Shared::Palette, 0.52f), 10.0f);
    EXPECT_EQ(fromUnit(Shared::Mirror, 0.49f), 0.0f);
    EXPECT_EQ(fromUnit(Shared::Mirror, 0.5f), 1.0f);
    EXPECT_EQ(fromUnit(Shared::Zoom, 3.0f), 3.0f);
    EXPECT_EQ(toUnit(Shared::Zoom, std::numeric_limits<float>::quiet_NaN()), toUnit(Shared::Zoom, 1.0f));
}

TEST(ReactionGraph, WithNoReactionsEverythingSitsAtItsOwnValue) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.shared[static_cast<size_t>(Shared::Vignette)] = 0.3f;
    ReactionGraph graph;
    const Evaluated& out = graph.evaluate(plan, heard(SoundSignal::Bass, 1.0f), kFrameDt);
    EXPECT_NEAR(out[Shared::Vignette], 0.3f, kTolerance);
    EXPECT_NEAR(out[Shared::Zoom], 1.0f, kTolerance);
    EXPECT_NEAR(out.macros[0], 0.5f, kTolerance);
}

TEST(ReactionGraph, ASoundSignalIsNormalisedThenPushesItsTarget) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.trims[static_cast<size_t>(SoundSignal::Bass)] = SignalTrim{2.0f, 0.0f};
    plan.add(fromSound(SoundSignal::Bass, Target::shared(Shared::Vignette), 0.5f));
    ReactionGraph graph;
    EXPECT_NEAR(graph.evaluate(plan, heard(SoundSignal::Bass, 0.5f), kFrameDt)[Shared::Vignette], 0.5f, kTolerance);
    EXPECT_NEAR(graph.evaluate(plan, heard(SoundSignal::Bass, 0.0f), kFrameDt)[Shared::Vignette], 0.0f, kTolerance);
}

TEST(ReactionGraph, ReactionsOnOneTargetApplyInTurnEachInsideItsOwnRange) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    ReactionSpec first = fromSound(SoundSignal::Level, Target::shared(Shared::Vignette), 0.5f);
    ReactionSpec second = fromSound(SoundSignal::Level, Target::shared(Shared::Vignette), 0.5f);
    second.shape.high = 0.8f;
    plan.add(first);
    plan.add(second);
    ReactionGraph graph;
    EXPECT_NEAR(graph.evaluate(plan, heard(SoundSignal::Level, 1.0f), kFrameDt)[Shared::Vignette], 0.8f, kTolerance);
}

TEST(ReactionGraph, RiseAndFallSlowTheInputDown) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    ReactionSpec r = fromSound(SoundSignal::Hit, Target::shared(Shared::Vignette));
    r.riseSeconds = 0.1f;
    plan.add(r);
    ReactionGraph graph;
    EXPECT_NEAR(graph.evaluate(plan, heard(SoundSignal::Hit, 1.0f), 0.05f)[Shared::Vignette], 0.5f, kTolerance);
}

TEST(ReactionGraph, ALuminousTargetAlwaysRisesOverTheNoFlashMinimumWhateverItIsHanded) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.add(fromSound(SoundSignal::Hit, Target::shared(Shared::Glow)));
    ReactionGraph graph;
    // Glow is 0..1 from 0. With no rise asked for, one frame still only moves 1/60 / 0.15 of the way.
    EXPECT_NEAR(graph.evaluate(plan, heard(SoundSignal::Hit, 1.0f), kFrameDt)[Shared::Glow], kFrameDt / kNoFlashMinRiseSeconds,
                kTolerance);
    float glow = 0.0f;
    for (int i = 0; i < 9; ++i) glow = graph.evaluate(plan, heard(SoundSignal::Hit, 1.0f), kFrameDt)[Shared::Glow];
    EXPECT_NEAR(glow, 1.0f, kTolerance);
    // Falling is not held back.
    EXPECT_NEAR(graph.evaluate(plan, heard(SoundSignal::Hit, 0.0f), kFrameDt)[Shared::Glow], 0.0f, kTolerance);
}

TEST(ReactionGraph, SteppedTargetsLandOnAStepAndSwitchesOnOrOff) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.add(fromSound(SoundSignal::Level, Target::shared(Shared::Palette), 0.5f));
    plan.add(fromSound(SoundSignal::Level, Target::shared(Shared::Mirror), 0.6f));
    ReactionGraph graph;
    const Evaluated& out = graph.evaluate(plan, heard(SoundSignal::Level, 1.0f), kFrameDt);
    EXPECT_EQ(out[Shared::Palette], 10.0f);
    EXPECT_EQ(out[Shared::Mirror], 1.0f);
}

TEST(ReactionGraph, ReactionsMoveExtrasAndMacrosToo) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.extras[3] = 0.2f;
    plan.add(fromSound(SoundSignal::Level, Target::extra(3), 0.3f));
    plan.add(fromSound(SoundSignal::Level, Target::macro(2), 0.3f));
    ReactionGraph graph;
    const Evaluated& out = graph.evaluate(plan, heard(SoundSignal::Level, 1.0f), kFrameDt);
    EXPECT_NEAR(out.extras[3], 0.5f, kTolerance);
    EXPECT_NEAR(out.macros[2], 0.8f, kTolerance);
}

TEST(ReactionGraph, AModulatorNoReactionListensToDoesNotRun) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.add(saw(1.0f));
    ReactionGraph graph;
    const Evaluated& out = graph.evaluate(plan, SignalFrame{}, 0.25f);
    EXPECT_FALSE(out.running[0]);
    EXPECT_EQ(out.modulators[0], 0.0f);
}

TEST(ReactionGraph, ALinkedModulatorRunsAndMovesItsTarget) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    const int m = plan.add(saw(1.0f));
    plan.add(fromModulator(m, Target::shared(Shared::Vignette)));
    ReactionGraph graph;
    const Evaluated& out = graph.evaluate(plan, SignalFrame{}, 0.05f);
    EXPECT_TRUE(out.running[0]);
    EXPECT_NEAR(out.modulators[0], 0.05f, kTolerance);
    EXPECT_NEAR(out[Shared::Vignette], 0.05f, kTolerance);
}

TEST(ReactionGraph, AModulatorStartedByHandRunsOnlyWhilePlaying) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    ModulatorSpec spec = saw(1.0f);
    spec.start = StartMode::ByHand;
    const int m = plan.add(spec);
    plan.add(fromModulator(m, Target::shared(Shared::Vignette)));
    ReactionGraph graph;
    EXPECT_EQ(graph.evaluate(plan, SignalFrame{}, 0.05f)[Shared::Vignette], 0.0f);
    plan.modulators[static_cast<size_t>(m)].playing = true;
    EXPECT_NEAR(graph.evaluate(plan, SignalFrame{}, 0.05f)[Shared::Vignette], 0.05f, kTolerance);
}

TEST(ReactionGraph, AReactionOnAModulatorsDepthTakesHoldOnTheNextFrame) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    const int m = plan.add(saw(1.0f));
    plan.add(fromModulator(m, Target::shared(Shared::Vignette)));
    ReactionSpec quieten = fromSound(SoundSignal::Level, Target::modulator(m, ModulatorField::Depth));
    quieten.shape.invert = true;
    plan.add(quieten);
    ReactionGraph graph;
    // This frame the Modulator still runs at its own depth...
    EXPECT_GT(graph.evaluate(plan, heard(SoundSignal::Level, 1.0f), 0.05f).modulators[0], 0.0f);
    // ...from the next its depth is 0.
    EXPECT_EQ(graph.evaluate(plan, heard(SoundSignal::Level, 1.0f), 0.05f).modulators[0], 0.0f);
}

TEST(ReactionGraph, AReactionOnAModulatorsRateSpeedsItUp) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    const int m = plan.add(saw(1.0f));
    plan.add(fromModulator(m, Target::shared(Shared::Vignette)));
    plan.add(fromSound(SoundSignal::Level, Target::modulator(m, ModulatorField::Rate)));
    ReactionGraph graph;
    graph.evaluate(plan, heard(SoundSignal::Level, 1.0f), 0.05f);
    // From the second frame it runs at the top rate, 4x: 0.05 at 1x, then 0.2.
    EXPECT_NEAR(graph.evaluate(plan, heard(SoundSignal::Level, 1.0f), 0.05f).modulators[0], 0.25f, kTolerance);
}

TEST(ReactionGraph, ALoopBetweenModulatorsFeedsBackButStaysBounded) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    ModulatorSpec a = saw(0.05f);
    ModulatorSpec b = saw(0.05f);
    b.wave = Wave::Square;
    const int ma = plan.add(a);
    const int mb = plan.add(b);
    // Each drives the other's rate, depth and pattern, and both drive every luminous property.
    for (const ModulatorField field : {ModulatorField::Rate, ModulatorField::Depth, ModulatorField::Pattern}) {
        plan.add(fromModulator(ma, Target::modulator(mb, field)));
        plan.add(fromModulator(mb, Target::modulator(ma, field)));
    }
    for (size_t p = 0; p < kSharedCount; ++p) {
        plan.add(fromModulator(ma, Target::shared(static_cast<Shared>(p))));
        plan.add(fromModulator(mb, Target::shared(static_cast<Shared>(p))));
    }
    ReactionGraph graph;
    uint32_t noise = 12345u;
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    for (int frame = 0; frame < 20000; ++frame) {
        noise = noise * 1664525u + 1013904223u;
        SignalFrame heardNow;
        for (size_t s = 0; s < kSoundSignalCount; ++s) heardNow.level[s] = static_cast<float>((noise >> (s + 8)) & 0xFF) / 64.0f;
        if (frame % 97 == 0) heardNow.level[0] = nan;
        if (frame % 89 == 0) heardNow.level[1] = inf;
        const float dt = frame % 1000 == 0 ? 5.0f : static_cast<float>(noise & 0x3F) / 2000.0f;
        const Evaluated& out = graph.evaluate(plan, heardNow, dt);
        for (size_t p = 0; p < kSharedCount; ++p) {
            const SharedSpec& s = sharedSpecs()[p];
            ASSERT_TRUE(std::isfinite(out.shared[p])) << s.name;
            ASSERT_LE(out.shared[p], s.max) << s.name;
            ASSERT_LE(s.min, out.shared[p]) << s.name;
        }
        for (const float m : out.modulators) {
            ASSERT_TRUE(std::isfinite(m));
            ASSERT_LE(m, 1.0f);
            ASSERT_LE(0.0f, m);
        }
    }
}

TEST(ReactionGraph, AnythingPointingOutsideThePlanIsLeftOut) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.add(fromModulator(5, Target::shared(Shared::Vignette)));
    plan.add(fromSound(static_cast<SoundSignal>(200), Target::shared(Shared::Vignette)));
    plan.add(fromSound(SoundSignal::Level, Target::shared(static_cast<Shared>(250))));
    plan.add(fromSound(SoundSignal::Level, Target::extra(99)));
    plan.add(fromSound(SoundSignal::Level, Target::modulator(7, ModulatorField::Rate)));
    plan.reactionCount = kMaxReactions + 10;
    ReactionGraph graph;
    const Evaluated& out = graph.evaluate(plan, heard(SoundSignal::Level, 1.0f), kFrameDt);
    EXPECT_EQ(out[Shared::Vignette], 0.0f);
}

TEST(ReactionGraph, TheSamePlanAndSoundGiveTheSameFramesAfterAReset) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    ModulatorSpec drift;
    drift.kind = ModulatorKind::RandomDrift;
    drift.periodSeconds = 0.3f;
    drift.seed = 9;
    const int m = plan.add(drift);
    plan.add(fromModulator(m, Target::shared(Shared::Warp)));
    ReactionGraph graph;
    float first[50];
    for (float& v : first) v = graph.evaluate(plan, SignalFrame{}, kFrameDt)[Shared::Warp];
    graph.reset();
    for (const float v : first) EXPECT_EQ(graph.evaluate(plan, SignalFrame{}, kFrameDt)[Shared::Warp], v);
}

}  // namespace
}  // namespace geode::viz::mod
