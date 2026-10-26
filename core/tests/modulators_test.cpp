#include "geode/viz/Modulators.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <limits>
#include <set>

namespace geode::viz::mod {
namespace {

constexpr float kTolerance = 1e-4f;
constexpr float kFrameDt = 1.0f / 60.0f;

ModulatorSpec lfo(Wave wave, float periodSeconds) {
    ModulatorSpec s;
    s.kind = ModulatorKind::Lfo;
    s.wave = wave;
    s.periodSeconds = periodSeconds;
    return s;
}

SignalFrame playing() { return SignalFrame{}; }

SignalFrame with(SoundSignal signal, float value) {
    SignalFrame f;
    f.level[static_cast<size_t>(signal)] = value;
    return f;
}

constexpr ModulatorDrive kAsSet{};

// Runs `seconds` of time in steps of `dt` and returns the last value.
float run(Modulator& m, const ModulatorSpec& spec, float seconds, float dt, const SignalFrame& frame = playing(),
          const ModulatorDrive& drive = kAsSet) {
    float v = m.value();
    const int steps = static_cast<int>(std::lround(seconds / dt));
    for (int i = 0; i < steps; ++i) v = m.tick(spec, drive, frame, dt);
    return v;
}

TEST(Lfo, ASineWaveStartsAtZeroAndPeaksHalfwayThroughItsPeriod) {
    Modulator m;
    const ModulatorSpec s = lfo(Wave::Sine, 1.0f);
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), 0.5f, kTolerance);
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), 1.0f, kTolerance);
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), 0.5f, kTolerance);
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), 0.0f, kTolerance);
}

TEST(Lfo, EachWaveHasItsShapeAQuarterAndThreeQuartersThrough) {
    struct Case {
        Wave wave;
        float quarter;
        float threeQuarters;
    };
    for (const Case c : {Case{Wave::Triangle, 0.5f, 0.5f}, Case{Wave::Saw, 0.25f, 0.75f}, Case{Wave::Square, 0.0f, 1.0f}}) {
        Modulator m;
        const ModulatorSpec s = lfo(c.wave, 1.0f);
        EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), c.quarter, kTolerance) << static_cast<int>(c.wave);
        m.tick(s, kAsSet, playing(), 0.25f);
        EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), c.threeQuarters, kTolerance) << static_cast<int>(c.wave);
    }
}

TEST(Lfo, RateSpeedsItUpAndDepthScalesItsSwing) {
    Modulator m;
    ModulatorSpec s = lfo(Wave::Sine, 1.0f);
    s.rate = 2.0f;
    s.depth = 0.5f;
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), 0.5f, kTolerance);
}

TEST(Lfo, RateAndDepthAreKeptInTheirRanges) {
    Modulator m;
    ModulatorSpec s = lfo(Wave::Saw, 1.0f);
    s.rate = 100.0f;
    s.depth = 7.0f;
    // Held at 4x: 1/16 s is a quarter cycle. Depth held at 1.
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.0625f), 0.25f, kTolerance);
    Modulator slow;
    s.rate = 0.0f;
    // Held at a quarter speed: one second is a quarter cycle.
    EXPECT_NEAR(slow.tick(s, kAsSet, playing(), 1.0f / 16.0f) * 16.0f, 0.25f, kTolerance);
}

TEST(Lfo, WhatOthersPushOntoItsRateDepthAndPatternIsUsedInsteadOfItsOwn) {
    Modulator m;
    const ModulatorSpec s = lfo(Wave::Saw, 1.0f);
    ModulatorDrive drive;
    drive.rate = 2.0f;
    drive.depth = 0.5f;
    drive.pattern = 0.25f;
    // Half a cycle at double rate, shifted a quarter: 0.75, at half depth.
    EXPECT_NEAR(m.tick(s, drive, playing(), 0.25f), 0.375f, kTolerance);
}

TEST(Lfo, BeatSyncLocksOneCycleToTheBeatOnceItIsKnown) {
    ModulatorSpec s = lfo(Wave::Saw, 2.0f);
    s.beatSync = true;
    SignalFrame f;
    f.beatSeconds = 0.5f;
    Modulator synced;
    EXPECT_NEAR(synced.tick(s, kAsSet, f, 0.125f), 0.25f, kTolerance);
    // Before a beat is heard it runs on its own period.
    Modulator unsynced;
    EXPECT_NEAR(unsynced.tick(s, kAsSet, playing(), 0.5f), 0.25f, kTolerance);
}

