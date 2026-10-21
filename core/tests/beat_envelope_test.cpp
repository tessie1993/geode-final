#include "geode/viz/BeatEnvelope.hpp"

#include <gtest/gtest.h>

namespace geode::viz {
namespace {

constexpr float kFrameDt = 1.0f / 60.0f;
constexpr float kTolerance = 1e-5f;
constexpr float kRisePerFrame = BeatEnvelope::kRisePerSecond * kFrameDt;

TEST(BeatEnvelope, RisesOverTheAttackInsteadOfJumping) {
    BeatEnvelope beat;
    EXPECT_NEAR(beat.step(1.0f, kFrameDt), kRisePerFrame, kTolerance);
    EXPECT_NEAR(beat.step(0.0f, kFrameDt), 2.0f * kRisePerFrame, kTolerance);
    EXPECT_NEAR(beat.value(), 2.0f * kRisePerFrame, kTolerance);
}

TEST(BeatEnvelope, ReachesTheDecayingPeakAndThenFollowsItDown) {
    BeatEnvelope beat;
    float v = 0.0f;
    for (int i = 0; i < 6; ++i) v = beat.step(i == 0 ? 1.0f : 0.0f, kFrameDt);
    // The peak has decayed for five frames by the time the rise meets it.
    const float peakNow = 1.0f - 5.0f * BeatEnvelope::kFallPerSecond * kFrameDt;
    EXPECT_NEAR(v, 6.0f * kRisePerFrame < peakNow ? 6.0f * kRisePerFrame : peakNow, kTolerance);
    for (int i = 0; i < 4; ++i) v = beat.step(0.0f, kFrameDt);
    EXPECT_NEAR(v, 1.0f - 9.0f * BeatEnvelope::kFallPerSecond * kFrameDt, kTolerance);
}

TEST(BeatEnvelope, NeverMovesUpFasterThanTheRiseRate) {
    BeatEnvelope beat;
    float last = 0.0f;
    for (int i = 0; i < 120; ++i) {
        const float v = beat.step(i % 7 == 0 ? 1.5f : 0.0f, kFrameDt);
        EXPECT_LE(v - last, kRisePerFrame + kTolerance) << "frame " << i;
        EXPECT_LE(v, 1.5f);
        EXPECT_GE(v, 0.0f);
        last = v;
    }
}

TEST(BeatEnvelope, HoldsAPeakThatKeepsComing) {
    BeatEnvelope beat;
    float v = 0.0f;
    for (int i = 0; i < 30; ++i) v = beat.step(0.5f, kFrameDt);
    EXPECT_FLOAT_EQ(v, 0.5f);
}

TEST(BeatEnvelope, SilenceStaysAtZeroAndResetClears) {
    BeatEnvelope beat;
    EXPECT_EQ(beat.step(0.0f, kFrameDt), 0.0f);
    beat.step(1.0f, kFrameDt);
    beat.reset();
    EXPECT_EQ(beat.value(), 0.0f);
    EXPECT_EQ(beat.step(0.0f, kFrameDt), 0.0f);
}

TEST(BeatEnvelope, NegativeTimeStepIsNoTime) {
    BeatEnvelope beat;
    EXPECT_EQ(beat.step(1.0f, -kFrameDt), 0.0f);
    EXPECT_NEAR(beat.step(1.0f, kFrameDt), kRisePerFrame, kTolerance);
}

TEST(BeatEnvelope, AttackIsATenthOfASecond) {
    EXPECT_FLOAT_EQ(BeatEnvelope::kAttackSeconds, 0.1f);
    EXPECT_FLOAT_EQ(BeatEnvelope::kRisePerSecond, 10.0f);
    EXPECT_FLOAT_EQ(BeatEnvelope::kFallPerSecond, 3.0f);
}

}  // namespace
}  // namespace geode::viz
