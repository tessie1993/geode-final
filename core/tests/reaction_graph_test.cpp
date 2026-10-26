#include "geode/viz/ReactionGraph.hpp"

#include <gtest/gtest.h>

#include <algorithm>
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
    EXPECT_EQ(kSharedCount, 61u);
    EXPECT_STREQ(spec(Shared::LayerSwap).name, "LAYER_SWAP");
    EXPECT_TRUE(spec(Shared::LayerOpacity).luminous);
    EXPECT_EQ(spec(Shared::LayerInfluence).initial, 0.5f);
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

TEST(ReactionGraph, HandsLayerTwoOnAsThePlanAsks) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.layer.style = 1234u;
    plan.layer.blend = layer::Blend::Overlay;
    plan.layer.influence = layer::Influence::Displace;
    ReactionGraph graph;
    const Evaluated& out = graph.evaluate(plan, heard(SoundSignal::Bass, 0.0f), kFrameDt);
    EXPECT_EQ(out.layer, plan.layer);
    EXPECT_NEAR(out[Shared::LayerOpacity], 0.5f, kTolerance);
    EXPECT_NEAR(out[Shared::LayerSwap], 0.0f, kTolerance);
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

MacroLinkSpec link(int macro, Target target, float low, float high) {
    MacroLinkSpec l;
    l.macro = static_cast<uint8_t>(macro);
    l.target = target;
    l.low = low;
    l.high = high;
    return l;
}

float vignetteWithMacroAt(float macro, float low, float high) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.shared[static_cast<size_t>(Shared::Vignette)] = 0.4f;
    plan.macros[0] = macro;
    plan.add(link(0, Target::shared(Shared::Vignette), low, high));
    ReactionGraph graph;
    return graph.evaluate(plan, SignalFrame{}, kFrameDt)[Shared::Vignette];
}

TEST(MacroLinks, AMacroAtRestLeavesItsPropertiesAtTheirOwnValues) {
    EXPECT_NEAR(vignetteWithMacroAt(kMacroRest, 0.1f, 0.9f), 0.4f, kTolerance);
}

TEST(MacroLinks, AMacroMovesItsPropertiesTowardTheLinksLowAndHigh) {
    EXPECT_NEAR(vignetteWithMacroAt(0.0f, 0.1f, 0.9f), 0.1f, kTolerance);
    EXPECT_NEAR(vignetteWithMacroAt(0.25f, 0.1f, 0.9f), 0.25f, kTolerance);
    EXPECT_NEAR(vignetteWithMacroAt(0.75f, 0.1f, 0.9f), 0.65f, kTolerance);
    EXPECT_NEAR(vignetteWithMacroAt(1.0f, 0.1f, 0.9f), 0.9f, kTolerance);
    // A link whose low is above its high turns its property down as the Macro goes up.
    EXPECT_NEAR(vignetteWithMacroAt(1.0f, 0.9f, 0.1f), 0.1f, kTolerance);
}

TEST(MacroLinks, MatchTheKotlinReference) {
    // Evaluation.macro in :feature:customization:core, as EvaluationTest pins it.
    EXPECT_NEAR(macroLink(0.4f, 0.1f, 0.9f, 0.5f), 0.4f, kTolerance);
    EXPECT_NEAR(macroLink(0.4f, 0.1f, 0.9f, 0.0f), 0.1f, kTolerance);
    EXPECT_NEAR(macroLink(0.4f, 0.1f, 0.9f, 0.25f), 0.25f, kTolerance);
    EXPECT_NEAR(macroLink(0.4f, 0.1f, 0.9f, 2.0f), 0.9f, kTolerance);
}