TEST(Lfo, TheRandomWaveHoldsAValueForACycleAndIsTheSameForTheSameSeed) {
    ModulatorSpec s = lfo(Wave::Random, 1.0f);
    s.seed = 7;
    Modulator a;
    Modulator b;
    const float first = a.tick(s, kAsSet, playing(), 0.25f);
    EXPECT_EQ(a.tick(s, kAsSet, playing(), 0.25f), first);
    EXPECT_EQ(b.tick(s, kAsSet, playing(), 0.25f), first);
    std::set<float> seen{first};
    for (int cycle = 0; cycle < 8; ++cycle) {
        const float v = run(a, s, 1.0f, 0.25f);
        EXPECT_GE(v, 0.0f);
        EXPECT_LE(v, 1.0f);
        seen.insert(v);
    }
    EXPECT_GT(seen.size(), 4u);
}

TEST(HitEnvelope, RisesOverItsAttackWhenTheSignalHitsThenFallsOverItsRelease) {
    ModulatorSpec s;
    s.kind = ModulatorKind::HitEnvelope;
    s.signal = SoundSignal::Kick;
    s.attackSeconds = 0.1f;
    s.releaseSeconds = 0.2f;
    s.threshold = 0.5f;
    Modulator m;
    EXPECT_EQ(m.tick(s, kAsSet, with(SoundSignal::Kick, 0.3f), 0.05f), 0.0f);
    EXPECT_NEAR(m.tick(s, kAsSet, with(SoundSignal::Kick, 0.9f), 0.05f), 0.5f, kTolerance);
    EXPECT_NEAR(m.tick(s, kAsSet, with(SoundSignal::Kick, 0.9f), 0.05f), 1.0f, kTolerance);
    // Holding the signal up does not fire it again: it falls.
    EXPECT_NEAR(m.tick(s, kAsSet, with(SoundSignal::Kick, 0.9f), 0.1f), 0.5f, kTolerance);
    EXPECT_NEAR(m.tick(s, kAsSet, with(SoundSignal::Kick, 0.0f), 0.1f), 0.0f, kTolerance);
    // Once the signal has dropped below the threshold a new hit fires it again.
    EXPECT_NEAR(m.tick(s, kAsSet, with(SoundSignal::Kick, 0.8f), 0.05f), 0.5f, kTolerance);
}

TEST(HitEnvelope, AHitDuringTheFallRisesAgainFromWhereItIs) {
    ModulatorSpec s;
    s.kind = ModulatorKind::HitEnvelope;
    s.signal = SoundSignal::Hit;
    s.attackSeconds = 0.1f;
    s.releaseSeconds = 0.4f;
    Modulator m;
    m.tick(s, kAsSet, with(SoundSignal::Hit, 1.0f), 0.1f);
    EXPECT_NEAR(m.tick(s, kAsSet, with(SoundSignal::Hit, 0.0f), 0.2f), 0.5f, kTolerance);
    EXPECT_NEAR(m.tick(s, kAsSet, with(SoundSignal::Hit, 1.0f), 0.025f), 0.75f, kTolerance);
}

TEST(StepSequencer, HoldsEachStepForItsLengthAndWrapsAround) {
    ModulatorSpec s;
    s.kind = ModulatorKind::StepSequencer;
    s.steps = {0.25f, 0.75f, 0.5f};
    s.stepCount = 3;
    s.stepSeconds = 0.25f;
    Modulator m;
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.125f), 0.25f, kTolerance);
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), 0.75f, kTolerance);
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), 0.5f, kTolerance);
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), 0.25f, kTolerance);
}

TEST(StepSequencer, ThePatternShiftsWhichStepPlays) {
    ModulatorSpec s;
    s.kind = ModulatorKind::StepSequencer;
    s.steps = {0.25f, 0.75f, 0.5f};
    s.stepCount = 3;
    s.stepSeconds = 0.25f;
    ModulatorDrive drive;
    drive.pattern = 1.0f / 3.0f;
    Modulator m;
    EXPECT_NEAR(m.tick(s, drive, playing(), 0.125f), 0.75f, kTolerance);
}

