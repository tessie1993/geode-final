#include "geode/viz/TouchField.hpp"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <initializer_list>
#include <limits>

namespace geode::viz {
namespace {

constexpr float kFrame = 1.0f / 60.0f;
constexpr float kTol = 1e-5f;

void touch(TouchField& field, std::initializer_list<float> xy) {
    std::array<float, TouchField::kMaxPoints * 2 + 4> buffer{};
    int i = 0;
    for (float v : xy) buffer[static_cast<size_t>(i++)] = v;
    field.submit(buffer.data(), i / 2);
}

void lift(TouchField& field) { field.submit(nullptr, 0); }

float releaseAfter(float seconds) { return std::exp(-seconds / TouchField::kReleaseTauSeconds); }

// Exact zeros: lib_touch.glsl's idle test (no gesture, no count, anchor strength 0) is an equality on
// what the CPU uploads. The anchor's position is left where it was; with no strength nothing reads it.
void expectIdle(const TouchField& f) {
    EXPECT_EQ(f.count(), 0);
    EXPECT_EQ(f.gesture(), TouchField::kGestureNone);
    EXPECT_EQ(f.anchorStrength(), 0.0f);
    EXPECT_EQ(f.spin(), 0.0f);
    for (float v : f.points()) EXPECT_EQ(v, 0.0f);
}

void expectUntouched(const TouchField& f) {
    expectIdle(f);
    EXPECT_EQ(f.anchorX(), 0.0f);
    EXPECT_EQ(f.anchorY(), 0.0f);
    EXPECT_EQ(f.axisX(), 0.0f);
    EXPECT_EQ(f.axisY(), 0.0f);
}

TEST(TouchField, UntouchedIsExactlyZero) {
    TouchField field;
    expectUntouched(field);
    for (int i = 0; i < 30; ++i) field.step(kFrame);
    expectUntouched(field);
    EXPECT_EQ(field.spread(), 0.0f);
}

TEST(TouchField, OneFingerIsAnAnchorThatArrivesWhereItIs) {
    TouchField field;
    touch(field, {0.25f, -0.5f});
    field.step(kFrame);
    EXPECT_EQ(field.count(), 1);
    EXPECT_EQ(field.gesture(), TouchField::kGestureAnchor);
    EXPECT_FLOAT_EQ(field.points()[0], 0.25f);
    EXPECT_FLOAT_EQ(field.points()[1], -0.5f);
    EXPECT_FLOAT_EQ(field.points()[2], 1.0f);
    EXPECT_FLOAT_EQ(field.points()[3], kFrame);
    // A new touch teleports the anchor: a new intent, not a continuation.
    EXPECT_FLOAT_EQ(field.anchorX(), 0.25f);
    EXPECT_FLOAT_EQ(field.anchorY(), -0.5f);
    EXPECT_FLOAT_EQ(field.anchorStrength(), 1.0f);
    EXPECT_FLOAT_EQ(field.anchorAge(), 0.0f);
    EXPECT_EQ(field.spread(), 0.0f);
    for (size_t i = TouchField::kPointStride; i < field.points().size(); ++i) EXPECT_EQ(field.points()[i], 0.0f);
}

TEST(TouchField, AHeldFingerAges) {
    TouchField field;
    touch(field, {0.0f, 0.0f});
    for (int i = 0; i < 3; ++i) field.step(kFrame);
    EXPECT_NEAR(field.points()[3], 3.0f * kFrame, kTol);
    EXPECT_FLOAT_EQ(field.points()[2], 1.0f);
}

TEST(TouchField, PointsAreClampedToTheSurface) {
    TouchField field;
    touch(field, {2.0f, -3.0f});
    field.step(kFrame);
    EXPECT_FLOAT_EQ(field.points()[0], 1.0f);
    EXPECT_FLOAT_EQ(field.points()[1], -1.0f);
    EXPECT_FLOAT_EQ(field.anchorX(), 1.0f);
    EXPECT_FLOAT_EQ(field.anchorY(), -1.0f);
}

TEST(TouchField, ANonFiniteSampleIsSkipped) {
    TouchField field;
    touch(field, {0.5f, 0.5f});
    field.step(kFrame);
    touch(field, {std::numeric_limits<float>::quiet_NaN(), 0.2f});
    field.step(kFrame);
    EXPECT_FLOAT_EQ(field.points()[0], 0.5f);
    EXPECT_FLOAT_EQ(field.points()[1], 0.5f);
    EXPECT_TRUE(std::isfinite(field.anchorX()));
    EXPECT_TRUE(std::isfinite(field.anchorY()));
}

TEST(TouchField, TheLatestSubmissionWins) {
    TouchField field;
    touch(field, {-0.5f, -0.5f});
    touch(field, {0.5f, 0.5f, 0.1f, 0.1f});
    touch(field, {0.75f, 0.25f});
    field.step(kFrame);
    EXPECT_EQ(field.count(), 1);
    EXPECT_FLOAT_EQ(field.anchorX(), 0.75f);
    EXPECT_FLOAT_EQ(field.anchorY(), 0.25f);
}

TEST(TouchField, TheAnchorChasesAMovingFingerAtItsOwnRate) {
    TouchField field;
    touch(field, {0.0f, 0.0f});
    field.step(kFrame);
    touch(field, {0.6f, -0.3f});
    field.step(kFrame);
    const float k = 1.0f - std::exp(-kFrame / TouchField::kAnchorTauSeconds);
    EXPECT_NEAR(field.anchorX(), 0.6f * k, kTol);
    EXPECT_NEAR(field.anchorY(), -0.3f * k, kTol);
    // The published point is where the finger is, not where the anchor has got to.
    EXPECT_FLOAT_EQ(field.points()[0], 0.6f);
    for (int i = 0; i < 60; ++i) field.step(kFrame);
    EXPECT_NEAR(field.anchorX(), 0.6f, 1e-4f);
    EXPECT_NEAR(field.anchorY(), -0.3f, 1e-4f);
}

TEST(TouchField, ALiftedFingerLeavesAWakeThatFades) {
    TouchField field;
    touch(field, {0.3f, 0.4f});
    field.step(kFrame);
    lift(field);
    field.step(kFrame);
    EXPECT_EQ(field.gesture(), TouchField::kGestureNone);
    EXPECT_EQ(field.count(), 1);
    EXPECT_FLOAT_EQ(field.points()[0], 0.3f);
    EXPECT_FLOAT_EQ(field.points()[1], 0.4f);
    EXPECT_NEAR(field.points()[2], releaseAfter(kFrame), kTol);
    EXPECT_NEAR(field.points()[3], 2.0f * kFrame, kTol);
    EXPECT_NEAR(field.anchorStrength(), releaseAfter(kFrame), kTol);
    EXPECT_NEAR(field.anchorAge(), kFrame, kTol);
    for (int i = 0; i < 9; ++i) field.step(kFrame);
    EXPECT_NEAR(field.points()[2], releaseAfter(10.0f * kFrame), 1e-4f);
    EXPECT_NEAR(field.anchorStrength(), releaseAfter(10.0f * kFrame), 1e-4f);
    EXPECT_NEAR(field.anchorAge(), 10.0f * kFrame, 1e-4f);
}

TEST(TouchField, ASpentWakeReturnsToExactZeroAndTheNextTouchTeleports) {
    TouchField field;
    touch(field, {0.3f, 0.4f});
    field.step(kFrame);
    lift(field);
    // ln(1/0.004) * 0.55 s is about 3 s.
    for (int i = 0; i < 4 * 60; ++i) field.step(kFrame);
    expectIdle(field);
    touch(field, {-0.7f, 0.1f});
    field.step(kFrame);
    EXPECT_FLOAT_EQ(field.anchorX(), -0.7f);
    EXPECT_FLOAT_EQ(field.anchorY(), 0.1f);
}

TEST(TouchField, AWakeIsSpentAtItsThresholdAndNotBefore) {
    TouchField field;
    touch(field, {0.0f, 0.0f});
    field.step(kFrame);
    lift(field);
    // Exactly the frame on which exp(-t / tau) first drops under 0.004.
    const int spentFrame = static_cast<int>(std::ceil(-std::log(0.004f) * TouchField::kReleaseTauSeconds / kFrame));
    for (int i = 1; i < spentFrame - 1; ++i) field.step(kFrame);
    EXPECT_GT(field.points()[2], 0.0f);
    EXPECT_GT(field.anchorStrength(), 0.0f);
    EXPECT_EQ(field.count(), 1);
    for (int i = 0; i < 3; ++i) field.step(kFrame);
    EXPECT_EQ(field.points()[2], 0.0f);
    EXPECT_EQ(field.anchorStrength(), 0.0f);
    EXPECT_EQ(field.count(), 0);
}

TEST(TouchField, TwoFingersAreAnAxis) {
    TouchField field;
    touch(field, {-0.2f, 0.1f, 0.4f, 0.9f});
    field.step(kFrame);
    EXPECT_EQ(field.count(), 2);
    EXPECT_EQ(field.gesture(), TouchField::kGestureAxis);
    EXPECT_FLOAT_EQ(field.axisX(), 0.6f);
    EXPECT_FLOAT_EQ(field.axisY(), 0.8f);
    EXPECT_NEAR(field.spread(), 1.0f, kTol);
    EXPECT_FLOAT_EQ(field.points()[4], 0.4f);
    EXPECT_FLOAT_EQ(field.points()[5], 0.9f);
    // The anchor is the first finger.
    EXPECT_FLOAT_EQ(field.anchorX(), -0.2f);
    lift(field);
    field.step(kFrame);
    EXPECT_NEAR(field.axisX(), 0.6f * releaseAfter(kFrame), kTol);
    EXPECT_NEAR(field.axisY(), 0.8f * releaseAfter(kFrame), kTol);
    EXPECT_EQ(field.spread(), 0.0f);
}

TEST(TouchField, OnlyTheReleasedSlotsDecay) {
    TouchField field;
    touch(field, {-0.5f, 0.0f, 0.5f, 0.0f});
    field.step(kFrame);
    touch(field, {-0.5f, 0.0f});
    field.step(kFrame);
    EXPECT_EQ(field.gesture(), TouchField::kGestureAnchor);
    EXPECT_EQ(field.count(), 2);
    EXPECT_FLOAT_EQ(field.points()[2], 1.0f);
    EXPECT_NEAR(field.points()[6], releaseAfter(kFrame), kTol);
    EXPECT_NEAR(field.points()[7], 2.0f * kFrame, kTol);
}

TEST(TouchField, ThreeFingersTracedAroundTheirCentreSpin) {
    const auto ring = [](TouchField& field, float turn) {
        std::array<float, 6> xy{};
        for (int i = 0; i < 3; ++i) {
            const float a = turn + 2.0943951f * static_cast<float>(i);
            xy[static_cast<size_t>(i * 2)] = 0.5f * std::cos(a);
            xy[static_cast<size_t>(i * 2 + 1)] = 0.5f * std::sin(a);
        }
        field.submit(xy.data(), 3);
    };
    const float k = 1.0f - std::exp(-kFrame / 0.25f);
    const float rate = std::sin(0.05f) / kFrame;

    TouchField ccw;
    ring(ccw, 0.0f);
    ccw.step(kFrame);
    EXPECT_EQ(ccw.gesture(), TouchField::kGestureVortex);
    // The first frame of a gesture has no motion to read.
    EXPECT_EQ(ccw.spin(), 0.0f);
    ring(ccw, 0.05f);
    ccw.step(kFrame);
    EXPECT_NEAR(ccw.spin(), rate * k, 1e-3f);

    TouchField cw;
    ring(cw, 0.0f);
    cw.step(kFrame);
    ring(cw, -0.05f);
    cw.step(kFrame);
    EXPECT_NEAR(cw.spin(), -rate * k, 1e-3f);

    // Held still (the host keeps reporting the same places), the spin settles back toward zero.
    for (int i = 0; i < 120; ++i) {
        ring(cw, -0.05f);
        cw.step(kFrame);
    }
    EXPECT_NEAR(cw.spin(), 0.0f, 1e-3f);
}

TEST(TouchField, AFlungVortexIsClamped) {
    TouchField field;
    std::array<float, 6> a{0.0f, 0.0f, 0.02f, 0.0f, 0.0f, 0.02f};
    field.submit(a.data(), 3);
    field.step(kFrame);
    std::array<float, 6> b{1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
    field.submit(b.data(), 3);
    for (int i = 0; i < 600; ++i) {
        field.step(kFrame);
        EXPECT_LE(std::fabs(field.spin()), 8.0f) << "frame " << i;
    }
}

TEST(TouchField, NoMoreThanFiveFingers) {
    TouchField field;
    std::array<float, 14> xy{};
    for (int i = 0; i < 7; ++i) xy[static_cast<size_t>(i * 2)] = 0.1f * static_cast<float>(i);
    field.submit(xy.data(), 7);
    field.step(kFrame);
    EXPECT_EQ(field.count(), TouchField::kMaxPoints);
    EXPECT_EQ(field.gesture(), TouchField::kGestureVortex);
    EXPECT_FLOAT_EQ(field.points()[(TouchField::kMaxPoints - 1) * TouchField::kPointStride], 0.4f);
    field.submit(xy.data(), -3);
    field.step(kFrame);
    EXPECT_EQ(field.gesture(), TouchField::kGestureNone);
}

TEST(TouchField, ResetDropsEverything) {
    TouchField field;
    touch(field, {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f});
    field.step(kFrame);
    touch(field, {0.9f, 0.9f});
    field.reset();
    expectIdle(field);
    EXPECT_EQ(field.axisX(), 0.0f);
    EXPECT_EQ(field.axisY(), 0.0f);
    EXPECT_EQ(field.anchorAge(), 0.0f);
    // A reset is a lift too: the touch submitted before it does not come back.
    field.step(kFrame);
    expectIdle(field);
}

TEST(TouchField, AFrameCountsForAtMostATenthOfASecond) {
    TouchField once;
    TouchField tenth;
    touch(once, {0.0f, 0.0f});
    touch(tenth, {0.0f, 0.0f});
    once.step(kFrame);
    tenth.step(kFrame);
    lift(once);
    lift(tenth);
    once.step(10.0f);
    tenth.step(0.1f);
    EXPECT_FLOAT_EQ(once.points()[2], tenth.points()[2]);
    EXPECT_NEAR(once.points()[2], releaseAfter(0.1f), kTol);
    once.step(-1.0f);
    EXPECT_FLOAT_EQ(once.points()[2], tenth.points()[2]);
}

}  // namespace
}  // namespace geode::viz
