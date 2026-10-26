#include <gtest/gtest.h>

#include <cmath>
#include <limits>
#include <vector>

#include "geode/viz/FeatureBus.hpp"

namespace geode::viz {
namespace {

namespace fb = featurebus;

constexpr float kDt = 1.0f / 60.0f;
constexpr float kTolerance = 1e-5f;

GeodeFeatureFrame quiet() { return GeodeFeatureFrame{}; }

// A frame from a tracker that is sure of its tempo and its bar.
GeodeFeatureFrame locked() {
    GeodeFeatureFrame f{};
    f.pulseConfidence = 1.0f;
    f.tempoStability = 1.0f;
    f.downbeatConfidence = 1.0f;
    return f;
}

void run(FeatureBus& bus, const GeodeFeatureFrame& f, float seconds) {
    const int frames = static_cast<int>(std::lround(seconds / kDt));
    for (int i = 0; i < frames; ++i) bus.step(f, kDt);
}

// ---- the hit envelope ------------------------------------------------------------------------------

TEST(FeatureBusHit, RisesOverTheAttackInsteadOfStepping) {
    fb::Hit hit;
    EXPECT_NEAR(hit.step(1.0f, 0.01f, 0.04f, 5.0f), 0.25f, kTolerance);
    EXPECT_NEAR(hit.step(0.0f, 0.01f, 0.04f, 5.0f), 0.5f, kTolerance);
    EXPECT_NEAR(hit.step(0.0f, 0.01f, 0.04f, 5.0f), 0.75f, kTolerance);
    EXPECT_NEAR(hit.step(0.0f, 0.01f, 0.04f, 5.0f), 1.0f, kTolerance);
    EXPECT_NEAR(hit.value(), 1.0f, kTolerance);
}

TEST(FeatureBusHit, RisesToItsStrengthThenDecaysAtItsRate) {
    fb::Hit hit;
    hit.step(0.5f, 0.1f, 0.05f, 2.0f);
    EXPECT_NEAR(hit.value(), 0.5f, kTolerance);
    EXPECT_NEAR(hit.step(0.0f, 0.1f, 0.05f, 2.0f), 0.5f * std::exp(-0.2f), kTolerance);
    EXPECT_NEAR(hit.step(0.0f, 0.1f, 0.05f, 2.0f), 0.5f * std::exp(-0.4f), kTolerance);
}

TEST(FeatureBusHit, AFireWhileRisingRaisesThePeakWithoutAStep) {
    fb::Hit hit;
    hit.step(0.4f, 0.01f, 0.1f, 1.0f);
    EXPECT_NEAR(hit.value(), 0.04f, kTolerance);
    // The rise now runs at the new peak's rate.
    EXPECT_NEAR(hit.step(1.0f, 0.01f, 0.1f, 1.0f), 0.14f, kTolerance);
}

TEST(FeatureBusHit, AWeakerFireWhileDecayingHoldsInsteadOfDipping) {
    fb::Hit hit;
    hit.step(1.0f, 0.1f, 0.05f, 1.0f);
    hit.step(0.0f, 0.1f, 0.05f, 1.0f);
    const float before = hit.value();
    EXPECT_NEAR(hit.step(0.3f, 0.1f, 0.05f, 1.0f), before, kTolerance);
    EXPECT_NEAR(hit.step(0.0f, 0.1f, 0.05f, 1.0f), before * std::exp(-0.1f), kTolerance);
}

TEST(FeatureBusHit, ClampsTheStrengthAndIgnoresAStalledFrame) {
    fb::Hit hit;
    EXPECT_EQ(hit.step(1.0f, 0.0f, 0.05f, 1.0f), 0.0f);
    EXPECT_EQ(hit.step(-1.0f, -1.0f, 0.05f, 1.0f), 0.0f);
    hit.step(3.0f, 1.0f, 0.05f, 1.0f);
    EXPECT_NEAR(hit.value(), 1.0f, kTolerance);
}

TEST(FeatureBusHit, SettlesToExactlyZero) {
    fb::Hit hit;
    hit.step(1.0f, 0.1f, 0.05f, 10.0f);
    for (int i = 0; i < 100; ++i) hit.step(0.0f, 0.1f, 0.05f, 10.0f);
    EXPECT_EQ(hit.value(), 0.0f);
    hit.step(1.0f, 0.1f, 0.05f, 10.0f);
    hit.reset();
    EXPECT_EQ(hit.value(), 0.0f);
    EXPECT_NEAR(hit.step(0.0f, 0.1f, 0.05f, 10.0f), 0.0f, kTolerance);
}

// ---- the helpers -----------------------------------------------------------------------------------

TEST(FeatureBusHelpers, SlewRisesAndFallsAtTheirOwnRates) {
    EXPECT_NEAR(fb::slew(0.0f, 1.0f, 0.1f, 2.0f, 8.0f), 1.0f - std::exp(-0.2f), kTolerance);
    EXPECT_NEAR(fb::slew(1.0f, 0.0f, 0.1f, 2.0f, 8.0f), std::exp(-0.8f), kTolerance);
    EXPECT_EQ(fb::slew(0.3f, 0.3f + fb::kSettled * 0.5f, 0.001f, 1.0f, 1.0f), 0.3f + fb::kSettled * 0.5f);
    EXPECT_EQ(fb::slew(0.3f, 0.8f, 0.0f, 1.0f, 1.0f), 0.3f);
    // A stall integrates no more than kMaxDt.
    EXPECT_NEAR(fb::slew(0.0f, 1.0f, 10.0f, 1.0f, 1.0f), 1.0f - std::exp(-fb::kMaxDt), kTolerance);
}

TEST(FeatureBusHelpers, WrappedStepTakesTheShortWayRound) {
    EXPECT_NEAR(fb::wrappedStep(0.9f, 0.1f), 0.2f, kTolerance);
    EXPECT_NEAR(fb::wrappedStep(0.1f, 0.9f), -0.2f, kTolerance);
    EXPECT_NEAR(fb::wrappedStep(0.2f, 0.4f), 0.2f, kTolerance);
    EXPECT_NEAR(fb::wrappedStep(0.4f, 0.4f), 0.0f, kTolerance);
}

TEST(FeatureBusHelpers, TrustFollowsTheWeakerOfConfidenceAndStability) {
    EXPECT_EQ(fb::trustOf(1.0f, fb::kTrustFloor), 0.0f);
    EXPECT_EQ(fb::trustOf(fb::kTrustFloor - 0.1f, 1.0f), 0.0f);
    EXPECT_EQ(fb::trustOf(1.0f, fb::kTrustCeiling), 1.0f);
    EXPECT_EQ(fb::trustOf(1.0f, 1.0f), 1.0f);
    EXPECT_NEAR(fb::trustOf(0.45f, 0.9f), (0.45f - fb::kTrustFloor) / (fb::kTrustCeiling - fb::kTrustFloor),
                kTolerance);
    EXPECT_NEAR(fb::trustOf(0.9f, 0.45f), (0.45f - fb::kTrustFloor) / (fb::kTrustCeiling - fb::kTrustFloor),
                kTolerance);
}

// ---- rhythm ------------------------------------------------------------------------------------------

TEST(FeatureBusRhythm, EachDrumDrivesItsOwnLayer) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.kick = 1.0f;
    const FeatureValues& v = bus.step(f, kDt);
    EXPECT_NEAR(v.kick, kDt / fb::kKick.attackSeconds, kTolerance);
    EXPECT_EQ(v.snare, 0.0f);
    EXPECT_EQ(v.hat, 0.0f);
    f = GeodeFeatureFrame{};
    f.snare = 0.5f;
    bus.step(f, kDt);
    EXPECT_NEAR(bus.values().snare, 0.5f * kDt / fb::kSnare.attackSeconds, kTolerance);
    EXPECT_EQ(bus.values().hat, 0.0f);
    f = GeodeFeatureFrame{};
    f.hat = 0.8f;
    bus.step(f, kDt);
    // A hat's attack is shorter than a frame; the snare has reached its peak.
    EXPECT_NEAR(bus.values().hat, 0.8f, kTolerance);
    EXPECT_NEAR(bus.values().snare, 0.5f, kTolerance);
    bus.step(quiet(), kDt);
    EXPECT_NEAR(bus.values().snare, 0.5f * std::exp(-kDt * fb::kSnare.fallHz), kTolerance);
    EXPECT_NEAR(bus.values().hat, 0.8f * std::exp(-kDt * fb::kHat.fallHz), kTolerance);
}

TEST(FeatureBusRhythm, TheHatFallsFasterThanTheSnareAndTheSnareThanTheKick) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.kick = 1.0f;
    f.snare = 1.0f;
    f.hat = 1.0f;
    bus.step(f, 0.1f);
    run(bus, quiet(), 0.1f);
    const FeatureValues& v = bus.values();
    EXPECT_GT(v.kick, v.snare);
    EXPECT_GT(v.snare, v.hat);
    EXPECT_GT(v.hat, 0.0f);
}

