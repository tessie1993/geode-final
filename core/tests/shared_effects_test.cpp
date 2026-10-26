#include "geode/viz/SharedEffects.hpp"

#include <gtest/gtest.h>

#include <array>
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
    plan.shared[static_cast<size_t>(mod::Shared::Trails)] = mod::toUnit(mod::Shared::Trails, 0.9f);
    plan.shared[static_cast<size_t>(mod::Shared::TrailZoom)] = mod::toUnit(mod::Shared::TrailZoom, -0.25f);
    plan.shared[static_cast<size_t>(mod::Shared::SoftFocus)] = mod::toUnit(mod::Shared::SoftFocus, 0.4f);
    plan.shared[static_cast<size_t>(mod::Shared::MotionBlur)] = mod::toUnit(mod::Shared::MotionBlur, 0.6f);
    plan.shared[static_cast<size_t>(mod::Shared::Particles)] = mod::toUnit(mod::Shared::Particles, 0.7f);
    plan.shared[static_cast<size_t>(mod::Shared::ParticleKind)] = mod::toUnit(mod::Shared::ParticleKind, 3.0f);
    plan.shared[static_cast<size_t>(mod::Shared::ParticleSize)] = mod::toUnit(mod::Shared::ParticleSize, 2.0f);
    mod::ReactionGraph graph;
    const SharedEffects e = SharedEffects::from(graph.evaluate(plan, mod::SignalFrame{}, kFrameDt));
    EXPECT_NEAR(e.trails, 0.9f, kTolerance);
    EXPECT_NEAR(e.trailZoom, -0.25f, kTolerance);
    EXPECT_NEAR(e.softFocus, 0.4f, kTolerance);
    EXPECT_NEAR(e.motionBlur, 0.6f, kTolerance);
    EXPECT_NEAR(e.particles, 0.7f, kTolerance);
    EXPECT_EQ(e.particleKind, 3);
    EXPECT_NEAR(e.particleSize, 2.0f, kTolerance);
    EXPECT_EQ(e.anaglyph, 0.0f);
}

TEST(SharedEffects, TheEchoCopyIsReadFromTheSharedCoreAndRunsTheSharedPass) {
    mod::CustomizationPlan plan = mod::CustomizationPlan::defaults();
    mod::ReactionGraph graph;
    const SharedEffects off = SharedEffects::from(graph.evaluate(plan, mod::SignalFrame{}, kFrameDt));
    EXPECT_EQ(off.echoCopy, 0.0f);
    EXPECT_NEAR(off.echoCopyZoom, 1.0f, kTolerance);
    // It starts as a mirror, so turning the copy up shows it at once.
    EXPECT_EQ(off.echoCopyFlip, 1);
    plan.shared[static_cast<size_t>(mod::Shared::EchoCopy)] = mod::toUnit(mod::Shared::EchoCopy, 0.8f);
    plan.shared[static_cast<size_t>(mod::Shared::EchoCopyZoom)] = mod::toUnit(mod::Shared::EchoCopyZoom, 1.5f);
    plan.shared[static_cast<size_t>(mod::Shared::EchoCopyFlip)] = mod::toUnit(mod::Shared::EchoCopyFlip, 3.0f);
    mod::ReactionGraph fresh;
    const SharedEffects on = SharedEffects::from(fresh.evaluate(plan, mod::SignalFrame{}, kFrameDt));
    EXPECT_NEAR(on.echoCopy, 0.8f, kTolerance);
    EXPECT_NEAR(on.echoCopyZoom, 1.5f, kTolerance);
    EXPECT_EQ(on.echoCopyFlip, 3);
    EXPECT_TRUE(on.active());
    SharedEffects copyOnly;
    copyOnly.echoCopy = 0.1f;
    EXPECT_TRUE(copyOnly.active());
}

