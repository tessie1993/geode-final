#include "geode/viz/Lfo.hpp"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <vector>

using geode::viz::applyLfoTarget;
using geode::viz::chainOf;
using geode::viz::isLuminanceTarget;
using geode::viz::LfoConfig;
using geode::viz::LfoEngine;
using geode::viz::LfoTarget;
using geode::viz::LfoWave;
using geode::viz::ModChain;
using geode::viz::ModChainField;
using geode::viz::ModCurve;
using geode::viz::ModPolarity;
using geode::viz::ModSource;
using geode::viz::naturalPolarity;
using geode::viz::polarized;
using geode::viz::SceneParams;
using geode::viz::shape;

namespace {

constexpr float kTol = 1e-4f;
// Far past every clamp.
constexpr float kHuge = 100.0f;

LfoConfig oscillator(LfoWave wave, LfoTarget target, float rateSeconds, float depth) {
    LfoConfig c;
    c.enabled = true;
    c.source = ModSource::Lfo;
    c.wave = wave;
    c.target = target;
    c.rateSeconds = rateSeconds;
    c.depth = depth;
    return c;
}

LfoConfig follower(ModSource source) {
    LfoConfig c;
    c.enabled = true;
    c.source = source;
    c.target = LfoTarget::Zoom;
    c.depth = 1.0f;
    return c;
}

struct TargetCase {
    LfoTarget target;
    float SceneParams::*member;
    float atTenth;
    float high;
    float low;
};

}  // namespace

TEST(Lfo, ChainTargetsNameASlotAndAField) {
    struct Case {
        LfoTarget target;
        int slot;
        ModChainField field;
    };
    for (const Case& c : {Case{LfoTarget::Lfo1Rate, 0, ModChainField::Rate}, Case{LfoTarget::Lfo1Depth, 0, ModChainField::Depth},
                          Case{LfoTarget::Lfo2Rate, 1, ModChainField::Rate}, Case{LfoTarget::Lfo2Depth, 1, ModChainField::Depth},
                          Case{LfoTarget::Lfo3Rate, 2, ModChainField::Rate}, Case{LfoTarget::Lfo3Depth, 2, ModChainField::Depth}}) {
        ModChain chain{-1, ModChainField::Rate};
        ASSERT_TRUE(chainOf(c.target, chain));
        EXPECT_EQ(chain.slot, c.slot);
        EXPECT_EQ(chain.field, c.field);
    }
    ModChain chain{-1, ModChainField::Rate};
    EXPECT_FALSE(chainOf(LfoTarget::Zoom, chain));
    EXPECT_FALSE(chainOf(LfoTarget::None, chain));
    EXPECT_EQ(chain.slot, -1);
}

TEST(Lfo, LuminanceTargets) {
    for (const LfoTarget t : {LfoTarget::Brightness, LfoTarget::Intensity, LfoTarget::Saturation, LfoTarget::Bloom, LfoTarget::Glitch,
                              LfoTarget::Vignette, LfoTarget::ColorShift, LfoTarget::PaletteMix, LfoTarget::Temperature}) {
        EXPECT_TRUE(isLuminanceTarget(t)) << static_cast<int>(t);
    }
    EXPECT_FALSE(isLuminanceTarget(LfoTarget::Zoom));
    EXPECT_FALSE(isLuminanceTarget(LfoTarget::Speed));
    EXPECT_FALSE(isLuminanceTarget(LfoTarget::None));
}

TEST(Lfo, NaturalPolarity) {
    EXPECT_EQ(naturalPolarity(ModSource::Lfo), ModPolarity::Bipolar);
    EXPECT_EQ(naturalPolarity(ModSource::StereoPan), ModPolarity::Bipolar);
    EXPECT_EQ(naturalPolarity(ModSource::Bass), ModPolarity::Positive);
    EXPECT_EQ(naturalPolarity(ModSource::Transient), ModPolarity::Positive);
}