TEST(FeatureBusRhythm, NoDownbeatFiresWhileTheBarIsNotTrusted) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.downbeat = 1.0f;
    f.pulseConfidence = 0.2f;
    f.tempoStability = 1.0f;
    f.downbeatConfidence = 1.0f;
    for (int i = 0; i < 600; ++i) {
        const FeatureValues& v = bus.step(f, kDt);
        EXPECT_EQ(v.downbeat, 0.0f);
        EXPECT_EQ(v.downbeatStrike, 0.0f);
    }
    EXPECT_EQ(bus.values().rhythmTrust, 0.0f);
}

TEST(FeatureBusRhythm, ATrustedDownbeatFiresWithTheTrustAsItsStrength) {
    FeatureBus bus;
    run(bus, locked(), 20.0f);
    EXPECT_NEAR(bus.values().rhythmTrust, 1.0f, kTolerance);
    EXPECT_NEAR(bus.values().barTrust, 1.0f, kTolerance);
    GeodeFeatureFrame f = locked();
    f.downbeat = 1.0f;
    const FeatureValues& v = bus.step(f, kDt);
    EXPECT_NEAR(v.downbeatStrike, 1.0f, kTolerance);
    EXPECT_NEAR(v.downbeat, kDt / fb::kDownbeat.attackSeconds, kTolerance);
    bus.step(locked(), kDt);
    EXPECT_EQ(bus.values().downbeatStrike, 0.0f);
}

TEST(FeatureBusRhythm, TrustIsLostFasterThanItIsGained) {
    FeatureBus bus;
    bus.step(locked(), 0.2f);
    const float gained = bus.values().rhythmTrust;
    EXPECT_NEAR(gained, 1.0f - std::exp(-0.2f * fb::kTrustRiseHz), kTolerance);
    run(bus, locked(), 20.0f);
    bus.step(quiet(), 0.2f);
    EXPECT_NEAR(bus.values().rhythmTrust, std::exp(-0.2f * fb::kTrustFallHz), kTolerance);
    EXPECT_NEAR(bus.values().barTrust, bus.values().rhythmTrust * bus.values().downbeatConfidence, kTolerance);
}

