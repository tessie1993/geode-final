#include "geode/viz/FrameRate.hpp"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>

#include "geode/viz/SharedEffects.hpp"

// The same look at 30, 60 and 120 Hz (#280): each per-frame step, run for the same wall-clock time at
// the three panel rates, has to land in the same place.

namespace geode::viz {
namespace {

constexpr float kTolerance = 1e-4f;
constexpr std::array<float, 3> kPanelRates = {30.0f, 60.0f, 120.0f};
constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();
constexpr float kInf = std::numeric_limits<float>::infinity();

int framesIn(float seconds, float hz) { return static_cast<int>(std::lround(seconds * hz)); }

// A per-frame decay run for `seconds` at `hz`.
float decayed(float perFrame, float seconds, float hz) {
    float value = 1.0f;
    for (int i = 0; i < framesIn(seconds, hz); ++i) value *= framerate::perFrameFactor(perFrame, 1.0f / hz);
    return value;
}

// A phase whose rate follows a "loudness" that swells and falls, sampled once per frame.
float integrated(float seconds, float hz) {
    float phase = 0.0f;
    const float dt = 1.0f / hz;
    for (int i = 0; i < framesIn(seconds, hz); ++i) {
        const float t = (static_cast<float>(i) + 0.5f) * dt;
        phase = framerate::advancePhase(phase, 1.0f + std::sin(t * 2.0f), dt, 0.0f);
    }
    return phase;
}

// The total scale the echo applies to the picture over `seconds` at `hz`.
float echoScale(float zoom, float seconds, float hz) {
    float scale = 1.0f;
    for (int i = 0; i < framesIn(seconds, hz); ++i) scale *= 1.0f - sharedfx::echoZoom(zoom, 1.0f / hz) * sharedfx::kEchoZoomScale;
    return scale;
}

int stepsIn(framerate::FixedStepClock& clock, float seconds, float hz) {
    int steps = 0;
    for (int i = 0; i < framesIn(seconds, hz); ++i) steps += clock.advance(1.0f / hz);
    return steps;
}

}  // namespace

TEST(FrameRate, AFrameCountsAsItsShareOfReferenceFrames) {
    EXPECT_NEAR(framerate::referenceFrames(1.0f / 60.0f), 1.0f, kTolerance);
    EXPECT_NEAR(framerate::referenceFrames(1.0f / 30.0f), 2.0f, kTolerance);
    EXPECT_NEAR(framerate::referenceFrames(1.0f / 120.0f), 0.5f, kTolerance);
}

TEST(FrameRate, AStalledOrBrokenFrameCountsAsNone) {
    EXPECT_EQ(framerate::referenceFrames(0.0f), 0.0f);
    EXPECT_EQ(framerate::referenceFrames(-0.01f), 0.0f);
    EXPECT_EQ(framerate::referenceFrames(kNaN), 0.0f);
    EXPECT_EQ(framerate::referenceFrames(kInf), 0.0f);
}

TEST(FrameRate, APerFrameFactorComposesAcrossFrames) {
    EXPECT_NEAR(framerate::perFrameFactor(0.9f, 1.0f / 60.0f), 0.9f, kTolerance);
    EXPECT_NEAR(framerate::perFrameFactor(0.9f, 1.0f / 30.0f), 0.81f, kTolerance);
    EXPECT_NEAR(framerate::perFrameFactor(0.81f, 1.0f / 120.0f), 0.9f, kTolerance);
    // A stalled frame changes nothing; a factor that is not positive keeps nothing.
    EXPECT_EQ(framerate::perFrameFactor(0.9f, 0.0f), 1.0f);
    EXPECT_EQ(framerate::perFrameFactor(0.0f, 1.0f / 60.0f), 0.0f);
    EXPECT_EQ(framerate::perFrameFactor(-0.5f, 1.0f / 60.0f), 0.0f);
    EXPECT_EQ(framerate::perFrameFactor(kNaN, 1.0f / 60.0f), 0.0f);
}

TEST(FrameRate, ADecayFadesTheSameEverySecondAt30_60And120Hz) {
    const float reference = std::pow(0.9f, 60.0f);
    for (const float hz : kPanelRates) EXPECT_NEAR(decayed(0.9f, 1.0f, hz), reference, kTolerance) << hz << " Hz";
}

TEST(FrameRate, APhaseAdvancesByItsRateAndWraps) {
    EXPECT_NEAR(framerate::advancePhase(0.25f, 2.0f, 0.25f, 10.0f), 0.75f, kTolerance);
    EXPECT_NEAR(framerate::advancePhase(0.75f, 1.0f, 0.5f, 1.0f), 0.25f, kTolerance);
    EXPECT_NEAR(framerate::advancePhase(0.5f, 1.0f, 0.5f, 1.0f), 0.0f, kTolerance);
    // A negative rate wraps back into [0, period) rather than below 0.
    EXPECT_NEAR(framerate::advancePhase(0.2f, -1.0f, 0.5f, 1.0f), 0.7f, kTolerance);
    // No period: unwrapped.
    EXPECT_NEAR(framerate::advancePhase(5.0f, 1.0f, 1.0f, 0.0f), 6.0f, kTolerance);
    EXPECT_NEAR(framerate::advancePhase(5.0f, 1.0f, 1.0f, -1.0f), 6.0f, kTolerance);
}

TEST(FrameRate, ABrokenFrameOrRateLeavesThePhaseWhereItWas) {
    EXPECT_EQ(framerate::advancePhase(0.4f, 1.0f, 0.0f, 1.0f), 0.4f);
    EXPECT_EQ(framerate::advancePhase(0.4f, 1.0f, -0.1f, 1.0f), 0.4f);
    EXPECT_EQ(framerate::advancePhase(0.4f, 1.0f, kNaN, 1.0f), 0.4f);
    EXPECT_EQ(framerate::advancePhase(0.4f, kNaN, 0.1f, 1.0f), 0.4f);
    EXPECT_EQ(framerate::advancePhase(0.4f, kInf, 0.1f, 1.0f), 0.4f);
}

TEST(FrameRate, ALoudnessDrivenPhaseLandsInTheSamePlaceAt30_60And120Hz) {
    const float reference = integrated(6.0f, 240.0f);
    for (const float hz : kPanelRates) EXPECT_NEAR(integrated(6.0f, hz), reference, 0.01f) << hz << " Hz";
}

TEST(FrameRate, ALoudnessDrivenPhaseSpeedsUpWithoutJumping) {
    // A minute in, loudness steps from quiet to loud. The product form `time x rate` jumps the picture
    // by a minute's worth of the change in rate; the integral moves one frame's worth.
    const float dt = 1.0f / 60.0f;
    const float quiet = 0.6f;
    const float loud = 2.6f;
    float phase = 0.0f;
    for (int i = 0; i < 3600; ++i) phase = framerate::advancePhase(phase, quiet, dt, 0.0f);
    const float before = phase;
    phase = framerate::advancePhase(phase, loud, dt, 0.0f);
    EXPECT_NEAR(phase - before, loud * dt, kTolerance);
    const float productJump = 60.0f * (loud - quiet);
    EXPECT_GT(productJump, 1000.0f * (phase - before));
}

TEST(FixedStepClock, RunsTheSameStepsEverySecondAt30_60And120Hz) {
    for (const float hz : kPanelRates) {
        framerate::FixedStepClock clock(60.0f, 4);
        EXPECT_EQ(stepsIn(clock, 2.0f, hz), 120) << hz << " Hz";
    }
}

TEST(FixedStepClock, StepsOncePerFrameAt60Hz) {
    framerate::FixedStepClock clock(60.0f, 4);
    for (int i = 0; i < 600; ++i) ASSERT_EQ(clock.advance(1.0f / 60.0f), 1) << "frame " << i;
}

TEST(FixedStepClock, VsyncJitterDoesNotStutterTheSteps) {
    // Real vsync timestamps wobble around the period; a frame at the step rate still runs one step.
    framerate::FixedStepClock clock(60.0f, 4);
    for (int i = 0; i < 600; ++i) {
        const float wobble = (i % 2 == 0 ? 1.0f : -1.0f) * 0.2f / 60.0f;
        ASSERT_EQ(clock.advance(1.0f / 60.0f + wobble), 1) << "frame " << i;
    }
}

TEST(FixedStepClock, StepsEveryOtherFrameAt120HzAndTwiceAFrameAt30Hz) {
    framerate::FixedStepClock fast(60.0f, 4);
    const int first = fast.advance(1.0f / 120.0f);
    const int second = fast.advance(1.0f / 120.0f);
    EXPECT_EQ(first + second, 1);
    EXPECT_EQ(fast.advance(1.0f / 120.0f), first);
    framerate::FixedStepClock slow(60.0f, 4);
    for (int i = 0; i < 60; ++i) ASSERT_EQ(slow.advance(1.0f / 30.0f), 2);
}

TEST(FixedStepClock, ALongFrameRunsAtMostItsCapAndDropsTheRest) {
    framerate::FixedStepClock clock(60.0f, 4);
    EXPECT_EQ(clock.advance(1.0f), 4);
    EXPECT_GE(clock.pending(), 0.0f);
    EXPECT_LT(clock.pending(), 1.0f);
    // Nothing was banked: the next ordinary frame runs one step, not the 56 the stall skipped.
    EXPECT_EQ(clock.advance(1.0f / 60.0f), 1);
}

TEST(FixedStepClock, StartsHalfAStepInAndResetsThere) {
    // Four steps a second and frames of a sixteenth of a second: a quarter step each, exactly.
    framerate::FixedStepClock clock(4.0f, 4);
    EXPECT_FLOAT_EQ(clock.pending(), framerate::FixedStepClock::kStartPending);
    EXPECT_EQ(clock.advance(0.0625f), 0);
    EXPECT_NEAR(clock.pending(), 0.75f, kTolerance);
    EXPECT_EQ(clock.advance(0.0625f), 1);
    EXPECT_NEAR(clock.pending(), 0.0f, kTolerance);
    clock.reset();
    EXPECT_FLOAT_EQ(clock.pending(), 0.5f);
}

TEST(FixedStepClock, ABrokenFrameRunsNothingAndKeepsItsPlace) {
    framerate::FixedStepClock clock(60.0f, 4);
    EXPECT_EQ(clock.advance(0.0f), 0);
    EXPECT_EQ(clock.advance(-1.0f), 0);
    EXPECT_EQ(clock.advance(kNaN), 0);
    EXPECT_EQ(clock.advance(kInf), 0);
    EXPECT_FLOAT_EQ(clock.pending(), 0.5f);
}

TEST(FixedStepClock, ANonsenseConfigurationStaysSafe) {
    framerate::FixedStepClock noCap(60.0f, 0);
    EXPECT_EQ(noCap.advance(0.1f), 1);
    framerate::FixedStepClock backwards(-60.0f, 4);
    EXPECT_EQ(backwards.advance(0.1f), 0);
    EXPECT_FLOAT_EQ(backwards.pending(), 0.5f);
}

TEST(EchoMotion, A60fpsFrameMovesByTheTunedAmount) {
    EXPECT_NEAR(sharedfx::echoZoom(0.3f, 1.0f / 60.0f), 0.3f, kTolerance);
    EXPECT_NEAR(sharedfx::echoZoom(-0.3f, 1.0f / 60.0f), -0.3f, kTolerance);
    EXPECT_NEAR(sharedfx::echoWarp(0.8f, 1.0f / 60.0f), 0.8f, kTolerance);
}

TEST(EchoMotion, TwoFramesAt120HzMoveAsFarAsOneAt60Hz) {
    const float at60 = 1.0f - sharedfx::echoZoom(0.5f, 1.0f / 60.0f) * sharedfx::kEchoZoomScale;
    const float at120 = 1.0f - sharedfx::echoZoom(0.5f, 1.0f / 120.0f) * sharedfx::kEchoZoomScale;
    EXPECT_NEAR(at120 * at120, at60, kTolerance);
    EXPECT_NEAR(sharedfx::echoWarp(0.8f, 1.0f / 120.0f), 0.4f, kTolerance);
    EXPECT_NEAR(sharedfx::echoWarp(0.8f, 1.0f / 30.0f), 1.6f, kTolerance);
}

TEST(EchoMotion, TheEchoStreamsAtTheSameSpeedAt30_60And120Hz) {
    for (const float zoom : {0.5f, -0.4f}) {
        const float reference = echoScale(zoom, 2.0f, 60.0f);
        for (const float hz : kPanelRates) EXPECT_NEAR(echoScale(zoom, 2.0f, hz), reference, 1e-3f) << zoom << " at " << hz << " Hz";
    }
}

TEST(EchoMotion, AStalledFrameOrABrokenAmountMovesNothing) {
    EXPECT_EQ(sharedfx::echoZoom(0.5f, 0.0f), 0.0f);
    EXPECT_EQ(sharedfx::echoZoom(kNaN, 1.0f / 60.0f), 0.0f);
    EXPECT_EQ(sharedfx::echoZoom(0.0f, 1.0f / 60.0f), 0.0f);
    EXPECT_EQ(sharedfx::echoWarp(0.8f, 0.0f), 0.0f);
    EXPECT_EQ(sharedfx::echoWarp(kNaN, 1.0f / 60.0f), 0.0f);
}

TEST(EchoMotion, AHugeZoomNeverFoldsThePictureThroughItsCentre) {
    // The per-frame scale is held at half, so the shader's (1 - zoom x 0.06) never reaches 0.
    EXPECT_NEAR(sharedfx::echoZoom(100.0f, 1.0f / 60.0f), 0.5f / sharedfx::kEchoZoomScale, kTolerance);
    EXPECT_GT(1.0f - sharedfx::echoZoom(100.0f, 1.0f / 30.0f) * sharedfx::kEchoZoomScale, 0.0f);
}

}  // namespace geode::viz
