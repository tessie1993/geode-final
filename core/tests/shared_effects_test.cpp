#include "geode/viz/SharedEffects.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <limits>

#include "geode/viz/ReactionGraph.hpp"

namespace geode::viz {
namespace {

constexpr float kTolerance = 1e-5f;
constexpr float kFrameDt = 1.0f / 60.0f;

TEST(SharedEffects, EveryEffectStartsOffSoNothingChangesUntilSomeoneTurnsItUp) {
    EXPECT_FALSE(SharedEffects{}.active());
    SharedEffects e;
    e.particles = 0.2f;
    EXPECT_TRUE(e.active());
}

TEST(SharedEffects, TheyAreReadFromTheEvaluatedSharedCore) {
    mod::CustomizationPlan plan = mod::CustomizationPlan::defaults();
    plan.shared[static_cast<size_t>(mod::Shared::Trails)] = 0.9f;
    plan.shared[static_cast<size_t>(mod::Shared::TrailZoom)] = -0.25f;
    plan.shared[static_cast<size_t>(mod::Shared::SoftFocus)] = 0.4f;
    plan.shared[static_cast<size_t>(mod::Shared::MotionBlur)] = 0.6f;
    plan.shared[static_cast<size_t>(mod::Shared::Particles)] = 0.7f;
    plan.shared[static_cast<size_t>(mod::Shared::ParticleKind)] = 3.0f;
    plan.shared[static_cast<size_t>(mod::Shared::ParticleSize)] = 2.0f;
    mod::ReactionGraph graph;
    const SharedEffects e = SharedEffects::from(graph.evaluate(plan, mod::SignalFrame{}, kFrameDt));
    EXPECT_NEAR(e.trails, 0.9f, kTolerance);
    EXPECT_NEAR(e.trailZoom, -0.25f, kTolerance);
    EXPECT_NEAR(e.softFocus, 0.4f, kTolerance);
    EXPECT_NEAR(e.motionBlur, 0.6f, kTolerance);
    EXPECT_NEAR(e.particles, 0.7f, kTolerance);
    EXPECT_EQ(e.particleKind, 3);
    EXPECT_NEAR(e.particleSize, 2.0f, kTolerance);
}

TEST(SharedEffects, TrailsKeepTheSameShareOfThePictureEverySecondAtAnyFrameRate) {
    // At 60 fps a frame keeps the trail amount itself; at 30 fps a frame keeps what two would.
    EXPECT_NEAR(sharedfx::echoKeep(0.9f, kFrameDt), 0.9f, kTolerance);
    EXPECT_NEAR(sharedfx::echoKeep(0.9f, 2.0f * kFrameDt), 0.81f, kTolerance);
    EXPECT_EQ(sharedfx::echoKeep(0.0f, kFrameDt), 0.0f);
    // Never more than the top of the Trails range, so a trail always fades out.
    EXPECT_LE(sharedfx::echoKeep(5.0f, kFrameDt), 0.98f);
    EXPECT_EQ(sharedfx::echoKeep(std::numeric_limits<float>::quiet_NaN(), kFrameDt), 0.0f);
}

TEST(SharedEffects, MotionBlurIsFrameRateIndependentAndAlwaysLetsTheNewFrameIn) {
    const float at60 = sharedfx::motionKeep(1.0f, kFrameDt);
    EXPECT_NEAR(at60, sharedfx::kMaxMotionKeep, kTolerance);
    EXPECT_NEAR(sharedfx::motionKeep(1.0f, 2.0f * kFrameDt), at60 * at60, kTolerance);
    EXPECT_NEAR(sharedfx::motionKeep(0.5f, kFrameDt), 0.5f * sharedfx::kMaxMotionKeep, kTolerance);
    EXPECT_EQ(sharedfx::motionKeep(0.0f, kFrameDt), 0.0f);
    EXPECT_LT(sharedfx::motionKeep(9.0f, kFrameDt), 1.0f);
}

TEST(SharedEffects, AStalledFrameDoesNotHoldThePictureForever) {
    // A zero or broken frame time keeps nothing of the last frame rather than freezing it.
    EXPECT_EQ(sharedfx::echoKeep(0.9f, 0.0f), 0.0f);
    EXPECT_EQ(sharedfx::motionKeep(0.9f, -1.0f), 0.0f);
}

}  // namespace
}  // namespace geode::viz