TEST(FeatureBusRhythm, TheBeatInTheBarHoldsWhileTheBarIsNotTrusted) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    for (int i = 0; i < 200; ++i) {
        f.beatInBar = static_cast<float>(i % 4);
        EXPECT_EQ(bus.step(f, kDt).beatInBar, 0.0f);
    }
    run(bus, locked(), 20.0f);
    f = locked();
    f.beatInBar = 2.0f;
    EXPECT_EQ(bus.step(f, kDt).beatInBar, 2.0f);
    f.beatInBar = 7.0f;
    EXPECT_EQ(bus.step(f, kDt).beatInBar, 3.0f);
    // Losing the bar keeps the last trusted beat.
    GeodeFeatureFrame losing{};
    losing.beatInBar = 3.0f;
    run(bus, losing, 5.0f);
    EXPECT_LT(bus.values().barTrust, fb::kBarTrustFloor);
    f = GeodeFeatureFrame{};
    f.beatInBar = 1.0f;
    EXPECT_EQ(bus.step(f, kDt).beatInBar, 3.0f);
}

TEST(FeatureBusRhythm, TheBarPhaseAndConfidencesAreCarriedClamped) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.barPhase = 1.7f;
    f.pulseConfidence = 2.0f;
    bus.step(f, 0.1f);
    EXPECT_EQ(bus.values().barPhase, 1.0f);
    EXPECT_NEAR(bus.values().pulseConfidence, 1.0f - std::exp(-0.1f * fb::kConfidenceHz), kTolerance);
    f.barPhase = 0.3f;
    f.tempoStability = 0.5f;
    f.downbeatConfidence = 0.5f;
    bus.step(f, 0.1f);
    EXPECT_NEAR(bus.values().barPhase, 0.3f, kTolerance);
    EXPECT_NEAR(bus.values().tempoStability, 0.5f * (1.0f - std::exp(-0.1f * fb::kConfidenceHz)), kTolerance);
    EXPECT_NEAR(bus.values().downbeatConfidence, 0.5f * (1.0f - std::exp(-0.1f * fb::kConfidenceHz)), kTolerance);
}

// ---- structure ---------------------------------------------------------------------------------------

TEST(FeatureBusStructure, ASectionStepsTheSectionPhaseOnAndItGlidesThere) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.sectionBoundary = 1.0f;
    const FeatureValues& v = bus.step(f, 0.1f);
    EXPECT_EQ(v.sectionStrike, 1.0f);
    // The short way to 0.618 is backwards through 0.
    const float step = fb::wrappedStep(0.0f, fb::kSectionStep);
    EXPECT_NEAR(v.section, std::fmod(step * (1.0f - std::exp(-0.1f * fb::kSectionGlideHz)) + 1.0f, 1.0f), kTolerance);
    bus.step(quiet(), 0.1f);
    EXPECT_EQ(bus.values().sectionStrike, 0.0f);
    run(bus, quiet(), 60.0f);
    EXPECT_NEAR(bus.values().section, fb::kSectionStep, kTolerance);
    bus.step(f, kDt);
    run(bus, quiet(), 60.0f);
    EXPECT_NEAR(bus.values().section, std::fmod(2.0f * fb::kSectionStep, 1.0f), kTolerance);
}

TEST(FeatureBusStructure, ASectionMidGlideTurnsTheShortWayAndWrapsForward) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.sectionBoundary = 1.0f;
    bus.step(f, kDt);
    // Backwards through 0 toward 0.618, until it is past 0.9.
    while (bus.values().section > 0.9f || bus.values().section < 0.5f) bus.step(quiet(), kDt);
    // The next section (0.236) is now ahead, forwards through 1.
    bus.step(f, kDt);
    bool wrapped = false;
    for (int i = 0; i < 3600; ++i) {
        const float s = bus.step(quiet(), kDt).section;
        ASSERT_GE(s, 0.0f);
        ASSERT_LT(s, 1.0f);
        wrapped = wrapped || s < 0.5f;
    }
    EXPECT_TRUE(wrapped);
    EXPECT_NEAR(bus.values().section, std::fmod(2.0f * fb::kSectionStep, 1.0f), kTolerance);
}

TEST(FeatureBusStructure, ADropGivesAShortHitAndALongGlow) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.drop = 1.0f;
    bus.step(f, kDt);
    EXPECT_NEAR(bus.values().dropHit, kDt / fb::kDropHit.attackSeconds, kTolerance);
    EXPECT_NEAR(bus.values().drop, kDt / fb::kDropGlow.attackSeconds, kTolerance);
    run(bus, quiet(), 3.0f);
    EXPECT_LT(bus.values().dropHit, 0.1f);
    EXPECT_GT(bus.values().drop, 0.6f);
    EXPECT_EQ(bus.values().arrival, 0.0f);
    EXPECT_EQ(bus.values().arrivalHit, 0.0f);
}

TEST(FeatureBusStructure, AnArrivalGivesItsOwnHitAndGlow) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.arrival = 1.0f;
    bus.step(f, kDt);
    EXPECT_NEAR(bus.values().arrivalHit, kDt / fb::kArrivalHit.attackSeconds, kTolerance);
    EXPECT_NEAR(bus.values().arrival, kDt / fb::kArrivalGlow.attackSeconds, kTolerance);
    EXPECT_EQ(bus.values().drop, 0.0f);
    EXPECT_EQ(bus.values().sectionHit, 0.0f);
}

