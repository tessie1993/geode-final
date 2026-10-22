#include "geode/viz/Reactions.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <limits>

namespace geode::viz::mod {
namespace {

constexpr float kTolerance = 1e-5f;
constexpr float kFrameDt = 1.0f / 60.0f;

// The numbers below are the ones Evaluation.kt (feature/customization/core) gives for the same
// inputs: the Kotlin reducer owns the meaning, the engine is held to it (ADR-0002).

TEST(Normalise, NeutralTrimPassesTheValueThroughClamped) {
    EXPECT_NEAR(normalise(0.4f, SignalTrim{}), 0.4f, kTolerance);
    EXPECT_NEAR(normalise(1.7f, SignalTrim{}), 1.0f, kTolerance);
    EXPECT_NEAR(normalise(-0.3f, SignalTrim{}), 0.0f, kTolerance);
}

TEST(Normalise, GainScalesAndTheFloorIsTakenOffAndStretched) {
    // 0.3 * 2 = 0.6; above the 0.2 floor by 0.4, over the 0.8 that is left: 0.5.
    EXPECT_NEAR(normalise(0.3f, SignalTrim{2.0f, 0.2f}), 0.5f, kTolerance);
    // At or below the floor it reads as nothing.
    EXPECT_EQ(normalise(0.1f, SignalTrim{2.0f, 0.2f}), 0.0f);
    EXPECT_EQ(normalise(0.2f, SignalTrim{1.0f, 0.2f}), 0.0f);
}

TEST(Normalise, NotANumberReadsAsNothing) {
    EXPECT_EQ(normalise(std::numeric_limits<float>::quiet_NaN(), SignalTrim{}), 0.0f);
}

TEST(Normalise, AnOutOfRangeTrimIsBroughtBackInsteadOfBlowingUp) {
    // A floor of 1 would divide by zero; it is held at the highest allowed floor, 0.9.
    EXPECT_NEAR(normalise(0.95f, SignalTrim{1.0f, 1.0f}), 0.5f, kTolerance);
    // A gain above 4 is held at 4.
    EXPECT_NEAR(normalise(0.1f, SignalTrim{100.0f, 0.0f}), 0.4f, kTolerance);
    EXPECT_EQ(normalise(0.5f, SignalTrim{-3.0f, 0.0f}), 0.0f);
}

TEST(Curve, EachCurveBendsTheInputAsTheModelDefinesIt) {
    EXPECT_NEAR(curve(0.25f, Curve::Linear), 0.25f, kTolerance);
    EXPECT_NEAR(curve(0.5f, Curve::Exponential), 0.25f, kTolerance);
    EXPECT_NEAR(curve(0.25f, Curve::Logarithmic), 0.5f, kTolerance);
    // 3x^2 - 2x^3 at 0.25: 0.1875 - 0.03125.
    EXPECT_NEAR(curve(0.25f, Curve::SCurve), 0.15625f, kTolerance);
    EXPECT_NEAR(curve(0.5f, Curve::SCurve), 0.5f, kTolerance);
}

TEST(Curve, InputOutsideZeroToOneIsClampedFirst) {
    EXPECT_EQ(curve(2.0f, Curve::Exponential), 1.0f);
    EXPECT_EQ(curve(-1.0f, Curve::Logarithmic), 0.0f);
    EXPECT_EQ(curve(std::numeric_limits<float>::quiet_NaN(), Curve::Linear), 0.0f);
}

TEST(React, AddsTheCurvedInputTimesTheAmount) {
    ReactionShape shape;
    shape.amount = 0.5f;
    EXPECT_NEAR(react(0.2f, 0.8f, shape), 0.6f, kTolerance);
}

TEST(React, InvertTakesAwayInstead) {
    ReactionShape shape;
    shape.amount = 0.5f;
    shape.invert = true;
    EXPECT_NEAR(react(0.7f, 0.8f, shape), 0.3f, kTolerance);
}

TEST(React, TheResultStaysInsideTheReactionsRange) {
    ReactionShape shape;
    shape.low = 0.3f;
    shape.high = 0.6f;
    EXPECT_NEAR(react(0.5f, 1.0f, shape), 0.6f, kTolerance);
    shape.invert = true;
    EXPECT_NEAR(react(0.5f, 1.0f, shape), 0.3f, kTolerance);
    EXPECT_NEAR(react(0.5f, 0.0f, shape), 0.5f, kTolerance);
}

TEST(React, AReversedOrBrokenShapeIsMadeSafe) {
    ReactionShape shape;
    shape.low = 0.8f;
    shape.high = 0.2f;
    shape.amount = 5.0f;
    // The range is put in order and the amount kept to 1: 0.1 + 1 -> held at 0.8.
    EXPECT_NEAR(react(0.1f, 1.0f, shape), 0.8f, kTolerance);
    shape.amount = std::numeric_limits<float>::quiet_NaN();
    EXPECT_NEAR(react(0.5f, 1.0f, shape), 0.5f, kTolerance);
}

TEST(Follower, WithNoRiseOrFallItTakesTheInputAtOnce) {
    Follower f;
    EXPECT_EQ(f.step(0.9f, 0.0f, 0.0f, kFrameDt), 0.9f);
    EXPECT_EQ(f.step(0.1f, 0.0f, 0.0f, kFrameDt), 0.1f);
}

TEST(Follower, RiseIsTheTimeAFullSwingTakesUpwards) {
    Follower f;
    // A 0.1 s rise: each 1/60 s frame moves one sixth of the way, so 6 frames to get from 0 to 1.
    float v = 0.0f;
    for (int i = 0; i < 3; ++i) v = f.step(1.0f, 0.1f, 0.0f, kFrameDt);
    EXPECT_NEAR(v, 0.5f, 1e-4f);
    for (int i = 0; i < 3; ++i) v = f.step(1.0f, 0.1f, 0.0f, kFrameDt);
    EXPECT_NEAR(v, 1.0f, 1e-4f);
    // It never overshoots.
    EXPECT_EQ(f.step(1.0f, 0.1f, 0.0f, kFrameDt), 1.0f);
}

TEST(Follower, FallIsTheTimeAFullSwingTakesDownwards) {
    Follower f;
    f.step(1.0f, 0.0f, 0.0f, kFrameDt);
    EXPECT_NEAR(f.step(0.0f, 0.0f, 0.2f, 0.05f), 0.75f, kTolerance);
    EXPECT_NEAR(f.step(0.0f, 0.0f, 0.2f, 0.05f), 0.5f, kTolerance);
    // The rise time does not slow a fall.
    EXPECT_NEAR(f.step(0.0f, 10.0f, 0.2f, 0.05f), 0.25f, kTolerance);
}

TEST(Follower, AStoppedOrBackwardsClockMovesNothing) {
    Follower f;
    f.step(0.5f, 0.0f, 0.0f, kFrameDt);
    EXPECT_EQ(f.step(1.0f, 0.1f, 0.1f, 0.0f), 0.5f);
    EXPECT_EQ(f.step(1.0f, 0.1f, 0.1f, -1.0f), 0.5f);
    f.reset();
    EXPECT_EQ(f.value(), 0.0f);
}

TEST(Follower, ABadInputHoldsWhereItWas) {
    Follower f;
    f.step(0.4f, 0.0f, 0.0f, kFrameDt);
    EXPECT_EQ(f.step(std::numeric_limits<float>::quiet_NaN(), 0.0f, 0.0f, kFrameDt), 0.4f);
    EXPECT_EQ(f.step(std::numeric_limits<float>::infinity(), 0.0f, 0.0f, kFrameDt), 1.0f);
}

TEST(Follower, ItNeverMovesFasterThanItsRiseAllows) {
    Follower f;
    float last = 0.0f;
    for (int i = 0; i < 120; ++i) {
        const float target = (i / 3) % 2 == 0 ? 1.0f : 0.0f;
        const float v = f.step(target, kNoFlashMinRiseSeconds, 0.0f, kFrameDt);
        if (v > last) EXPECT_LE(v - last, kFrameDt / kNoFlashMinRiseSeconds + kTolerance);
        last = v;
    }
}

}  // namespace
}  // namespace geode::viz::mod