TEST(Lfo, PolarizedAndShaped) {
    EXPECT_NEAR(polarized(0.5f, ModPolarity::Bipolar), 0.5f, kTol);
    EXPECT_NEAR(polarized(0.5f, ModPolarity::Positive), 0.75f, kTol);
    EXPECT_NEAR(polarized(0.5f, ModPolarity::Negative), -0.75f, kTol);
    EXPECT_NEAR(polarized(-1.0f, ModPolarity::Positive), 0.0f, kTol);

    EXPECT_NEAR(shape(0.25f, ModCurve::Linear), 0.25f, kTol);
    EXPECT_NEAR(shape(0.25f, ModCurve::Exponential), 0.0625f, kTol);
    EXPECT_NEAR(shape(0.25f, ModCurve::Logarithmic), 0.5f, kTol);
    EXPECT_NEAR(shape(0.25f, ModCurve::Smooth), 0.15625f, kTol);
    EXPECT_NEAR(shape(-0.25f, ModCurve::Exponential), -0.0625f, kTol);
    EXPECT_NEAR(shape(-0.25f, ModCurve::Smooth), -0.15625f, kTol);
    EXPECT_NEAR(shape(2.0f, ModCurve::Linear), 1.0f, kTol);
    EXPECT_NEAR(shape(-3.0f, ModCurve::Logarithmic), -1.0f, kTol);
    EXPECT_EQ(shape(0.0f, ModCurve::Smooth), 0.0f);
}

TEST(Lfo, EveryParameterTargetMovesItsOwnFieldWithinItsRange) {
    const std::vector<TargetCase> cases = {
        {LfoTarget::Speed, &SceneParams::speed, 1.1f, 4.0f, 0.05f},
        {LfoTarget::Zoom, &SceneParams::zoom, 1.1f, 3.0f, 0.3f},
        {LfoTarget::Rotation, &SceneParams::rotation, 0.15f, 3.0f, -3.0f},
        {LfoTarget::Sway, &SceneParams::sway, 0.1f, 1.0f, 0.0f},
        {LfoTarget::Pulse, &SceneParams::pulse, 0.1f, 1.0f, 0.0f},
        {LfoTarget::DriftX, &SceneParams::driftX, 0.1f, 1.0f, -1.0f},
        {LfoTarget::DriftY, &SceneParams::driftY, 0.1f, 1.0f, -1.0f},
        {LfoTarget::Warp, &SceneParams::warp, 0.1f, 1.0f, 0.0f},
        {LfoTarget::Ripple, &SceneParams::ripple, 0.1f, 1.0f, 0.0f},
        {LfoTarget::Morph, &SceneParams::morph, 0.1f, 1.0f, 0.0f},
        {LfoTarget::Twist, &SceneParams::twist, 0.1f, 1.0f, -1.0f},
        {LfoTarget::Tile, &SceneParams::tile, 1.2f, 6.0f, 1.0f},
        {LfoTarget::Pixelate, &SceneParams::pixelate, 0.1f, 1.0f, 0.0f},
        {LfoTarget::Posterize, &SceneParams::posterize, 0.1f, 1.0f, 0.0f},
        {LfoTarget::ColorShift, &SceneParams::colorShift, 0.05f, 50.0f, -50.0f},
        {LfoTarget::PaletteMix, &SceneParams::paletteMix, 0.1f, 1.0f, 0.0f},
        {LfoTarget::Saturation, &SceneParams::saturation, 1.1f, 1.5f, 0.0f},
        {LfoTarget::Brightness, &SceneParams::brightness, 1.1f, 2.0f, 0.2f},
        {LfoTarget::Intensity, &SceneParams::intensity, 1.1f, 2.0f, 0.2f},
        {LfoTarget::Bloom, &SceneParams::bloom, 0.1f, 1.0f, 0.0f},
        {LfoTarget::Temperature, &SceneParams::temperature, 0.1f, 1.0f, -1.0f},
        {LfoTarget::Turbulence, &SceneParams::turbulence, 0.1f, 1.5f, 0.0f},
        {LfoTarget::ChromaAb, &SceneParams::chromaAb, 0.1f, 1.0f, 0.0f},
        {LfoTarget::Vignette, &SceneParams::vignette, 0.1f, 1.0f, 0.0f},
        {LfoTarget::Glitch, &SceneParams::glitch, 0.1f, 1.0f, 0.0f},
        {LfoTarget::Fisheye, &SceneParams::fisheye, 0.1f, 1.0f, -1.0f},
        {LfoTarget::ParticleSize, &SceneParams::particleSize, 1.1f, 2.5f, 0.3f},
        {LfoTarget::TrailLength, &SceneParams::trailLength, 0.6f, 0.98f, 0.05f},
        {LfoTarget::FluidCurl, &SceneParams::fluidCurl, 32.5f, 50.0f, 0.0f},
        {LfoTarget::FluidRadius, &SceneParams::fluidSplatRadius, 0.135f, 0.4f, 0.02f},
        {LfoTarget::FluidForce, &SceneParams::fluidSplatForce, 1.15f, 3.0f, 0.0f},
        {LfoTarget::FluidGlow, &SceneParams::fluidBloomIntensity, 0.9f, 2.0f, 0.1f},
        {LfoTarget::FluidFade, &SceneParams::fluidDensityDissipation, 1.15f, 4.0f, 0.0f},
        {LfoTarget::FluidCatchPull, &SceneParams::fluidCatchPull, 1.15f, 3.0f, 0.0f},
        {LfoTarget::FluidCatchRadius, &SceneParams::fluidCatchRadius, 0.132f, 0.3f, 0.03f},
        {LfoTarget::FlowStrength, &SceneParams::flowStrength, 0.45f, 1.0f, 0.0f},
        {LfoTarget::WaterRipple, &SceneParams::waterRippleStrength, 1.1f, 2.0f, 0.0f},
        {LfoTarget::RippleOverlay, &SceneParams::rippleOverlayStrength, 0.5f, 1.0f, 0.0f},
    };
    const SceneParams base;
    for (const TargetCase& c : cases) {
        const int id = static_cast<int>(c.target);
        EXPECT_NEAR(applyLfoTarget(base, c.target, 0.1f).*c.member, c.atTenth, kTol) << id;
        EXPECT_NEAR(applyLfoTarget(base, c.target, kHuge).*c.member, c.high, kTol) << id;
        EXPECT_NEAR(applyLfoTarget(base, c.target, -kHuge).*c.member, c.low, kTol) << id;
        // Nothing else moves.
        const SceneParams moved = applyLfoTarget(base, c.target, 0.1f);
        for (const auto& f : SceneParams::lerpedFloats()) {
            if (f.member == c.member) continue;
            EXPECT_EQ(moved.*f.member, base.*f.member) << id << " " << f.name;
        }
    }
}