TEST(FeatureBusStructure, ASectionHitRisesThenDecays) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.sectionBoundary = 1.0f;
    bus.step(f, kDt);
    EXPECT_NEAR(bus.values().sectionHit, kDt / fb::kSectionHit.attackSeconds, kTolerance);
    run(bus, quiet(), 0.12f);
    EXPECT_NEAR(bus.values().sectionHit, 1.0f, kTolerance);
    run(bus, quiet(), 4.0f);
    EXPECT_LT(bus.values().sectionHit, 0.01f);
}

TEST(FeatureBusStructure, SlowValuesRiseAndFallAtTheirOwnRates) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.novelty = 1.0f;
    f.buildup = 1.0f;
    f.macroEnergy = 1.0f;
    bus.step(f, 0.1f);
    EXPECT_NEAR(bus.values().novelty, 1.0f - std::exp(-0.1f * fb::kNoveltyRiseHz), kTolerance);
    EXPECT_NEAR(bus.values().buildup, 1.0f - std::exp(-0.1f * fb::kBuildupRiseHz), kTolerance);
    EXPECT_NEAR(bus.values().macro, 1.0f - std::exp(-0.1f * fb::kMacroRiseHz), kTolerance);
    run(bus, f, 60.0f);
    bus.step(quiet(), 0.1f);
    EXPECT_NEAR(bus.values().novelty, std::exp(-0.1f * fb::kNoveltyFallHz), kTolerance);
    EXPECT_NEAR(bus.values().buildup, std::exp(-0.1f * fb::kBuildupFallHz), kTolerance);
    EXPECT_NEAR(bus.values().macro, std::exp(-0.1f * fb::kMacroFallHz), kTolerance);
}

// ---- timbre ------------------------------------------------------------------------------------------

TEST(FeatureBusTimbre, HoldsUntilTheFirstLatchThenGlidesToTheAverage) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.centroid = 0.8f;
    f.harmonicity = 0.9f;
    f.onset = 0.6f;
    run(bus, f, fb::kFirstLatchSeconds - 0.1f);
    EXPECT_EQ(bus.values().centroid, 0.0f);
    EXPECT_EQ(bus.values().harmonicity, 0.5f);
    EXPECT_EQ(bus.values().onset, 0.0f);
    run(bus, f, 0.2f);
    EXPECT_GT(bus.values().centroid, 0.0f);
    run(bus, f, 6.0f);
    EXPECT_GT(bus.values().centroid, 0.7f);
    EXPECT_GT(bus.values().harmonicity, 0.85f);
    EXPECT_GT(bus.values().onset, 0.5f);
}

TEST(FeatureBusTimbre, ChangesBetweenLatchesWaitForTheNextOne) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.centroid = 0.2f;
    run(bus, f, 20.0f);
    EXPECT_NEAR(bus.values().centroid, 0.2f, 1e-3f);
    // Untrusted: the fallback latch. The next average is taken over the frames since the last latch.
    f.centroid = 0.9f;
    run(bus, f, 1.0f);
    EXPECT_NEAR(bus.values().centroid, 0.2f, 1e-3f);
    run(bus, f, fb::kFallbackLatchSeconds + 4.0f);
    EXPECT_GT(bus.values().centroid, 0.6f);
}

TEST(FeatureBusTimbre, ASectionBoundaryLatchesAtOnce) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.centroid = 0.2f;
    run(bus, f, 20.0f);
    f.centroid = 0.9f;
    run(bus, f, 2.5f);
    EXPECT_NEAR(bus.values().centroid, 0.2f, 0.05f);
    GeodeFeatureFrame turn = f;
    turn.sectionBoundary = 1.0f;
    bus.step(turn, kDt);
    run(bus, f, 3.0f);
    EXPECT_GT(bus.values().centroid, 0.4f);
}

TEST(FeatureBusTimbre, ASectionTooSoonAfterALatchWaits) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.centroid = 0.2f;
    // The fallback latches at 2 s, 10 s and 18 s; a section under 2 s after the last is too soon to latch.
    run(bus, f, 18.1f);
    f.centroid = 0.9f;
    run(bus, f, 1.4f);
    GeodeFeatureFrame turn = f;
    turn.sectionBoundary = 1.0f;
    bus.step(turn, kDt);
    run(bus, f, 0.3f);
    EXPECT_NEAR(bus.values().centroid, 0.2f, 0.01f);
}

TEST(FeatureBusTimbre, ATrustedBarLatchesEveryPhrase) {
    FeatureBus bus;
    GeodeFeatureFrame f = locked();
    f.centroid = 0.2f;
    run(bus, f, 20.0f);
    f.centroid = 0.9f;
    GeodeFeatureFrame down = f;
    down.downbeat = 1.0f;
    // Three bars of two seconds: not yet a phrase.
    for (int bar = 0; bar < 3; ++bar) {
        bus.step(down, kDt);
        run(bus, f, 2.0f);
    }
    EXPECT_NEAR(bus.values().centroid, 0.2f, 0.05f);
    bus.step(down, kDt);
    run(bus, f, 3.0f);
    EXPECT_GT(bus.values().centroid, 0.4f);
}

TEST(FeatureBusTimbre, ATrustedBarWaitsLongerThanTheFallback) {
    FeatureBus bus;
    GeodeFeatureFrame f = locked();
    f.centroid = 0.2f;
    run(bus, f, 20.0f);
    f.centroid = 0.9f;
    run(bus, f, fb::kFallbackLatchSeconds + 1.0f);
    EXPECT_NEAR(bus.values().centroid, 0.2f, 0.05f);
    run(bus, f, fb::kTrustedLatchSeconds);
    EXPECT_GT(bus.values().centroid, 0.4f);
}