TEST(StepSequencer, BeatSyncHoldsEachStepForOneBeat) {
    ModulatorSpec s;
    s.kind = ModulatorKind::StepSequencer;
    s.steps = {0.25f, 0.75f};
    s.stepCount = 2;
    s.stepSeconds = 4.0f;
    s.beatSync = true;
    SignalFrame f;
    f.beatSeconds = 0.5f;
    Modulator m;
    EXPECT_NEAR(m.tick(s, kAsSet, f, 0.25f), 0.25f, kTolerance);
    EXPECT_NEAR(m.tick(s, kAsSet, f, 0.5f), 0.75f, kTolerance);
}

TEST(StepSequencer, NoStepsGiveNothingAndStepsAreKeptInRange) {
    ModulatorSpec s;
    s.kind = ModulatorKind::StepSequencer;
    s.stepCount = 0;
    Modulator m;
    EXPECT_EQ(m.tick(s, kAsSet, playing(), kFrameDt), 0.0f);
    s.steps = {3.0f};
    s.stepCount = 99;
    Modulator over;
    const float v = over.tick(s, kAsSet, playing(), kFrameDt);
    EXPECT_GE(v, 0.0f);
    EXPECT_LE(v, 1.0f);
}

TEST(RandomDrift, WandersSmoothlyInsideZeroToOneAndRepeatsForTheSameSeed) {
    ModulatorSpec s;
    s.kind = ModulatorKind::RandomDrift;
    s.periodSeconds = 1.0f;
    s.seed = 42;
    Modulator a;
    Modulator b;
    float last = a.value();
    float low = 1.0f;
    float high = 0.0f;
    for (int i = 0; i < 600; ++i) {
        const float v = a.tick(s, kAsSet, playing(), kFrameDt);
        EXPECT_EQ(b.tick(s, kAsSet, playing(), kFrameDt), v);
        EXPECT_GE(v, 0.0f);
        EXPECT_LE(v, 1.0f);
        // Smooth: a smoothstep over one period never moves faster than 1.5 per period.
        EXPECT_LE(std::fabs(v - last), 1.5f * kFrameDt + kTolerance);
        last = v;
        low = std::min(low, v);
        high = std::max(high, v);
    }
    EXPECT_GT(high - low, 0.2f);
}

ModulatorSpec onBeat(ModulatorKind kind, int everyBeats, float glideSeconds) {
    ModulatorSpec s;
    s.kind = kind;
    s.stepCount = everyBeats;
    s.attackSeconds = glideSeconds;
    return s;
}

// One heard beat: the Beat signal for one frame, then a frame without it.
float beat(Modulator& m, const ModulatorSpec& s, float dt = kFrameDt, const ModulatorDrive& drive = kAsSet) {
    m.tick(s, drive, with(SoundSignal::Beat, 1.0f), dt);
    return m.tick(s, drive, playing(), dt);
}

TEST(ToggleOnBeat, FlipsBetweenZeroAndOneOnEveryBeat) {
    Modulator m;
    const ModulatorSpec s = onBeat(ModulatorKind::ToggleOnBeat, 1, 0.0f);
    EXPECT_EQ(m.tick(s, kAsSet, playing(), kFrameDt), 0.0f);
    EXPECT_EQ(beat(m, s), 1.0f);
    EXPECT_EQ(run(m, s, 1.0f, kFrameDt), 1.0f) << "holds between beats";
    EXPECT_EQ(beat(m, s), 0.0f);
    EXPECT_EQ(beat(m, s), 1.0f);
}

TEST(ToggleOnBeat, CountsOneBeatPerRiseOfTheBeatSignal) {
    Modulator m;
    const ModulatorSpec s = onBeat(ModulatorKind::ToggleOnBeat, 1, 0.0f);
    for (int i = 0; i < 10; ++i) m.tick(s, kAsSet, with(SoundSignal::Beat, 1.0f), kFrameDt);
    EXPECT_EQ(m.value(), 1.0f) << "a held beat is one beat";
    EXPECT_EQ(m.tick(s, kAsSet, with(SoundSignal::Beat, kBeatHeard), kFrameDt), 1.0f) << "too faint to be a beat";
    EXPECT_EQ(m.tick(s, kAsSet, with(SoundSignal::Beat, 0.02f), kFrameDt), 0.0f) << "any beat heard counts";
}