TEST(MacroLinks, AReactionOnAMacroMovesItsPropertiesInTheSameFrame) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.shared[static_cast<size_t>(Shared::Vignette)] = 0.2f;
    plan.add(link(0, Target::shared(Shared::Vignette), 0.0f, 1.0f));
    plan.add(fromSound(SoundSignal::Bass, Target::macro(0), 0.5f));
    // A Reaction listed after the link pushes from where the Macro put the property.
    plan.add(fromSound(SoundSignal::Bass, Target::shared(Shared::Warp), 0.3f));
    plan.add(link(0, Target::shared(Shared::Warp), 0.0f, 0.5f));
    ReactionGraph graph;
    const Evaluated& out = graph.evaluate(plan, heard(SoundSignal::Bass, 1.0f), kFrameDt);
    EXPECT_NEAR(out.macros[0], 1.0f, kTolerance);
    EXPECT_NEAR(out[Shared::Vignette], 1.0f, kTolerance);
    EXPECT_NEAR(out[Shared::Warp], 0.8f, kTolerance);
}

TEST(MacroLinks, ALinkOntoAMacroOrFromNoMacroMovesNothing) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.shared[static_cast<size_t>(Shared::Vignette)] = 0.3f;
    plan.macros[0] = 1.0f;
    plan.add(link(0, Target::macro(1), 0.0f, 1.0f));
    plan.add(link(static_cast<int>(kMacroCount), Target::shared(Shared::Vignette), 0.0f, 1.0f));
    ReactionGraph graph;
    const Evaluated& out = graph.evaluate(plan, SignalFrame{}, kFrameDt);
    EXPECT_NEAR(out.macros[1], kMacroRest, kTolerance);
    EXPECT_NEAR(out[Shared::Vignette], 0.3f, kTolerance);
}

TEST(MacroLinks, LinksPastThePlansCountAreLeftOut) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.macros[0] = 1.0f;
    plan.add(link(0, Target::shared(Shared::Vignette), 0.0f, 1.0f));
    plan.macroLinkCount = 0;
    ReactionGraph graph;
    EXPECT_EQ(graph.evaluate(plan, SignalFrame{}, kFrameDt)[Shared::Vignette], 0.0f);
}

TEST(CustomizationPlan, RefusesLinksAndAutomationsPastItsSize) {
    CustomizationPlan plan;
    for (int i = 0; i < kMaxMacroLinks; ++i) EXPECT_EQ(plan.add(MacroLinkSpec{}), i);
    EXPECT_EQ(plan.add(MacroLinkSpec{}), -1);
    for (int i = 0; i < kMaxAutomations; ++i) EXPECT_EQ(plan.add(AutomationSpec{}), i);
    EXPECT_EQ(plan.add(AutomationSpec{}), -1);
}

// A tenth of a second a frame: five frames a beat at a reported 0.5 s beat.
constexpr float kAutomationDt = 0.1f;

SignalFrame atTempo(float beatSeconds) {
    SignalFrame f;
    f.beatSeconds = beatSeconds;
    return f;
}

AutomationSpec palettePerBeat() {
    AutomationSpec a;
    a.kind = AutomationKind::Lane;
    a.target = Target::shared(Shared::Palette);
    a.everyBeats = 1;
    a.steps[0] = 0.0f;
    a.steps[1] = 0.5f;
    a.stepCount = 2;
    return a;
}

// The Palette after `frames` frames of `heard`.
float paletteAfter(int frames, const SignalFrame& heard) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.add(palettePerBeat());
    ReactionGraph graph;
    float palette = -1.0f;
    for (int i = 0; i < frames; ++i) palette = graph.evaluate(plan, heard, kAutomationDt)[Shared::Palette];
    return palette;
}

TEST(Automations, ASequenceStepsItsTargetOnTheBeat) {
    EXPECT_EQ(paletteAfter(1, atTempo(0.5f)), 0.0f);
    EXPECT_EQ(paletteAfter(7, atTempo(0.5f)), 10.0f);
    EXPECT_EQ(paletteAfter(12, atTempo(0.5f)), 0.0f);
    // Twice the tempo, twice as fast.
    EXPECT_EQ(paletteAfter(4, atTempo(0.25f)), 10.0f);
}