TEST(Lfo, ChainAndNoneTargetsLeaveTheParametersAlone) {
    const SceneParams base;
    for (const LfoTarget t : {LfoTarget::None, LfoTarget::Lfo1Rate, LfoTarget::Lfo1Depth, LfoTarget::Lfo2Rate, LfoTarget::Lfo2Depth,
                              LfoTarget::Lfo3Rate, LfoTarget::Lfo3Depth}) {
        const SceneParams out = applyLfoTarget(base, t, 0.5f);
        for (const auto& f : SceneParams::lerpedFloats()) EXPECT_EQ(out.*f.member, base.*f.member) << f.name;
    }
}

TEST(LfoEngine, DisabledAndUntargetedSlotsAreSilent) {
    LfoEngine lfo;
    const GeodeFeatureFrame f{};
    lfo.configs[0] = oscillator(LfoWave::Square, LfoTarget::Zoom, 2.0f, 0.3f);
    lfo.configs[0].enabled = false;
    lfo.configs[1] = oscillator(LfoWave::Square, LfoTarget::None, 2.0f, 0.3f);
    const auto& out = lfo.tick(0.5f, f);
    EXPECT_EQ(out[0], 0.0f);
    EXPECT_EQ(out[1], 0.0f);
    EXPECT_EQ(out[2], 0.0f);
}

TEST(LfoEngine, SawWalksThroughItsCycle) {
    LfoEngine lfo;
    const GeodeFeatureFrame f{};
    // 2 s period: a quarter cycle per 0.5 s.
    lfo.configs[0] = oscillator(LfoWave::Saw, LfoTarget::Zoom, 2.0f, 0.3f);
    EXPECT_NEAR(lfo.tick(0.5f, f)[0], -0.15f, kTol);
    EXPECT_NEAR(lfo.tick(0.5f, f)[0], 0.0f, kTol);
    EXPECT_NEAR(lfo.tick(0.5f, f)[0], 0.15f, kTol);
    EXPECT_NEAR(lfo.tick(0.5f, f)[0], -0.3f, kTol);
}

