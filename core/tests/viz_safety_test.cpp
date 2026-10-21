#include "geode/viz/VisualSafety.hpp"

#include <gtest/gtest.h>

#include <string>

using geode::viz::BlendMode;
using geode::viz::blendModeFromOrdinal;
using geode::viz::LfoTarget;
using geode::viz::SceneParams;
using geode::viz::TransitionStyle;
using geode::viz::transitionStyleId;
namespace safety = geode::viz::safety;

namespace {

constexpr float kTol = 1e-5f;

}  // namespace

TEST(VisualSafety, TransitionStyleIds) {
    EXPECT_EQ(std::string(transitionStyleId(TransitionStyle::Cut)), "cut");
    EXPECT_EQ(std::string(transitionStyleId(TransitionStyle::Fade)), "fade");
    EXPECT_EQ(std::string(transitionStyleId(TransitionStyle::Melt)), "melt");
    EXPECT_EQ(std::string(transitionStyleId(TransitionStyle::Slide)), "slide");
    EXPECT_EQ(std::string(transitionStyleId(TransitionStyle::Zoom)), "zoom");
}

TEST(VisualSafety, BlendModeFromOrdinalFallsBackToScreen) {
    EXPECT_EQ(blendModeFromOrdinal(0), BlendMode::Normal);
    EXPECT_EQ(blendModeFromOrdinal(3), BlendMode::Multiply);
    EXPECT_EQ(blendModeFromOrdinal(7), BlendMode::Darken);
    EXPECT_EQ(blendModeFromOrdinal(8), BlendMode::Screen);
    EXPECT_EQ(blendModeFromOrdinal(-1), BlendMode::Screen);
}

TEST(VisualSafety, ApplyClampsLuminance) {
    SceneParams p;
    p.glitch = 0.9f;
    p.bloom = 0.9f;
    p.brightness = 3.0f;
    p.intensity = 3.0f;
    p.contrast = 3.0f;
    const SceneParams high = safety::apply(p);
    EXPECT_NEAR(high.glitch, 0.25f, kTol);
    EXPECT_NEAR(high.bloom, 0.25f, kTol);
    EXPECT_NEAR(high.brightness, 1.25f, kTol);
    EXPECT_NEAR(high.intensity, 1.25f, kTol);
    EXPECT_NEAR(high.contrast, 1.25f, kTol);

    p.brightness = -1.0f;
    p.intensity = -1.0f;
    p.contrast = -1.0f;
    const SceneParams low = safety::apply(p);
    EXPECT_EQ(low.brightness, 0.0f);
    EXPECT_EQ(low.intensity, 0.0f);
    EXPECT_EQ(low.contrast, 0.0f);

    SceneParams safe;
    safe.glitch = 0.1f;
    safe.bloom = 0.2f;
    safe.brightness = 1.1f;
    const SceneParams same = safety::apply(safe);
    EXPECT_FLOAT_EQ(same.glitch, 0.1f);
    EXPECT_FLOAT_EQ(same.bloom, 0.2f);
    EXPECT_FLOAT_EQ(same.brightness, 1.1f);
    EXPECT_FLOAT_EQ(same.speed, 1.0f);
}

TEST(VisualSafety, ReducedMotionScalesEveryMotionRate) {
    SceneParams p;
    p.speed = 1.0f;
    p.shake = 1.0f;
    p.sway = 1.0f;
    p.driftX = 1.0f;
    p.driftY = -1.0f;
    p.rotation = 2.0f;
    p.turbulence = 1.0f;
    p.pulse = 1.0f;
    p.cycleSpeed = 1.0f;
    p.endlessZoomSpeed = 1.0f;
    p.zoom = 2.0f;
    const SceneParams r = safety::apply(p, true);
    EXPECT_NEAR(r.speed, 0.4f, kTol);
    EXPECT_NEAR(r.shake, 0.4f, kTol);
    EXPECT_NEAR(r.sway, 0.4f, kTol);
    EXPECT_NEAR(r.driftX, 0.4f, kTol);
    EXPECT_NEAR(r.driftY, -0.4f, kTol);
    EXPECT_NEAR(r.rotation, 0.8f, kTol);
    EXPECT_NEAR(r.turbulence, 0.4f, kTol);
    EXPECT_NEAR(r.pulse, 0.4f, kTol);
    EXPECT_NEAR(r.cycleSpeed, 0.4f, kTol);
    EXPECT_NEAR(r.endlessZoomSpeed, 0.4f, kTol);
    EXPECT_FLOAT_EQ(r.zoom, 2.0f);

    const SceneParams full = safety::apply(p, false);
    EXPECT_FLOAT_EQ(full.speed, 1.0f);
    EXPECT_FLOAT_EQ(full.rotation, 2.0f);
}

TEST(VisualSafety, LuminanceLfosAreHeldUnderTheFlashRate) {
    EXPECT_NEAR(safety::limitLfoRate(10.0f, LfoTarget::Brightness), 3.0f, kTol);
    EXPECT_NEAR(safety::limitLfoRate(2.0f, LfoTarget::Brightness), 2.0f, kTol);
    EXPECT_NEAR(safety::limitLfoRate(10.0f, LfoTarget::Zoom), 10.0f, kTol);
}

TEST(VisualSafety, BeatIntervalAndTransitions) {
    EXPECT_NEAR(safety::beatMinIntervalMs(100.0f), 1000.0f / 3.0f, 1e-3f);
    EXPECT_NEAR(safety::beatMinIntervalMs(500.0f), 500.0f, kTol);
    EXPECT_EQ(safety::transitionStyle(TransitionStyle::Cut), TransitionStyle::Fade);
    EXPECT_EQ(safety::transitionStyle(TransitionStyle::Melt), TransitionStyle::Melt);
    EXPECT_EQ(safety::transitionId("cut"), "fade");
    EXPECT_EQ(safety::transitionId("melt"), "melt");
}

TEST(VisualSafety, LayerMixCapsTheFlashingBlendModes) {
    EXPECT_NEAR(safety::layerMix(0.9f, BlendMode::Difference), 0.25f, kTol);
    EXPECT_NEAR(safety::layerMix(0.9f, BlendMode::Add), 0.25f, kTol);
    EXPECT_NEAR(safety::layerMix(0.1f, BlendMode::Add), 0.1f, kTol);
    EXPECT_NEAR(safety::layerMix(0.9f, BlendMode::Screen), 0.9f, kTol);
    EXPECT_NEAR(safety::layerMix(1.5f, BlendMode::Normal), 1.0f, kTol);
    EXPECT_NEAR(safety::layerMix(-1.0f, BlendMode::Normal), 0.0f, kTol);
}