TEST(Automations, HoldUntilTheLiveAnalysisReportsATempo) {
    // No tempo assumed: with none reported, nothing beat-timed moves, however long it plays.
    EXPECT_EQ(paletteAfter(7, atTempo(0.0f)), 0.0f);
    EXPECT_EQ(paletteAfter(40, atTempo(0.0f)), 0.0f);
    EXPECT_EQ(paletteAfter(40, atTempo(-0.5f)), 0.0f);
    EXPECT_EQ(paletteAfter(40, atTempo(std::numeric_limits<float>::quiet_NaN())), 0.0f);
    EXPECT_EQ(paletteAfter(40, atTempo(std::numeric_limits<float>::infinity())), 0.0f);
}

TEST(Automations, FollowTheReportedTempoOnceItArrives) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.add(palettePerBeat());
    ReactionGraph graph;
    float palette = -1.0f;
    for (int i = 0; i < 20; ++i) palette = graph.evaluate(plan, atTempo(0.0f), kAutomationDt)[Shared::Palette];
    EXPECT_EQ(palette, 0.0f) << "held before any tempo";
    // The reported tempo starts the count from where it held: a beat later, the next step.
    for (int i = 0; i < 4; ++i) palette = graph.evaluate(plan, atTempo(0.5f), kAutomationDt)[Shared::Palette];
    EXPECT_EQ(palette, 0.0f) << "not yet a beat in";
    for (int i = 0; i < 2; ++i) palette = graph.evaluate(plan, atTempo(0.5f), kAutomationDt)[Shared::Palette];
    EXPECT_EQ(palette, 10.0f);
    // A lost tempo holds again, on the step it reached.
    for (int i = 0; i < 20; ++i) palette = graph.evaluate(plan, atTempo(0.0f), kAutomationDt)[Shared::Palette];
    EXPECT_EQ(palette, 10.0f);
}

TEST(Automations, WaitWhileTheMusicIsStopped) {
    SignalFrame stopped = atTempo(0.5f);
    stopped.playing = false;
    EXPECT_EQ(paletteAfter(7, stopped), 0.0f);
}

TEST(Automations, ASequenceWithNoStepsOrPastThePlanMovesNothing) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    AutomationSpec empty = palettePerBeat();
    empty.stepCount = 0;
    plan.add(empty);
    AutomationSpec dropped = palettePerBeat();
    dropped.target = Target::shared(Shared::Vignette);
    dropped.steps[0] = 0.7f;
    plan.add(dropped);
    plan.automationCount = 1;
    ReactionGraph graph;
    const Evaluated& out = graph.evaluate(plan, atTempo(0.5f), kAutomationDt);
    EXPECT_EQ(out[Shared::Palette], 0.0f);
    EXPECT_EQ(out[Shared::Vignette], 0.0f);
}

TEST(MacroDrift, AModulatorLinkedToAMacroMovesItAroundThePersonsValue) {
    // No autopilot (#41): a Macro drifts only through a Modulator linked to it (Evaluation.drift).
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.macros[0] = 0.6f;
    plan.shared[static_cast<size_t>(Shared::Vignette)] = 0.5f;
    plan.add(link(0, Target::shared(Shared::Vignette), 0.0f, 1.0f));
    const int lfo = plan.add(saw(1.0f));
    plan.add(fromModulator(lfo, Target::macro(0), 0.5f));
    ReactionGraph graph;
    float low = 1.0f;
    float high = 0.0f;
    for (int i = 0; i < 60; ++i) {
        const Evaluated& out = graph.evaluate(plan, SignalFrame{}, kFrameDt);
        low = std::min(low, out.macros[0]);
        high = std::max(high, out.macros[0]);
        EXPECT_NEAR(out[Shared::Vignette], macroLink(0.5f, 0.0f, 1.0f, out.macros[0]), kTolerance);
    }
    EXPECT_LT(low, 0.6f - 0.2f) << "below the person's value, not only above it";
    EXPECT_GT(high, 0.6f + 0.2f);
    EXPECT_GE(low, 0.6f - 0.25f - kTolerance);
    EXPECT_LE(high, 0.6f + 0.25f + kTolerance);
}