TEST(LfoEngine, Waveforms) {
    const GeodeFeatureFrame f{};
    LfoEngine lfo;
    lfo.configs[0] = oscillator(LfoWave::Sine, LfoTarget::Zoom, 2.0f, 0.3f);
    lfo.configs[1] = oscillator(LfoWave::Triangle, LfoTarget::Zoom, 2.0f, 0.3f);
    lfo.configs[2] = oscillator(LfoWave::Square, LfoTarget::Zoom, 2.0f, 0.3f);
    // Phase 0.125.
    auto out = lfo.tick(0.25f, f);
    EXPECT_NEAR(out[0], 0.3f * std::sin(0.785398163f), kTol);
    EXPECT_NEAR(out[1], 0.15f, kTol);
    EXPECT_NEAR(out[2], 0.3f, kTol);
    // Phase 0.25.
    out = lfo.tick(0.25f, f);
    EXPECT_NEAR(out[0], 0.3f, kTol);
    EXPECT_NEAR(out[1], 0.0f, kTol);
    EXPECT_NEAR(out[2], 0.3f, kTol);
    // Phase 0.5.
    out = lfo.tick(0.5f, f);
    EXPECT_NEAR(out[0], 0.0f, kTol);
    EXPECT_NEAR(out[1], -0.3f, kTol);
    EXPECT_NEAR(out[2], -0.3f, kTol);
}

TEST(LfoEngine, RandomHoldsItsValueForACycle) {
    const GeodeFeatureFrame f{};
    LfoEngine lfo;
    lfo.configs[0] = oscillator(LfoWave::Random, LfoTarget::Zoom, 2.0f, 1.0f);
    const float first = lfo.tick(0.5f, f)[0];
    EXPECT_LE(std::fabs(first), 1.0f);
    EXPECT_EQ(lfo.tick(0.5f, f)[0], first);
    const float next = lfo.tick(2.0f, f)[0];
    EXPECT_LE(std::fabs(next), 1.0f);
    EXPECT_NE(next, first);

    // Seeded: a fresh engine plays the same sequence.
    LfoEngine again;
    again.configs[0] = lfo.configs[0];
    EXPECT_EQ(again.tick(0.5f, f)[0], first);
}

TEST(LfoEngine, RateIsClamped) {
    const GeodeFeatureFrame f{};
    {
        // 0.01 s clamps to 0.05 s: 20 Hz, a fifth of a cycle in 10 ms.
        LfoEngine lfo;
        lfo.configs[0] = oscillator(LfoWave::Saw, LfoTarget::Zoom, 0.01f, 1.0f);
        EXPECT_NEAR(lfo.tick(0.01f, f)[0], -0.6f, kTol);
    }
    {
        // 1000 s clamps to 60 s.
        LfoEngine lfo;
        lfo.configs[0] = oscillator(LfoWave::Saw, LfoTarget::Zoom, 1000.0f, 1.0f);
        EXPECT_NEAR(lfo.tick(15.0f, f)[0], -0.5f, 1e-3f);
    }
    {
        // A negative external rate floors at 0.01 Hz.
        LfoEngine lfo;
        lfo.configs[0] = oscillator(LfoWave::Saw, LfoTarget::Zoom, 2.0f, 1.0f);
        const float rate[LfoEngine::kSlots] = {-kHuge, 0.0f, 0.0f};
        EXPECT_NEAR(lfo.tick(25.0f, f, rate)[0], -0.5f, 1e-3f);
    }
    {
        // A large external rate caps at 30 Hz.
        LfoEngine lfo;
        lfo.configs[0] = oscillator(LfoWave::Saw, LfoTarget::Zoom, 2.0f, 1.0f);
        const float rate[LfoEngine::kSlots] = {kHuge, 0.0f, 0.0f};
        EXPECT_NEAR(lfo.tick(0.01f, f, rate)[0], -0.4f, kTol);
    }
    {
        // A luminance target is held to the flash rate: 20 Hz becomes 3 Hz.
        LfoEngine lfo;
        lfo.configs[0] = oscillator(LfoWave::Saw, LfoTarget::Brightness, 0.05f, 1.0f);
        EXPECT_NEAR(lfo.tick(0.1f, f)[0], -0.4f, kTol);
    }
}