TEST(FeatureBusTimbre, FluxIsReadAgainstItsOwnPeak) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.flux = 50.0f;
    run(bus, f, 14.0f);
    EXPECT_NEAR(bus.values().flux, 1.0f, 0.02f);
    FeatureBus quieter;
    GeodeFeatureFrame g{};
    g.flux = 0.002f;
    run(quieter, g, 14.0f);
    EXPECT_NEAR(quieter.values().flux, 1.0f, 0.02f);
    // Half the peak reads as half.
    FeatureBus mixed;
    for (int i = 0; i < 1200; ++i) {
        GeodeFeatureFrame h{};
        h.flux = i % 2 == 0 ? 10.0f : 5.0f;
        mixed.step(h, kDt);
    }
    EXPECT_NEAR(mixed.values().flux, 0.75f, 0.03f);
}

// ---- space -------------------------------------------------------------------------------------------

TEST(FeatureBusSpace, StereoValuesGlideAndAreClamped) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.stereoWidth = 2.0f;
    f.stereoPan = -3.0f;
    f.stereoCorrelation = -0.5f;
    bus.step(f, 0.1f);
    const float k = 1.0f - std::exp(-0.1f * fb::kSpaceHz);
    EXPECT_NEAR(bus.values().stereoWidth, k, kTolerance);
    EXPECT_NEAR(bus.values().stereoPan, -k, kTolerance);
    EXPECT_NEAR(bus.values().stereoCorrelation, 1.0f - 1.5f * k, kTolerance);
}

// ---- the bands against their usual level (#319) ---------------------------------------------------------

void run(fb::UsualBand& band, float level, float seconds) {
    const int frames = static_cast<int>(std::lround(seconds / kDt));
    for (int i = 0; i < frames; ++i) band.step(level, kDt);
}

TEST(FeatureBusBands, ASteadyBandReadsHalfAtItsUsualLevelAndNeverHits) {
    fb::UsualBand band;
    band.step(0.4f, kDt);
    EXPECT_EQ(band.usual(), 0.4f) << "starts at the first level heard";
    run(band, 0.4f, 30.0f);
    EXPECT_EQ(band.usual(), 0.4f);
    EXPECT_EQ(band.level(), 0.5f);
    EXPECT_EQ(band.presence(), 0.5f);
    EXPECT_EQ(band.hits(), 0.0f);
}

TEST(FeatureBusBands, AJumpWellAboveTheUsualLevelHitsOnceUntilTheBandFallsBack) {
    fb::UsualBand band;
    run(band, 0.4f, 30.0f);
    band.step(0.8f, kDt);
    EXPECT_GT(band.hits(), 0.0f);
    EXPECT_LT(band.hits(), 0.6f) << "rises over the attack instead of stepping";
    band.step(0.8f, kDt);
    const float usual = band.usual();
    EXPECT_GT(usual, 0.4f);
    EXPECT_NEAR(band.hits(), 0.5f * 0.8f / (0.4f + 0.4f * (1.0f - std::exp(-kDt / fb::kUsualRiseSeconds))) / fb::kBandHitRatio,
                1e-3f);
    run(band, 0.8f, 0.25f);
    EXPECT_GT(band.level(), 0.85f);
    EXPECT_LT(band.presence(), 0.6f) << "the presence follows over seconds";
    run(band, 0.8f, 1.0f);
    EXPECT_LT(band.hits(), 0.01f) << "a held jump is one hit";
    run(band, 0.4f, 0.5f);
    band.step(0.8f, kDt);
    band.step(0.8f, kDt);
    EXPECT_GT(band.hits(), 0.3f) << "falling back re-arms it";
}

TEST(FeatureBusBands, AJumpShortOfTheRatioDoesNotHit) {
    fb::UsualBand band;
    run(band, 0.4f, 30.0f);
    run(band, 0.4f * fb::kBandHitRatio * 0.98f, 0.5f);
    EXPECT_EQ(band.hits(), 0.0f);
}

TEST(FeatureBusBands, AHeldBandAboveTheRearmLevelDoesNotHitAgain) {
    fb::UsualBand band;
    run(band, 0.2f, 30.0f);
    run(band, 0.6f, 0.05f);
    run(band, 0.2f * fb::kBandRearmRatio * 1.3f, 0.02f);
    run(band, 0.9f, 0.05f);
    EXPECT_LT(band.hits(), 0.65f) << "never fell back under the rearm level";
}

TEST(FeatureBusBands, TheUsualLevelHoldsThroughSilence) {
    fb::UsualBand band;
    run(band, 0.4f, 10.0f);
    run(band, 0.0f, 60.0f);
    EXPECT_EQ(band.usual(), 0.4f);
    EXPECT_EQ(band.level(), 0.0f);
    EXPECT_EQ(band.presence(), 0.0f);
    for (int i = 0; i < 120; ++i) {
        band.step(0.4f, kDt);
        ASSERT_EQ(band.hits(), 0.0f) << "the music coming back is not a jump";
    }
    EXPECT_NEAR(band.level(), 0.5f, 1e-3f);
}

TEST(FeatureBusBands, TheUsualLevelRisesFasterThanItFalls) {
    fb::UsualBand rising;
    run(rising, 0.2f, 30.0f);
    run(rising, 0.6f, 4.0f);
    EXPECT_NEAR(rising.usual(), 0.2f + 0.4f * (1.0f - std::exp(-4.0f / fb::kUsualRiseSeconds)), 1e-3f);
    fb::UsualBand falling;
    run(falling, 0.6f, 30.0f);
    run(falling, 0.2f, 4.0f);
    EXPECT_NEAR(falling.usual(), 0.6f - 0.4f * (1.0f - std::exp(-4.0f / fb::kUsualFallSeconds)), 1e-3f);
    run(rising, 0.6f, 120.0f);
    EXPECT_NEAR(rising.level(), 0.5f, 1e-3f) << "a louder song comes to read as usual";
}