TEST(MacroDrift, MatchesTheKotlinReference) {
    ReactionShape shape;
    shape.amount = 0.4f;
    EXPECT_NEAR(drift(0.5f, 0.5f, shape), 0.5f, kTolerance);
    EXPECT_NEAR(drift(0.5f, 1.0f, shape), 0.7f, kTolerance);
    EXPECT_NEAR(drift(0.5f, 0.0f, shape), 0.3f, kTolerance);
    shape.invert = true;
    EXPECT_NEAR(drift(0.5f, 0.0f, shape), 0.7f, kTolerance);
    shape.invert = false;
    shape.high = 0.6f;
    EXPECT_NEAR(drift(0.5f, 1.0f, shape), 0.6f, kTolerance);
    shape.high = 1.0f;
    shape.low = 0.45f;
    EXPECT_NEAR(drift(0.5f, 0.0f, shape), 0.45f, kTolerance);
    shape.low = 0.0f;
    shape.curve = Curve::Exponential;
    EXPECT_NEAR(drift(0.5f, 0.5f, shape), 0.4f, kTolerance);
    EXPECT_EQ(drift(std::numeric_limits<float>::quiet_NaN(), 1.0f, shape), 0.0f);
}

TEST(MacroDrift, ASoundSignalOnAMacroStillOnlyPushes) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.add(fromSound(SoundSignal::Bass, Target::macro(1), 0.4f));
    ReactionGraph graph;
    EXPECT_NEAR(graph.evaluate(plan, heard(SoundSignal::Bass, 0.0f), kFrameDt).macros[1], 0.5f, kTolerance);
}

AutomationSpec cadence(AutomationKind kind, int everyBeats) {
    AutomationSpec a;
    a.kind = kind;
    a.everyBeats = everyBeats;
    a.trigger = TriggerKind::EveryBeats;
    return a;
}

AutomationSpec onBigMoments(AutomationKind kind, float threshold, float spacingSeconds) {
    AutomationSpec a;
    a.kind = kind;
    a.trigger = TriggerKind::BigMoments;
    a.threshold = threshold;
    a.spacingSeconds = spacingSeconds;
    return a;
}

SignalFrame big(SoundSignal signal, float level = 1.0f) {
    SignalFrame f = atTempo(0.5f);
    f.level[static_cast<size_t>(signal)] = level;
    return f;
}

TEST(Automations, StyleSwitchingIsDueEveryNBeatsPastTheFirst) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.add(cadence(AutomationKind::StyleSwitch, 2));
    ReactionGraph graph;
    EXPECT_EQ(graph.evaluate(plan, atTempo(0.5f), kAutomationDt).stylesDue, 0u);
    // Beats 2 and 4 are due; a big moment is not, on this trigger.
    for (int i = 1; i < 25; ++i) graph.evaluate(plan, atTempo(0.5f), kAutomationDt);
    EXPECT_EQ(graph.evaluate(plan, big(SoundSignal::Surge), 0.0f).stylesDue, 2u);
    graph.reset();
    EXPECT_EQ(graph.evaluate(plan, atTempo(0.5f), 0.0f).stylesDue, 0u);
}

TEST(Automations, StyleSwitchingHoldsUntilATempoIsReported) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.add(cadence(AutomationKind::StyleSwitch, 1));
    ReactionGraph graph;
    for (int i = 0; i < 100; ++i) graph.evaluate(plan, atTempo(0.0f), kAutomationDt);
    EXPECT_EQ(graph.evaluate(plan, atTempo(0.0f), kAutomationDt).stylesDue, 0u);
}