TEST(LfoEngine, DepthIsOffsetAndClamped) {
    const GeodeFeatureFrame f{};
    {
        LfoEngine lfo;
        lfo.configs[0] = oscillator(LfoWave::Sine, LfoTarget::Zoom, 2.0f, 0.3f);
        const float depth[LfoEngine::kSlots] = {0.2f, 0.0f, 0.0f};
        EXPECT_NEAR(lfo.tick(0.5f, f, nullptr, depth)[0], 0.5f, kTol);
    }
    {
        LfoEngine lfo;
        lfo.configs[0] = oscillator(LfoWave::Sine, LfoTarget::Zoom, 2.0f, 5.0f);
        EXPECT_NEAR(lfo.tick(0.5f, f)[0], 2.0f, kTol);
    }
    {
        LfoEngine lfo;
        lfo.configs[0] = oscillator(LfoWave::Sine, LfoTarget::Zoom, 2.0f, -1.0f);
        EXPECT_NEAR(lfo.tick(0.5f, f)[0], 0.0f, kTol);
    }
}

TEST(LfoEngine, PolarityAndCurveShapeTheOutput) {
    const GeodeFeatureFrame f{};
    LfoEngine lfo;
    lfo.configs[0] = oscillator(LfoWave::Saw, LfoTarget::Zoom, 2.0f, 1.0f);
    lfo.configs[0].polarity = ModPolarity::Positive;
    lfo.configs[0].curve = ModCurve::Exponential;
    // Saw -0.5, made positive 0.25, squared.
    EXPECT_NEAR(lfo.tick(0.5f, f)[0], 0.0625f, kTol);
}

TEST(LfoEngine, BandFollowersRiseFastAndFallSlowly) {
    LfoEngine lfo;
    lfo.configs[0] = follower(ModSource::Bass);
    GeodeFeatureFrame f{};
    f.bass = 1.0f;
    // Rise time 20 ms: half way in 10 ms.
    EXPECT_NEAR(lfo.tick(0.01f, f)[0], 0.5f, kTol);
    EXPECT_NEAR(lfo.tick(0.02f, f)[0], 1.0f, kTol);
    f.bass = 0.0f;
    // Fall time 160 ms: half way from 1 to -1 in 80 ms.
    EXPECT_NEAR(lfo.tick(0.08f, f)[0], 0.0f, kTol);
    f.bass = 2.0f;
    EXPECT_NEAR(lfo.tick(1.0f, f)[0], 1.0f, kTol);
}

TEST(LfoEngine, EachSourceReadsItsOwnFeature) {
    struct Case {
        ModSource source;
        float GeodeFeatureFrame::*field;
    };
    for (const Case& c : {Case{ModSource::Bass, &GeodeFeatureFrame::bass}, Case{ModSource::Mid, &GeodeFeatureFrame::mid},
                          Case{ModSource::Treble, &GeodeFeatureFrame::treble}, Case{ModSource::Level, &GeodeFeatureFrame::rms},
                          Case{ModSource::Brightness, &GeodeFeatureFrame::centroid},
                          Case{ModSource::StereoWidth, &GeodeFeatureFrame::stereoWidth}}) {
        LfoEngine lfo;
        lfo.configs[0] = follower(c.source);
        GeodeFeatureFrame f{};
        f.*c.field = 0.75f;
        EXPECT_NEAR(lfo.tick(1.0f, f)[0], 0.5f, kTol) << static_cast<int>(c.source);
        GeodeFeatureFrame silent{};
        EXPECT_NEAR(lfo.tick(1.0f, silent)[0], -1.0f, kTol) << static_cast<int>(c.source);
    }
}

TEST(LfoEngine, StereoPanFollowsBothWays) {
    LfoEngine lfo;
    lfo.configs[0] = follower(ModSource::StereoPan);
    GeodeFeatureFrame f{};
    f.stereoPan = -0.5f;
    EXPECT_NEAR(lfo.tick(0.08f, f)[0], -0.25f, kTol);
    EXPECT_NEAR(lfo.tick(1.0f, f)[0], -0.5f, kTol);
    f.stereoPan = 0.5f;
    EXPECT_NEAR(lfo.tick(0.01f, f)[0], 0.0f, kTol);
    f.stereoPan = -3.0f;
    EXPECT_NEAR(lfo.tick(1.0f, f)[0], -1.0f, kTol);
}