TEST(FeatureBusBands, AFaintBandIsReadAgainstTheFloorAndSilenceIsNotHeard) {
    fb::UsualBand faint;
    run(faint, 0.005f, 10.0f);
    EXPECT_EQ(faint.usual(), fb::kUsualFloor);
    EXPECT_NEAR(faint.level(), 0.5f * 0.005f / fb::kUsualFloor, 1e-4f);
    fb::UsualBand silent;
    run(silent, fb::kAudible, 10.0f);
    EXPECT_EQ(silent.usual(), 0.0f);
    EXPECT_EQ(silent.level(), 0.0f);
    fb::UsualBand broken;
    broken.step(std::nanf(""), kDt);
    broken.step(std::numeric_limits<float>::infinity(), kDt);
    broken.step(2.0f, kDt);
    EXPECT_EQ(broken.usual(), 1.0f) << "kept to 0..1, nothing not a number";
    EXPECT_TRUE(std::isfinite(broken.level()));
}

TEST(FeatureBusBands, ResetStartsOver) {
    fb::UsualBand band;
    run(band, 0.7f, 5.0f);
    band.step(1.0f, kDt);
    band.reset();
    EXPECT_EQ(band.usual(), 0.0f);
    EXPECT_EQ(band.level(), 0.0f);
    EXPECT_EQ(band.presence(), 0.0f);
    EXPECT_EQ(band.hits(), 0.0f);
    band.step(0.3f, kDt);
    EXPECT_EQ(band.usual(), 0.3f);
}

TEST(FeatureBus, EachBandIsReadAgainstItsOwnUsualLevel) {
    FeatureBus bus;
    GeodeFeatureFrame f{};
    f.bass = 0.6f;
    f.mid = 0.2f;
    f.treble = 0.05f;
    run(bus, f, 30.0f);
    for (size_t b = 0; b < fb::kBands; ++b) {
        EXPECT_EQ(bus.values().bandLevel[b], 0.5f) << b;
        EXPECT_EQ(bus.values().bandPresence[b], 0.5f) << b;
    }
    f.treble = 0.15f;
    bus.step(f, kDt);
    bus.step(f, kDt);
    EXPECT_EQ(bus.values().bandHits[0], 0.0f);
    EXPECT_EQ(bus.values().bandHits[1], 0.0f);
    EXPECT_GT(bus.values().bandHits[2], 0.5f) << "the treble tripled";
    EXPECT_GT(bus.values().bandLevel[2], bus.values().bandLevel[0]);
    const auto uniforms = uniformValues(bus.values());
    EXPECT_EQ(uniforms[29], bus.values().bandHits[2]);
    bus.reset();
    EXPECT_EQ(bus.values().bandHits[2], 0.0f);
    bus.step(f, kDt);
    EXPECT_EQ(bus.values().bandHits[2], 0.0f) << "a reset forgets the usual level, so nothing is a jump";
}

// ---- the whole bus -----------------------------------------------------------------------------------

TEST(FeatureBus, SilenceSettlesAndHoldsStill) {
    FeatureBus bus;
    GeodeFeatureFrame loud = locked();
    loud.kick = loud.snare = loud.hat = 1.0f;
    loud.novelty = loud.buildup = loud.macroEnergy = 1.0f;
    loud.centroid = 0.7f;
    loud.flux = 3.0f;
    loud.stereoWidth = 0.8f;
    loud.drop = loud.arrival = loud.sectionBoundary = loud.downbeat = 1.0f;
    run(bus, loud, 4.0f);
    run(bus, quiet(), 120.0f);
    const auto before = uniformValues(bus.values());
    for (int i = 0; i < 600; ++i) {
        const auto now = uniformValues(bus.step(quiet(), kDt));
        for (size_t u = 0; u < now.size(); ++u) {
            ASSERT_TRUE(std::isfinite(now[u])) << kFeatureUniforms[u];
            ASSERT_EQ(now[u], before[u]) << kFeatureUniforms[u] << " moved in silence";
        }
    }
}

TEST(FeatureBus, AnUnsureTrackerNeverMakesTheRhythmJitter) {
    FeatureBus bus;
    unsigned int state = 12345u;
    float last = 0.0f;
    for (int i = 0; i < 3600; ++i) {
        state = state * 1664525u + 1013904223u;
        GeodeFeatureFrame f{};
        f.pulseConfidence = static_cast<float>((state >> 8) & 0xFFu) / 255.0f * 0.3f;
        f.tempoStability = 0.2f;
        f.downbeatConfidence = 0.9f;
        f.downbeat = (state & 0x10u) != 0u ? 1.0f : 0.0f;
        f.beatInBar = static_cast<float>(state % 4u);
        f.barPhase = static_cast<float>((state >> 4) & 0xFFu) / 255.0f;
        const FeatureValues& v = bus.step(f, kDt);
        ASSERT_EQ(v.downbeat, 0.0f);
        ASSERT_EQ(v.beatInBar, 0.0f);
        ASSERT_LT(v.barTrust, fb::kBarTrustFloor);
        ASSERT_EQ(featuremap::cymaticsModes(8, v), 8);
        ASSERT_NEAR(featuremap::lifeKickAngle(last, v), std::fmod(last + featuremap::kGoldenAngle, 6.2831853f),
                    kTolerance);
        last = featuremap::lifeKickAngle(last, v);
    }
}

