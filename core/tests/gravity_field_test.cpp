#include "geode/viz/GravityField.hpp"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <limits>

namespace geode::viz {
namespace {

constexpr float kFrame = 1.0f / 60.0f;

void run(GravityField& field, float seconds, float dt = kFrame, bool reducedMotion = false) {
    const int frames = static_cast<int>(std::lround(seconds / dt));
    for (int i = 0; i < frames; ++i) field.step(dt, reducedMotion);
}

float gravityLength(const GravityField& f) { return std::sqrt(f.x() * f.x() + f.y() * f.y() + f.z() * f.z()); }
float tiltLength(const GravityField& f) { return std::hypot(f.tiltX(), f.tiltY()); }

void expectExactlyZero(const GravityField& f) {
    // Exact equality on purpose: the idle guarantee is bit-identical uniforms, not "nearly zero".
    EXPECT_EQ(f.x(), 0.0f);
    EXPECT_EQ(f.y(), 0.0f);
    EXPECT_EQ(f.z(), 0.0f);
    EXPECT_EQ(f.tiltX(), 0.0f);
    EXPECT_EQ(f.tiltY(), 0.0f);
    EXPECT_EQ(f.engagement(), 0.0f);
    EXPECT_TRUE(f.idle());
}

TEST(GravityField, WithNoReadingEveryValueIsExactlyZero) {
    GravityField field;
    expectExactlyZero(field);
    run(field, 2.0f);
    expectExactlyZero(field);
    run(field, 1.0f, kFrame, true);
    expectExactlyZero(field);
}

TEST(GravityField, AReadingWithNoDirectionIsNoReading) {
    GravityField field;
    field.submit(0.0f, 0.0f, 0.0f);
    run(field, 1.0f);
    expectExactlyZero(field);
    field.submit(GravityField::kMinReading * 0.5f, 0.0f, 0.0f);
    run(field, 1.0f);
    expectExactlyZero(field);
    field.submit(std::numeric_limits<float>::quiet_NaN(), -1.0f, 0.0f);
    run(field, 1.0f);
    expectExactlyZero(field);
    field.submit(0.0f, -std::numeric_limits<float>::infinity(), 0.0f);
    run(field, 1.0f);
    expectExactlyZero(field);
    field.submit(0.0f, -3.0e38f, -3.0e38f);
    run(field, 1.0f);
    expectExactlyZero(field);
}

TEST(GravityField, EngagesAlongTheReadingWithoutJumping) {
    GravityField field;
    field.submit(0.0f, -9.81f, 0.0f);
    field.step(kFrame, false);
    // One frame in, the field points the right way but has barely begun to grow.
    EXPECT_FALSE(field.idle());
    EXPECT_EQ(field.x(), 0.0f);
    EXPECT_LT(field.y(), 0.0f);
    EXPECT_GT(field.y(), -0.05f);
    EXPECT_EQ(field.z(), 0.0f);
    float last = gravityLength(field);
    for (int i = 0; i < 180; ++i) {
        field.step(kFrame, false);
        const float now = gravityLength(field);
        EXPECT_GE(now, last) << "frame " << i;
        EXPECT_LE(now, 1.0f + 1e-6f) << "frame " << i;
        last = now;
    }
    EXPECT_NEAR(field.y(), -1.0f, 1e-3f);
    EXPECT_NEAR(field.engagement(), 1.0f, 1e-3f);
    // Engaging is not tilting: the resting pose starts where the phone is.
    EXPECT_EQ(field.tiltX(), 0.0f);
    EXPECT_EQ(field.tiltY(), 0.0f);
}

TEST(GravityField, TheReadingIsNormalised) {
    GravityField field;
    field.submit(0.0f, 0.0f, -40.0f);
    run(field, 3.0f);
    EXPECT_NEAR(field.z(), -1.0f, 1e-3f);
    EXPECT_NEAR(gravityLength(field), 1.0f, 1e-3f);
}

TEST(GravityField, TheLatestReadingWins) {
    GravityField field;
    field.submit(1.0f, 0.0f, 0.0f);
    field.submit(0.0f, 0.0f, 0.0f);
    field.submit(0.0f, -1.0f, 0.0f);
    run(field, 3.0f);
    EXPECT_NEAR(field.x(), 0.0f, 1e-6f);
    EXPECT_NEAR(field.y(), -1.0f, 1e-3f);

    field.submit(0.0f, -1.0f, 0.0f);
    field.submit(0.0f, 0.0f, 0.0f);
    run(field, 4.0f);
    expectExactlyZero(field);
}

TEST(GravityField, TurnsWithoutOvershootingTheNewDirection) {
    GravityField field;
    field.submit(0.0f, -1.0f, 0.0f);
    run(field, 3.0f);
    const float d = std::sqrt(0.5f);
    field.submit(d, -d, 0.0f);
    float lastX = field.x();
    float lastY = field.y();
    for (int i = 0; i < 240; ++i) {
        field.step(kFrame, false);
        // Each component moves one way only and never past where it is going: no ringing.
        EXPECT_GE(field.x(), lastX - 1e-6f) << "frame " << i;
        EXPECT_LE(field.x(), d + 1e-6f) << "frame " << i;
        EXPECT_GE(field.y(), lastY - 1e-6f) << "frame " << i;
        EXPECT_LE(field.y(), -d + 1e-3f) << "frame " << i;
        lastX = field.x();
        lastY = field.y();
    }
    EXPECT_NEAR(field.x(), d, 1e-3f);
    EXPECT_NEAR(field.y(), -d, 1e-3f);
}

TEST(GravityField, NeverMovesFasterThanItsGlide) {
    GravityField field;
    field.submit(0.0f, -1.0f, 0.0f);
    run(field, 6.0f);
    // The largest turn a hand gives in one reading (just under the reseed angle).
    const float a = std::acos(GravityField::kReseedCosine) - 0.01f;
    field.submit(std::sin(a), -std::cos(a), 0.0f);
    float lastX = field.x();
    for (int i = 0; i < 120; ++i) {
        field.step(kFrame, false);
        EXPECT_LE(std::fabs(field.x() - lastX), GravityField::kMaxSpeed * kFrame + 1e-4f) << "frame " << i;
        lastX = field.x();
    }
    EXPECT_NEAR(field.x(), std::sin(a), 1e-3f);
}

TEST(GravityField, LettingGoEasesOutToExactZero) {
    GravityField field;
    field.submit(0.6f, -0.8f, 0.0f);
    run(field, 3.0f);
    field.submit(0.0f, 0.0f, 0.0f);
    field.step(kFrame, false);
    // Release has inertia: one frame later almost all of it is still there.
    EXPECT_GT(gravityLength(field), 0.95f);
    EXPECT_NEAR(field.x() / field.y(), 0.6f / -0.8f, 1e-4f);
    float last = gravityLength(field);
    bool reachedZero = false;
    for (int i = 0; i < 600 && !reachedZero; ++i) {
        field.step(kFrame, false);
        const float now = gravityLength(field);
        EXPECT_LE(now, last + 1e-6f) << "frame " << i;
        last = now;
        reachedZero = field.idle();
    }
    EXPECT_TRUE(reachedZero);
    expectExactlyZero(field);
}

TEST(GravityField, ReduceMotionHoldsTheFieldAtLevel) {
    GravityField field;
    field.submit(0.0f, -1.0f, 0.0f);
    run(field, 3.0f);
    run(field, 4.0f, kFrame, true);
    expectExactlyZero(field);
    // Readings keep arriving while it is on; the field stays level.
    field.submit(0.5f, -0.5f, 0.0f);
    run(field, 2.0f, kFrame, true);
    expectExactlyZero(field);
    // Off again, the latest reading engages the field (at its length, 0.71), with no tilt from the
    // time it was still.
    run(field, 3.0f);
    EXPECT_NEAR(field.weight(), std::sqrt(0.5f), 1e-3f);
    EXPECT_NEAR(field.x(), 0.5f, 1e-3f);
    EXPECT_NEAR(field.y(), -0.5f, 1e-3f);
    EXPECT_NEAR(tiltLength(field), 0.0f, 1e-6f);
}

TEST(GravityField, ATiltMovesAwayFromRestAndSettlesBack) {
    GravityField field;
    field.submit(0.0f, -1.0f, 0.0f);
    run(field, 4.0f);
    EXPECT_NEAR(tiltLength(field), 0.0f, 1e-6f);
    // The right edge goes down.
    field.submit(0.5f, -0.866f, 0.0f);
    run(field, 0.5f);
    const float tilted = field.tiltX();
    EXPECT_GT(tilted, 0.2f);
    // Down moved toward the bottom-right, so its y component rose from -1 as well.
    EXPECT_GT(field.tiltY(), 0.0f);
    EXPECT_LT(field.tiltY(), tilted);
    // Held there, the resting pose catches up and the tilt comes home.
    run(field, 15.0f);
    EXPECT_LT(field.tiltX(), tilted * 0.05f);
    EXPECT_GE(field.tiltX(), 0.0f);
    EXPECT_NEAR(field.x(), 0.5f, 2e-3f);
}

TEST(GravityField, TheTiltIsClamped) {
    GravityField field;
    field.submit(0.0f, -1.0f, 0.0f);
    run(field, 4.0f);
    float longest = 0.0f;
    for (int i = 0; i < 240; ++i) {
        // A steady turn of 90 degrees a second, a little each reading.
        const float a = std::min(1.5707963f, 1.5707963f * static_cast<float>(i) * kFrame);
        field.submit(std::sin(a), -std::cos(a), 0.0f);
        field.step(kFrame, false);
        longest = std::max(longest, tiltLength(field));
        EXPECT_LE(tiltLength(field), GravityField::kMaxTilt * field.engagement() + 1e-5f) << "frame " << i;
    }
    EXPECT_NEAR(longest, GravityField::kMaxTilt, 1e-3f);
}

TEST(GravityField, TheSameMotionAtAnyFrameRate) {
    GravityField at60;
    GravityField at120;
    at60.submit(0.0f, -1.0f, 0.0f);
    at120.submit(0.0f, -1.0f, 0.0f);
    run(at60, 1.0f, 1.0f / 60.0f);
    run(at120, 1.0f, 1.0f / 120.0f);
    at60.submit(0.6f, -0.8f, 0.0f);
    at120.submit(0.6f, -0.8f, 0.0f);
    run(at60, 0.25f, 1.0f / 60.0f);
    run(at120, 0.25f, 1.0f / 120.0f);
    EXPECT_NEAR(at60.x(), at120.x(), 0.02f);
    EXPECT_NEAR(at60.tiltX(), at120.tiltX(), 0.02f);
    EXPECT_NEAR(at60.engagement(), at120.engagement(), 3e-3f);
}

TEST(GravityField, AFrameCountsForAtMostATenthOfASecond) {
    GravityField once;
    GravityField tenth;
    once.submit(0.0f, -1.0f, 0.0f);
    tenth.submit(0.0f, -1.0f, 0.0f);
    once.step(5.0f, false);
    tenth.step(0.1f, false);
    EXPECT_FLOAT_EQ(once.y(), tenth.y());
    EXPECT_FLOAT_EQ(once.engagement(), tenth.engagement());
    EXPECT_GT(once.engagement(), 0.0f);
    EXPECT_LT(once.engagement(), 0.5f);

    GravityField broken;
    broken.submit(0.0f, -1.0f, 0.0f);
    broken.step(std::numeric_limits<float>::quiet_NaN(), false);
    broken.step(-1.0f, false);
    // A frame of no time adopts the reading but moves nothing.
    EXPECT_EQ(broken.engagement(), 0.0f);
    broken.step(kFrame, false);
    EXPECT_GT(broken.engagement(), 0.0f);
}

TEST(GravityField, ResetDropsEverythingAtOnce) {
    GravityField field;
    field.submit(0.0f, -1.0f, 0.0f);
    run(field, 2.0f);
    field.submit(1.0f, 0.0f, 0.0f);
    field.reset();
    expectExactlyZero(field);
    // The reading submitted before the reset is gone with it.
    run(field, 1.0f);
    expectExactlyZero(field);
}

TEST(GravityField, ALaterReadingDuringReleaseContinuesFromWhereItIs) {
    GravityField field;
    field.submit(0.0f, -1.0f, 0.0f);
    run(field, 3.0f);
    field.submit(0.0f, 0.0f, 0.0f);
    run(field, 0.2f);
    const float fading = field.y();
    field.submit(1.0f, 0.0f, 0.0f);
    field.step(kFrame, false);
    // No snap to the new direction: it is still mostly down, and turns from there.
    EXPECT_LT(field.y(), fading * 0.8f);
    EXPECT_LT(field.x(), 0.1f);
}

TEST(GravityField, TheReadingsLengthIsTheWeight) {
    GravityField subtle;
    subtle.submit(0.0f, -0.5f, 0.0f);
    run(subtle, 4.0f);
    EXPECT_NEAR(subtle.weight(), 0.5f, 1e-3f);
    EXPECT_NEAR(subtle.y(), -0.5f, 1e-3f);
    GravityField full;
    full.submit(0.0f, -9.81f, 0.0f);
    run(full, 4.0f);
    EXPECT_NEAR(full.weight(), 1.0f, 1e-3f);
    EXPECT_LE(full.weight(), 1.0f);
}

TEST(GravityField, ADisplayRotationStartsTheDirectionAndRestAgain) {
    GravityField field;
    field.submit(0.0f, -1.0f, 0.0f);
    run(field, 4.0f);
    // A quarter turn between two readings is the picture turning, not the hand.
    field.submit(-1.0f, 0.0f, 0.0f);
    field.step(kFrame, false);
    EXPECT_NEAR(field.x(), -field.weight(), 1e-6f);
    EXPECT_NEAR(field.y(), 0.0f, 1e-6f);
    EXPECT_EQ(field.tiltX(), 0.0f);
    EXPECT_EQ(field.tiltY(), 0.0f);
}

TEST(GravityInPlane, FadesOutNearFlatAndIsExactlyZeroWithNoWeight) {
    float out[2] = {7.0f, 7.0f};
    gravity::inPlaneDown(0.0f, -1.0f, 0.0f, out);
    EXPECT_EQ(out[0], 0.0f);
    EXPECT_EQ(out[1], 0.0f);
    gravity::inPlaneDown(0.0f, -0.05f, 1.0f, out);
    EXPECT_EQ(out[1], 0.0f);
    gravity::inPlaneDown(0.0f, -0.6f, 1.0f, out);
    EXPECT_FLOAT_EQ(out[1], -1.0f);
    gravity::inPlaneDown(0.3f, -0.4f, 0.5f, out);
    EXPECT_FLOAT_EQ(out[0], 0.3f);
    EXPECT_FLOAT_EQ(out[1], -0.4f);
    // Half way through the fade, half the push (smoothstep at its midpoint).
    gravity::inPlaneDown(0.0f, -0.14f, 1.0f, out);
    EXPECT_NEAR(out[1], -0.5f, 1e-5f);
    gravity::inPlaneDown(std::numeric_limits<float>::quiet_NaN(), -1.0f, 1.0f, out);
    EXPECT_EQ(out[0], 0.0f);
    EXPECT_EQ(out[1], 0.0f);
    gravity::inPlaneDown(0.0f, -1.0f, 3.0f, out);
    EXPECT_FLOAT_EQ(out[1], -1.0f);
}

TEST(GravityLook, NoTiltIsExactlyTheIdentity) {
    const auto r = gravity::lookRotation(0.0f, 0.0f);
    const std::array<float, 9> identity{1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    EXPECT_EQ(r, identity);
    EXPECT_EQ(gravity::lookRotation(std::numeric_limits<float>::quiet_NaN(), 0.0f), identity);
}

TEST(GravityLook, TheRightEdgeDownLooksRightAndTheTopAwayLooksDown) {
    const auto right = gravity::lookRotation(0.5f, 0.0f);
    // The third column is where the camera's forward axis now points.
    EXPECT_NEAR(right[6], std::sin(0.5f * gravity::kLookRadians), 1e-6f);
    EXPECT_NEAR(right[7], 0.0f, 1e-6f);
    EXPECT_NEAR(right[8], std::cos(0.5f * gravity::kLookRadians), 1e-6f);
    const auto down = gravity::lookRotation(0.0f, 0.5f);
    EXPECT_NEAR(down[6], 0.0f, 1e-6f);
    EXPECT_NEAR(down[7], -std::sin(0.5f * gravity::kLookRadians), 1e-6f);
    EXPECT_NEAR(down[8], std::cos(0.5f * gravity::kLookRadians), 1e-6f);
}

TEST(GravityLook, TheRotationIsOrthonormal) {
    const auto r = gravity::lookRotation(0.4f, -0.3f);
    const auto product = gravity::multiply(r, {r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8]});
    for (size_t col = 0; col < 3; ++col) {
        for (size_t row = 0; row < 3; ++row) EXPECT_NEAR(product[col * 3 + row], col == row ? 1.0f : 0.0f, 1e-5f);
    }
}

TEST(GravityLook, MultiplyIsColumnMajor) {
    // a scales x by 2; b moves x into y. a * b applies b first.
    const std::array<float, 9> a{2.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    const std::array<float, 9> b{0.0f, 1.0f, 0.0f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 3.0f};
    const auto ab = gravity::multiply(a, b);
    const std::array<float, 9> expected{0.0f, 1.0f, 0.0f, -2.0f, 0.0f, 0.0f, 0.0f, 0.0f, 3.0f};
    EXPECT_EQ(ab, expected);
    const auto ba = gravity::multiply(b, a);
    const std::array<float, 9> expectedBa{0.0f, 2.0f, 0.0f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 3.0f};
    EXPECT_EQ(ba, expectedBa);
}

}  // namespace
}  // namespace geode::viz