TEST(SharedEffects, TheEchoCopyFadesOutTurnsAndFadesBackInRatherThanCuttingOver) {
    using sharedfx::EchoCopyFade;
    // From nothing it takes the flip asked for at once, and fades in over kEchoCopyFadeSeconds.
    EchoCopyFade fade = sharedfx::stepEchoCopy(EchoCopyFade{}, 2, kFrameDt);
    EXPECT_EQ(fade.flip, 2);
    EXPECT_EQ(fade.presence, 0.0f);
    fade = sharedfx::stepEchoCopy(fade, 2, 0.125f);
    EXPECT_NEAR(fade.presence, 0.5f, kTolerance);
    fade = sharedfx::stepEchoCopy(fade, 2, 1.0f);
    EXPECT_EQ(fade, (EchoCopyFade{2, 1.0f}));
    // A new flip: the copy fades out showing the old one, turns once it is gone, then fades back in.
    fade = sharedfx::stepEchoCopy(fade, 1, 0.125f);
    EXPECT_EQ(fade.flip, 2);
    EXPECT_NEAR(fade.presence, 0.5f, kTolerance);
    fade = sharedfx::stepEchoCopy(fade, 1, 0.125f);
    EXPECT_EQ(fade, (EchoCopyFade{1, 0.0f}));
    fade = sharedfx::stepEchoCopy(fade, 1, 0.0625f);
    EXPECT_EQ(fade.flip, 1);
    EXPECT_NEAR(fade.presence, 0.25f, kTolerance);
    // The same at any frame rate: a quarter of a second of 120 Hz frames fades it fully in.
    EchoCopyFade fast{3, 0.0f};
    for (int i = 0; i < 30; ++i) fast = sharedfx::stepEchoCopy(fast, 3, 1.0f / 120.0f);
    EXPECT_NEAR(fast.presence, 1.0f, kTolerance);
    // A flip out of range is held to the four there are; a stalled or broken frame holds the fade.
    EXPECT_EQ(sharedfx::stepEchoCopy(EchoCopyFade{}, 9, kFrameDt).flip, 3);
    EXPECT_EQ(sharedfx::stepEchoCopy(EchoCopyFade{}, -2, kFrameDt).flip, 0);
    EXPECT_EQ(sharedfx::stepEchoCopy(EchoCopyFade{2, 0.4f}, 1, 0.0f), (EchoCopyFade{2, 0.4f}));
    EXPECT_EQ(sharedfx::stepEchoCopy(EchoCopyFade{2, 0.4f}, 1, std::numeric_limits<float>::quiet_NaN()), (EchoCopyFade{2, 0.4f}));
    EXPECT_EQ(sharedfx::stepEchoCopy(EchoCopyFade{2, 0.4f}, 1, std::numeric_limits<float>::infinity()), (EchoCopyFade{2, 0.4f}));
    EXPECT_EQ(sharedfx::stepEchoCopy(EchoCopyFade{7, 3.0f}, 3, 0.0f), (EchoCopyFade{3, 1.0f}));
}

TEST(SharedEffects, TheEchoCopyIsAtMostAnEvenBlend) {
    EXPECT_NEAR(sharedfx::echoCopyMix(1.0f, 1.0f), sharedfx::kEchoCopyMaxMix, kTolerance);
    EXPECT_NEAR(sharedfx::echoCopyMix(0.5f, 1.0f), 0.25f, kTolerance);
    EXPECT_NEAR(sharedfx::echoCopyMix(1.0f, 0.5f), 0.25f, kTolerance);
    EXPECT_EQ(sharedfx::echoCopyMix(0.0f, 1.0f), 0.0f);
    EXPECT_EQ(sharedfx::echoCopyMix(1.0f, 0.0f), 0.0f);
    EXPECT_NEAR(sharedfx::echoCopyMix(3.0f, 2.0f), sharedfx::kEchoCopyMaxMix, kTolerance);
    EXPECT_EQ(sharedfx::echoCopyMix(std::numeric_limits<float>::quiet_NaN(), 1.0f), 0.0f);
    EXPECT_EQ(sharedfx::kEchoCopyMaxMix, 0.5f);
}