TEST(FeatureBus, TheSameFramesGiveTheSameValues) {
    FeatureBus a;
    FeatureBus b;
    unsigned int state = 99u;
    for (int i = 0; i < 2000; ++i) {
        state = state * 1664525u + 1013904223u;
        GeodeFeatureFrame f = locked();
        f.kick = (state & 0x3u) == 0u ? 0.9f : 0.0f;
        f.downbeat = (state & 0x3Fu) == 0u ? 1.0f : 0.0f;
        f.sectionBoundary = (state & 0x3FFu) == 0u ? 1.0f : 0.0f;
        f.centroid = static_cast<float>((state >> 8) & 0xFFu) / 255.0f;
        f.flux = static_cast<float>((state >> 16) & 0xFFu);
        const auto va = uniformValues(a.step(f, kDt));
        const auto vb = uniformValues(b.step(f, kDt));
        for (size_t u = 0; u < va.size(); ++u) ASSERT_EQ(va[u], vb[u]) << kFeatureUniforms[u] << " at frame " << i;
    }
}

TEST(FeatureBus, ResetStartsOver) {
    FeatureBus bus;
    FeatureBus fresh;
    GeodeFeatureFrame f = locked();
    f.kick = f.drop = f.sectionBoundary = f.downbeat = 1.0f;
    f.centroid = 0.9f;
    f.flux = 4.0f;
    f.stereoWidth = 1.0f;
    run(bus, f, 30.0f);
    bus.reset();
    GeodeFeatureFrame g{};
    g.centroid = 0.3f;
    g.flux = 0.01f;
    for (int i = 0; i < 900; ++i) {
        const auto a = uniformValues(bus.step(g, kDt));
        const auto b = uniformValues(fresh.step(g, kDt));
        for (size_t u = 0; u < a.size(); ++u) ASSERT_EQ(a[u], b[u]) << kFeatureUniforms[u];
    }
    EXPECT_EQ(bus.values().barTrust, fresh.values().barTrust);
    EXPECT_EQ(bus.values().sectionStrike, 0.0f);
}

TEST(FeatureBus, ReduceMotionCalmsTheHitsAndNothingElse) {
    FeatureValues v;
    v.kick = v.snare = v.hat = v.downbeat = v.downbeatStrike = v.sectionStrike = 1.0f;
    v.sectionHit = v.dropHit = v.arrivalHit = 1.0f;
    v.novelty = v.drop = v.arrival = v.macro = v.buildup = v.section = 0.5f;
    v.centroid = v.flux = v.onset = v.stereoWidth = v.barTrust = v.barPhase = 0.5f;
    const FeatureValues c = fb::calmed(v, 0.4f);
    for (float hit : {c.kick, c.snare, c.hat, c.downbeat, c.downbeatStrike, c.sectionStrike, c.sectionHit, c.dropHit,
                      c.arrivalHit}) {
        EXPECT_NEAR(hit, 0.4f, kTolerance);
    }
    for (float kept : {c.novelty, c.drop, c.arrival, c.macro, c.buildup, c.section, c.centroid, c.flux, c.onset,
                       c.stereoWidth, c.barTrust, c.barPhase}) {
        EXPECT_EQ(kept, 0.5f);
    }
    EXPECT_EQ(c.harmonicity, 0.5f);
    EXPECT_EQ(c.stereoCorrelation, 1.0f);
}

TEST(FeatureBus, ReduceMotionCalmsTheBandHitsButNotTheirLevels) {
    FeatureValues v;
    v.bandHits = {1.0f, 0.5f, 0.25f};
    v.bandLevel = {0.5f, 0.5f, 0.5f};
    v.bandPresence = {0.5f, 0.5f, 0.5f};
    const FeatureValues c = fb::calmed(v, 0.4f);
    EXPECT_NEAR(c.bandHits[0], 0.4f, kTolerance);
    EXPECT_NEAR(c.bandHits[1], 0.2f, kTolerance);
    EXPECT_NEAR(c.bandHits[2], 0.1f, kTolerance);
    for (size_t b = 0; b < fb::kBands; ++b) {
        EXPECT_EQ(c.bandLevel[b], 0.5f);
        EXPECT_EQ(c.bandPresence[b], 0.5f);
    }
}

TEST(FeatureBus, UniformValuesFollowTheUniformNames) {
    FeatureValues v;
    v.kick = 1.0f;
    v.snare = 2.0f;
    v.hat = 3.0f;
    v.beatInBar = 4.0f;
    v.downbeat = 5.0f;
    v.downbeatConfidence = 6.0f;
    v.pulseConfidence = 7.0f;
    v.tempoStability = 8.0f;
    v.novelty = 9.0f;
    v.section = 10.0f;
    v.buildup = 11.0f;
    v.drop = 12.0f;
    v.arrival = 13.0f;
    v.macro = 14.0f;
    v.sectionHit = 15.0f;
    v.dropHit = 16.0f;
    v.arrivalHit = 17.0f;
    v.centroid = 18.0f;
    v.harmonicity = 19.0f;
    v.flux = 20.0f;
    v.onset = 21.0f;
    v.stereoWidth = 22.0f;
    v.stereoPan = 23.0f;
    v.stereoCorrelation = 24.0f;
    v.bandLevel = {25.0f, 26.0f, 27.0f};
    v.bandHits = {28.0f, 29.0f, 30.0f};
    v.bandPresence = {31.0f, 32.0f, 33.0f};
    const auto values = uniformValues(v);
    for (size_t u = 0; u < values.size(); ++u) EXPECT_EQ(values[u], static_cast<float>(u + 1)) << kFeatureUniforms[u];
}