TEST(Automations, ABigMomentFiresOncePerRiseAboveItsThreshold) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.add(onBigMoments(AutomationKind::StyleSwitch, 0.7f, 0.0f));
    ReactionGraph graph;
    for (int i = 0; i < 25; ++i) graph.evaluate(plan, atTempo(0.5f), kAutomationDt);
    EXPECT_EQ(graph.evaluate(plan, atTempo(0.5f), 0.0f).stylesDue, 0u) << "not on the beat";
    EXPECT_EQ(graph.evaluate(plan, big(SoundSignal::Surge, 0.69f), 0.0f).stylesDue, 0u) << "below its threshold";
    EXPECT_EQ(graph.evaluate(plan, big(SoundSignal::Surge, 0.7f), 0.0f).stylesDue, 1u);
    EXPECT_EQ(graph.evaluate(plan, big(SoundSignal::Surge), 0.0f).stylesDue, 1u) << "once per moment";
    graph.evaluate(plan, atTempo(0.5f), 0.0f);
    EXPECT_EQ(graph.evaluate(plan, big(SoundSignal::Change), 0.0f).stylesDue, 2u);
    // Big moments need no tempo.
    graph.evaluate(plan, atTempo(0.0f), 0.0f);
    EXPECT_EQ(graph.evaluate(plan, [] { SignalFrame f; f.level[static_cast<size_t>(SoundSignal::Surge)] = 1.0f; return f; }(), 0.0f).stylesDue, 3u);
}

TEST(Automations, TheSpacingHoldsTheNextMoveBack) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.add(onBigMoments(AutomationKind::StyleSwitch, 0.5f, 1.0f));
    ReactionGraph graph;
    EXPECT_EQ(graph.evaluate(plan, big(SoundSignal::Surge), kAutomationDt).stylesDue, 1u);
    graph.evaluate(plan, atTempo(0.5f), kAutomationDt);
    EXPECT_EQ(graph.evaluate(plan, big(SoundSignal::Surge), kAutomationDt).stylesDue, 1u) << "0.2 s after the last";
    for (int i = 0; i < 8; ++i) graph.evaluate(plan, atTempo(0.5f), kAutomationDt);
    EXPECT_EQ(graph.evaluate(plan, big(SoundSignal::Surge), kAutomationDt).stylesDue, 2u) << "a second on";
}

TEST(Automations, APaletteChangeCrossfadesIntoTheNextPaletteAndWrapsRound) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.shared[static_cast<size_t>(Shared::Palette)] = toUnit(Shared::Palette, 20.0f);
    AutomationSpec palette = cadence(AutomationKind::Palette, 4);
    palette.fadeSeconds = 1.0f;
    plan.add(palette);
    ReactionGraph graph;
    EXPECT_EQ(graph.evaluate(plan, atTempo(0.5f), kAutomationDt)[Shared::Palette], 20.0f);
    // Beat 4 lands on the 20th frame: the fade starts from the old palette into the next.
    for (int i = 1; i < 19; ++i) graph.evaluate(plan, atTempo(0.5f), kAutomationDt);
    const Evaluated& start = graph.evaluate(plan, atTempo(0.5f), kAutomationDt);
    EXPECT_EQ(start[Shared::Palette], 20.0f);
    EXPECT_EQ(start[Shared::Palette2], 0.0f);
    EXPECT_NEAR(start[Shared::PaletteMix], 0.0f, kTolerance);
    for (int i = 0; i < 4; ++i) graph.evaluate(plan, atTempo(0.5f), kAutomationDt);
    const Evaluated& half = graph.evaluate(plan, atTempo(0.5f), kAutomationDt);
    EXPECT_NEAR(half[Shared::PaletteMix], 0.5f, 1e-3f);
    for (int i = 0; i < 5; ++i) graph.evaluate(plan, atTempo(0.5f), kAutomationDt);
    const Evaluated& done = graph.evaluate(plan, atTempo(0.5f), kAutomationDt);
    EXPECT_EQ(done[Shared::Palette], 0.0f) << "past the last palette, round to the first";
    EXPECT_NEAR(done[Shared::PaletteMix], 0.0f, kTolerance);
}

TEST(Automations, APaletteFadeNeverJumps) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    AutomationSpec palette = onBigMoments(AutomationKind::Palette, 0.5f, 0.0f);
    palette.fadeSeconds = 1.0f;
    plan.add(palette);
    ReactionGraph graph;
    float last = graph.evaluate(plan, big(SoundSignal::Surge), kFrameDt)[Shared::PaletteMix];
    for (int i = 0; i < 59; ++i) {
        const float mix = graph.evaluate(plan, atTempo(0.5f), kFrameDt)[Shared::PaletteMix];
        EXPECT_LE(std::abs(mix - last), 1.5f * kFrameDt + kTolerance);
        last = mix;
    }
}