TEST(ToggleOnBeat, FlipsEveryNBeatsAndThePatternMovesWhichBeat) {
    Modulator m;
    const ModulatorSpec s = onBeat(ModulatorKind::ToggleOnBeat, 4, 0.0f);
    EXPECT_EQ(beat(m, s), 0.0f);
    EXPECT_EQ(beat(m, s), 0.0f);
    EXPECT_EQ(beat(m, s), 0.0f);
    EXPECT_EQ(beat(m, s), 1.0f);
    EXPECT_EQ(beat(m, s), 1.0f);

    Modulator shifted;
    const ModulatorDrive drive{ModulatorDrive::kOwn, ModulatorDrive::kOwn, 0.5f};
    EXPECT_EQ(beat(shifted, s, kFrameDt, drive), 0.0f);
    EXPECT_EQ(beat(shifted, s, kFrameDt, drive), 1.0f) << "half a cycle on: the second beat of four";
    EXPECT_EQ(beat(shifted, s, kFrameDt, drive), 1.0f);
}

TEST(ToggleOnBeat, GlidesOverItsGlideScaledByTheRateAndDepth) {
    Modulator m;
    ModulatorSpec s = onBeat(ModulatorKind::ToggleOnBeat, 1, 0.4f);
    m.tick(s, kAsSet, with(SoundSignal::Beat, 1.0f), 0.1f);
    EXPECT_NEAR(m.value(), 0.25f, kTolerance);
    EXPECT_NEAR(run(m, s, 0.2f, 0.1f), 0.75f, kTolerance);
    EXPECT_NEAR(run(m, s, 0.3f, 0.1f), 1.0f, kTolerance) << "lands and stops";

    Modulator fast;
    s.rate = 2.0f;
    s.depth = 0.5f;
    fast.tick(s, kAsSet, with(SoundSignal::Beat, 1.0f), 0.1f);
    EXPECT_NEAR(fast.value(), 0.25f, kTolerance) << "half the swing, twice as fast";
}

TEST(ToggleOnBeat, StandsStillWithoutBeatsAndWhenTheMusicStops) {
    Modulator m;
    const ModulatorSpec s = onBeat(ModulatorKind::ToggleOnBeat, 1, 0.0f);
    EXPECT_EQ(run(m, s, 5.0f, kFrameDt), 0.0f);
    SignalFrame stopped = with(SoundSignal::Beat, 1.0f);
    stopped.playing = false;
    EXPECT_EQ(m.tick(s, kAsSet, stopped, kFrameDt), 0.0f);
}

TEST(ToggleOnBeat, AStartOnASoundCountsTheBeatsAgain) {
    Modulator m;
    ModulatorSpec s = onBeat(ModulatorKind::ToggleOnBeat, 2, 0.0f);
    s.start = StartMode::OnSound;
    s.startSignal = SoundSignal::Kick;
    EXPECT_EQ(beat(m, s), 0.0f);
    m.tick(s, kAsSet, with(SoundSignal::Kick, 1.0f), kFrameDt);
    EXPECT_EQ(beat(m, s), 0.0f) << "the kick started the count over";
    EXPECT_EQ(beat(m, s), 1.0f);
}

TEST(RandomOnBeat, MovesToANewValueOnEachBeatAndRepeatsForTheSameSeed) {
    ModulatorSpec s = onBeat(ModulatorKind::RandomOnBeat, 1, 0.0f);
    s.seed = 7;
    Modulator a;
    Modulator b;
    std::set<float> values;
    for (int i = 0; i < 8; ++i) {
        const float va = beat(a, s);
        EXPECT_EQ(va, beat(b, s));
        EXPECT_GE(va, 0.0f);
        EXPECT_LE(va, 1.0f);
        EXPECT_EQ(run(a, s, 0.5f, kFrameDt), va) << "holds between beats";
        run(b, s, 0.5f, kFrameDt);
        values.insert(va);
    }
    EXPECT_GE(values.size(), 6u);
    ModulatorSpec other = s;
    other.seed = 8;
    Modulator c;
    Modulator d;
    EXPECT_NE(beat(c, other), beat(d, s));
}