TEST(SharedEffects, TheEchoCopyZoomsWithinItsRangeAndFlipsEachAxis) {
    EXPECT_EQ(sharedfx::echoCopyZoom(1.25f), 1.25f);
    EXPECT_EQ(sharedfx::echoCopyZoom(0.1f), sharedfx::kMinEchoCopyZoom);
    EXPECT_EQ(sharedfx::echoCopyZoom(9.0f), sharedfx::kMaxEchoCopyZoom);
    EXPECT_EQ(sharedfx::echoCopyZoom(std::numeric_limits<float>::quiet_NaN()), 1.0f);
    EXPECT_EQ(sharedfx::echoCopyFlipScale(0), (std::array<float, 2>{1.0f, 1.0f}));
    EXPECT_EQ(sharedfx::echoCopyFlipScale(1), (std::array<float, 2>{-1.0f, 1.0f}));
    EXPECT_EQ(sharedfx::echoCopyFlipScale(2), (std::array<float, 2>{1.0f, -1.0f}));
    EXPECT_EQ(sharedfx::echoCopyFlipScale(3), (std::array<float, 2>{-1.0f, -1.0f}));
    EXPECT_EQ(sharedfx::echoCopyFlipScale(-1), (std::array<float, 2>{1.0f, 1.0f}));
    EXPECT_EQ(sharedfx::echoCopyFlipScale(6), (std::array<float, 2>{-1.0f, -1.0f}));
}

TEST(SharedEffects, TheAnaglyphIsASwitchTheCompositeDrawsSoTheSharedPassStaysOff) {
    mod::CustomizationPlan plan = mod::CustomizationPlan::defaults();
    plan.shared[static_cast<size_t>(mod::Shared::Anaglyph)] = 1.0f;
    mod::ReactionGraph graph;
    const SharedEffects on = SharedEffects::from(graph.evaluate(plan, mod::SignalFrame{}, kFrameDt));
    EXPECT_EQ(on.anaglyph, 1.0f);
    EXPECT_FALSE(on.active());
    EXPECT_TRUE(mod::spec(mod::Shared::Anaglyph).luminous);
    EXPECT_EQ(mod::spec(mod::Shared::Anaglyph).kind, mod::ValueKind::Switch);
}

TEST(SharedEffects, TheAnaglyphFadesInAndOutRatherThanCuttingOver) {
    // Half a second from off to on, the same at any frame rate, and never past either end.
    EXPECT_NEAR(sharedfx::anaglyphMix(0.0f, 1.0f, kFrameDt), kFrameDt / sharedfx::kAnaglyphFadeSeconds, kTolerance);
    EXPECT_NEAR(sharedfx::anaglyphMix(0.0f, 1.0f, 0.25f), 0.5f, kTolerance);
    EXPECT_NEAR(sharedfx::anaglyphMix(0.5f, 0.0f, 0.125f), 0.25f, kTolerance);
    EXPECT_EQ(sharedfx::anaglyphMix(0.9f, 1.0f, 1.0f), 1.0f);
    EXPECT_EQ(sharedfx::anaglyphMix(0.1f, 0.0f, 1.0f), 0.0f);
    EXPECT_EQ(sharedfx::anaglyphMix(1.0f, 1.0f, kFrameDt), 1.0f);
    // A stalled or broken frame holds the mix where it is.
    EXPECT_EQ(sharedfx::anaglyphMix(0.4f, 1.0f, 0.0f), 0.4f);
    EXPECT_EQ(sharedfx::anaglyphMix(0.4f, 0.0f, std::numeric_limits<float>::quiet_NaN()), 0.4f);
    EXPECT_EQ(sharedfx::anaglyphMix(0.4f, 1.0f, std::numeric_limits<float>::infinity()), 0.4f);
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