TEST(Automations, ACameraMoveSwellsOverItsSpanOnTopOfTheCameraSetting) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    AutomationSpec move = cadence(AutomationKind::Camera, 2);
    move.target = Target::shared(Shared::PushIn);
    move.amount = 0.4f;
    plan.add(move);
    ReactionGraph graph;
    // Half a beat in (a beat a second): a quarter of the way through its two-beat span, sin^2 = 0.5.
    float pushIn = 0.0f;
    for (int i = 0; i < 5; ++i) pushIn = graph.evaluate(plan, atTempo(1.0f), kAutomationDt)[Shared::PushIn];
    EXPECT_NEAR(pushIn, 0.2f, 1e-3f);
    for (int i = 0; i < 5; ++i) pushIn = graph.evaluate(plan, atTempo(1.0f), kAutomationDt)[Shared::PushIn];
    EXPECT_NEAR(pushIn, 0.4f, 1e-3f) << "the top of the swell, a beat in";
    for (int i = 0; i < 10; ++i) pushIn = graph.evaluate(plan, atTempo(1.0f), kAutomationDt)[Shared::PushIn];
    EXPECT_NEAR(pushIn, 0.0f, 1e-3f) << "still again at the end of its span";
}

TEST(Automations, AnAlternatingCameraMoveSwingsTheOtherWayEveryOtherSpan) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    AutomationSpec orbit = cadence(AutomationKind::Camera, 1);
    orbit.target = Target::shared(Shared::OrbitYaw);
    orbit.amount = 0.25f;
    orbit.alternate = true;
    plan.add(orbit);
    ReactionGraph graph;
    float yaw = 0.0f;
    // Half a beat in (beat 0), then half a beat into beat 1.
    for (int i = 0; i < 5; ++i) yaw = graph.evaluate(plan, atTempo(1.0f), kAutomationDt)[Shared::OrbitYaw];
    EXPECT_NEAR(yaw, 0.5f, 1e-3f);
    for (int i = 0; i < 10; ++i) yaw = graph.evaluate(plan, atTempo(1.0f), kAutomationDt)[Shared::OrbitYaw];
    EXPECT_NEAR(yaw, -0.5f, 1e-3f);
}

TEST(Automations, ACameraMoveWaitsForATempoAndNeverJumps) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    AutomationSpec move = cadence(AutomationKind::Camera, 1);
    move.target = Target::shared(Shared::Sway);
    move.amount = 1.0f;
    plan.add(move);
    ReactionGraph graph;
    EXPECT_EQ(graph.evaluate(plan, atTempo(0.0f), kFrameDt)[Shared::Sway], 0.0f);
    float last = 0.0f;
    for (int i = 0; i < 120; ++i) {
        const float sway = graph.evaluate(plan, atTempo(0.5f), kFrameDt)[Shared::Sway];
        // Eased over the beat: never more than pi / beat per second (0.105 a frame at 0.5 s).
        EXPECT_LE(std::abs(sway - last), 0.11f);
        last = sway;
    }
}

TEST(Automations, ALuminousSequenceStillRisesNoFasterThanTheNoFlashMinimum) {
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.shared[static_cast<size_t>(Shared::Glow)] = 0.0f;
    AutomationSpec strobe;
    strobe.kind = AutomationKind::Lane;
    strobe.target = Target::shared(Shared::Glow);
    strobe.everyBeats = 1;
    strobe.steps[0] = 1.0f;
    strobe.stepCount = 1;
    plan.add(strobe);
    ReactionGraph graph;
    const float first = graph.evaluate(plan, atTempo(0.5f), kFrameDt)[Shared::Glow];
    EXPECT_LE(toUnit(Shared::Glow, first), kFrameDt / kNoFlashMinRiseSeconds + kTolerance);
}

}  // namespace
}  // namespace geode::viz::mod