TEST(RandomOnBeat, ChangesOnlyEveryNBeatsAndGlides) {
    ModulatorSpec s = onBeat(ModulatorKind::RandomOnBeat, 2, 0.0f);
    s.seed = 3;
    Modulator m;
    EXPECT_EQ(beat(m, s), 0.0f);
    const float first = beat(m, s);
    EXPECT_GT(first, 0.0f);
    EXPECT_EQ(beat(m, s), first);
    EXPECT_NE(beat(m, s), first);

    s.attackSeconds = 1.0f;
    s.stepCount = 1;
    Modulator gliding;
    gliding.tick(s, kAsSet, with(SoundSignal::Beat, 1.0f), 0.01f);
    EXPECT_GT(gliding.value(), 0.0f);
    EXPECT_LE(gliding.value(), 0.01f + kTolerance) << "never further than the glide allows in one frame";
}

TEST(StartMode, WhilePlayingItHoldsStillWhenTheMusicStops) {
    Modulator m;
    const ModulatorSpec s = lfo(Wave::Saw, 1.0f);
    m.tick(s, kAsSet, playing(), 0.25f);
    SignalFrame paused;
    paused.playing = false;
    EXPECT_NEAR(m.tick(s, kAsSet, paused, 0.25f), 0.25f, kTolerance);
}

TEST(StartMode, ByHandItRunsOnlyBetweenPlayAndStop) {
    ModulatorSpec s = lfo(Wave::Saw, 1.0f);
    s.start = StartMode::ByHand;
    Modulator m;
    EXPECT_EQ(m.tick(s, kAsSet, playing(), 0.25f), 0.0f);
    s.playing = true;
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), 0.25f, kTolerance);
    s.playing = false;
    EXPECT_EQ(m.tick(s, kAsSet, playing(), 0.25f), 0.0f);
    // Played again, it starts from the beginning.
    s.playing = true;
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), 0.25f, kTolerance);
}

TEST(StartMode, OnASoundItRestartsEachTimeThatSoundHits) {
    ModulatorSpec s = lfo(Wave::Saw, 1.0f);
    s.start = StartMode::OnSound;
    s.startSignal = SoundSignal::Beat;
    Modulator m;
    EXPECT_NEAR(run(m, s, 0.5f, 0.125f), 0.5f, kTolerance);
    EXPECT_NEAR(m.tick(s, kAsSet, with(SoundSignal::Beat, 1.0f), 0.125f), 0.125f, kTolerance);
    // Still hitting: no second restart until the sound has let go.
    EXPECT_NEAR(m.tick(s, kAsSet, with(SoundSignal::Beat, 1.0f), 0.125f), 0.25f, kTolerance);
}

TEST(Modulator, BrokenSettingsStillGiveAValueInZeroToOne) {
    const float nan = std::numeric_limits<float>::quiet_NaN();
    for (const ModulatorKind kind : {ModulatorKind::Lfo, ModulatorKind::HitEnvelope, ModulatorKind::StepSequencer,
                                     ModulatorKind::RandomDrift, ModulatorKind::ToggleOnBeat, ModulatorKind::RandomOnBeat}) {
        ModulatorSpec s;
        s.kind = kind;
        s.periodSeconds = nan;
        s.attackSeconds = -1.0f;
        s.releaseSeconds = nan;
        s.stepSeconds = 0.0f;
        s.stepCount = 2;
        s.steps = {nan, 1.0f};
        s.rate = nan;
        s.depth = nan;
        Modulator m;
        for (int i = 0; i < 100; ++i) {
            const float v = m.tick(s, ModulatorDrive{nan, nan, nan}, with(SoundSignal::Hit, i % 2 ? 1.0f : 0.0f), i % 3 ? kFrameDt : -1.0f);
            EXPECT_TRUE(std::isfinite(v)) << static_cast<int>(kind);
            EXPECT_GE(v, 0.0f);
            EXPECT_LE(v, 1.0f);
        }
    }
}

TEST(Modulator, ResetStartsItOver) {
    Modulator m;
    const ModulatorSpec s = lfo(Wave::Saw, 1.0f);
    run(m, s, 0.5f, 0.125f);
    m.reset();
    EXPECT_EQ(m.value(), 0.0f);
    EXPECT_NEAR(m.tick(s, kAsSet, playing(), 0.25f), 0.25f, kTolerance);
}

}  // namespace
}  // namespace geode::viz::mod