TEST(LfoEngine, TransientsJumpThenFall) {
    LfoEngine lfo;
    lfo.configs[0] = follower(ModSource::Transient);
    GeodeFeatureFrame f{};
    f.transient = 0.8f;
    // Whatever the frame time, the follower jumps to the hit.
    EXPECT_NEAR(lfo.tick(0.001f, f)[0], 0.6f, kTol);
    f.transient = 0.0f;
    EXPECT_NEAR(lfo.tick(0.08f, f)[0], -0.2f, kTol);
    // Under the hit floor a transient is no hit.
    f.transient = 0.05f;
    EXPECT_NEAR(lfo.tick(1.0f, f)[0], -1.0f, kTol);
    // A weaker hit than where the follower stands is followed, not jumped to.
    LfoEngine held;
    held.configs[0] = follower(ModSource::Transient);
    f.transient = 1.0f;
    EXPECT_NEAR(held.tick(0.01f, f)[0], 1.0f, kTol);
    f.transient = 0.5f;
    EXPECT_NEAR(held.tick(0.08f, f)[0], 0.5f, kTol);
}

TEST(LfoEngine, ASlotSteersALaterSlotsDepthAndRate) {
    const GeodeFeatureFrame f{};
    {
        LfoEngine lfo;
        lfo.configs[0] = oscillator(LfoWave::Square, LfoTarget::Lfo2Depth, 2.0f, 0.3f);
        lfo.configs[1] = oscillator(LfoWave::Sine, LfoTarget::Zoom, 2.0f, 0.3f);
        const auto& out = lfo.tick(0.5f, f);
        EXPECT_NEAR(out[0], 0.3f, kTol);
        EXPECT_NEAR(out[1], 0.6f, kTol);
    }
    {
        LfoEngine lfo;
        lfo.configs[0] = oscillator(LfoWave::Square, LfoTarget::Lfo3Rate, 2.0f, 0.3f);
        lfo.configs[2] = oscillator(LfoWave::Saw, LfoTarget::Zoom, 2.0f, 0.3f);
        // 0.5 Hz + 0.3 * 4 Hz = 1.7 Hz: phase 0.85 after 0.5 s.
        EXPECT_NEAR(lfo.tick(0.5f, f)[2], 0.21f, kTol);
    }
}

TEST(LfoEngine, AnEarlierSlotCannotBeSteered) {
    const GeodeFeatureFrame f{};
    LfoEngine lfo;
    lfo.configs[0] = oscillator(LfoWave::Sine, LfoTarget::Zoom, 2.0f, 0.3f);
    lfo.configs[1] = oscillator(LfoWave::Square, LfoTarget::Lfo1Depth, 2.0f, 1.0f);
    lfo.configs[2] = oscillator(LfoWave::Square, LfoTarget::Lfo3Depth, 2.0f, 0.3f);
    for (int i = 0; i < 2; ++i) {
        const auto& out = lfo.tick(0.25f, f);
        EXPECT_NEAR(out[2], 0.3f, kTol);
    }
    EXPECT_NEAR(lfo.tick(0.0f, f)[0], 0.3f, kTol);
}

TEST(LfoEngine, ApplyRunsEveryEnabledSlot) {
    LfoEngine lfo;
    lfo.configs[0] = oscillator(LfoWave::Sine, LfoTarget::Zoom, 2.0f, 0.3f);
    lfo.configs[1] = oscillator(LfoWave::Sine, LfoTarget::Speed, 2.0f, 0.3f);
    lfo.configs[1].enabled = false;
    lfo.configs[2] = oscillator(LfoWave::Sine, LfoTarget::Warp, 2.0f, 0.3f);
    const SceneParams out = lfo.apply(SceneParams{}, {0.1f, 0.5f, 0.2f});
    EXPECT_NEAR(out.zoom, 1.1f, kTol);
    EXPECT_NEAR(out.speed, 1.0f, kTol);
    EXPECT_NEAR(out.warp, 0.2f, kTol);
}