// ---- the families --------------------------------------------------------------------------------------

TEST(FeatureMap, EveryFamilyIsNeutralWhenTheMusicGivesNothing) {
    const FeatureValues v;
    EXPECT_EQ(featuremap::fluidSplatForce(v), 1.0f);
    EXPECT_EQ(featuremap::fluidCurl(v), 1.0f);
    EXPECT_EQ(featuremap::fluidHueOffset(v), 0.0f);
    EXPECT_EQ(featuremap::lifeRuleScale(v), 1.0f);
    EXPECT_EQ(featuremap::mycoSensorAngleScale(v), 1.0f);
    EXPECT_EQ(featuremap::acidFeedbackScale(v), 1.0f);
    EXPECT_EQ(featuremap::cymaticsModes(6, v), 6);
    EXPECT_EQ(featuremap::curlForce(v), 1.0f);
    EXPECT_EQ(featuremap::waterRing(v), 0.0f);
    EXPECT_EQ(featuremap::waterSwell(v), 1.0f);
    EXPECT_EQ(featuremap::milkdropZoom(v), 0.0f);
}

TEST(FeatureMap, FluidTakesTheKickTheDropAndTheBrightness) {
    FeatureValues v;
    v.kick = 0.5f;
    v.dropHit = 0.25f;
    v.centroid = 1.0f;
    EXPECT_NEAR(featuremap::fluidSplatForce(v),
                1.0f + 0.5f * featuremap::kFluidKickForce + 0.25f * featuremap::kFluidDropForce, kTolerance);
    EXPECT_NEAR(featuremap::fluidCurl(v), 1.0f + 0.25f * featuremap::kFluidDropCurl, kTolerance);
    EXPECT_NEAR(featuremap::fluidHueOffset(v), featuremap::kFluidCentroidHue, kTolerance);
}

TEST(FeatureMap, LifeSeedsOnTheBarClockWhileTheBarIsTrusted) {
    FeatureValues v;
    v.barPhase = 0.25f;
    v.barTrust = fb::kBarTrustFloor;
    EXPECT_NEAR(featuremap::lifeKickAngle(1.0f, v), 0.25f * 6.2831853f, kTolerance);
    v.barTrust = fb::kBarTrustFloor - 0.01f;
    EXPECT_NEAR(featuremap::lifeKickAngle(1.0f, v), 1.0f + featuremap::kGoldenAngle, kTolerance);
    EXPECT_NEAR(featuremap::lifeKickAngle(5.0f, v), 5.0f + featuremap::kGoldenAngle - 6.2831853f, kTolerance);
    v.harmonicity = 1.0f;
    EXPECT_NEAR(featuremap::lifeRuleScale(v), 1.0f + featuremap::kLifeRuleSpan, kTolerance);
    v.harmonicity = 0.0f;
    EXPECT_NEAR(featuremap::lifeRuleScale(v), 1.0f - featuremap::kLifeRuleSpan, kTolerance);
}

TEST(FeatureMap, MyceliumAcidAndCurlFollowTheirFeatures) {
    FeatureValues v;
    v.novelty = 0.5f;
    EXPECT_NEAR(featuremap::mycoSensorAngleScale(v), 1.0f + 0.5f * featuremap::kMycoNoveltyBranch, kTolerance);
    v.flux = 1.0f;
    v.dropHit = 0.5f;
    EXPECT_NEAR(featuremap::acidFeedbackScale(v),
                (1.0f + featuremap::kAcidFluxFeedback) * (1.0f - 0.5f * featuremap::kAcidDropDecay), kTolerance);
    v.buildup = 0.5f;
    EXPECT_NEAR(featuremap::curlForce(v), 1.0f + 0.5f * featuremap::kCurlBuild, kTolerance);
}

TEST(FeatureMap, CymaticsClimbsThroughTheBar) {
    FeatureValues v;
    v.barTrust = 1.0f;
    v.barPhase = 0.0f;
    EXPECT_EQ(featuremap::cymaticsModes(8, v), 4);
    EXPECT_EQ(featuremap::cymaticsModes(7, v), 4);
    v.barPhase = 0.5f;
    EXPECT_EQ(featuremap::cymaticsModes(8, v), 6);
    v.barPhase = 1.0f;
    EXPECT_EQ(featuremap::cymaticsModes(8, v), 8);
    EXPECT_EQ(featuremap::cymaticsModes(1, v), 1);
    EXPECT_EQ(featuremap::cymaticsModes(0, v), 0);
    v.barPhase = 0.0f;
    EXPECT_EQ(featuremap::cymaticsModes(2, v), 1);
    v.barTrust = fb::kBarTrustFloor - 0.01f;
    EXPECT_EQ(featuremap::cymaticsModes(8, v), 8);
}

TEST(FeatureMap, WaterRingsOnTheDownbeatAndSwellsOnASection) {
    FeatureValues v;
    v.downbeatStrike = 0.8f;
    v.downbeat = 0.5f;
    v.sectionHit = 0.5f;
    v.dropHit = 0.5f;
    EXPECT_NEAR(featuremap::waterRing(v), 0.8f * featuremap::kWaterRing, kTolerance);
    EXPECT_NEAR(featuremap::waterSwell(v), 1.0f + 0.5f * featuremap::kWaterSectionSwell, kTolerance);
    EXPECT_NEAR(featuremap::milkdropZoom(v), 0.5f * featuremap::kMilkDownbeatZoom + 0.5f * featuremap::kMilkDropZoom,
                kTolerance);
}

}  // namespace
}  // namespace geode::viz
