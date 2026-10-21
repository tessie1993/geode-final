#include "geode/viz/fluid/FluidMath.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <utility>

namespace math = geode::viz::fluid::math;
namespace water = geode::viz::fluid::water;
namespace curl = geode::viz::fluid::curl;
using geode::viz::fluid::scaledFeatures;

namespace {

constexpr float kTol = 1e-4f;
constexpr float kFrame = 1.0f / 60.0f;

}  // namespace

TEST(FluidMath, DrivenScalesByTheClampedAudioDriveUnderTheCeiling) {
    EXPECT_NEAR(math::driven(0.5f, 1.0f), 0.5f, kTol);
    EXPECT_NEAR(math::driven(0.5f, 2.0f), 1.0f, kTol);
    // The drive is clamped to 0.2..2.5.
    EXPECT_NEAR(math::driven(0.5f, 10.0f), 1.25f, kTol);
    EXPECT_NEAR(math::driven(0.5f, 0.0f), 0.1f, kTol);
    // The ceiling is 1.5, or the value itself when that is higher.
    EXPECT_NEAR(math::driven(1.0f, 2.5f), 1.5f, kTol);
    EXPECT_NEAR(math::driven(2.0f, 2.0f), 2.0f, kTol);
    EXPECT_NEAR(math::driven(-1.0f, 1.0f), 0.0f, kTol);
}

TEST(FluidMath, CurlVelocityIsDivergenceFree) {
    constexpr float kStep = 0.02f;
    constexpr float kTime = 0.37f;
    constexpr float kFreq = 3.0f;
    for (const float detail : {0.0f, 1.0f}) {
        for (const auto& [x, y] : {std::pair{0.13f, 0.71f}, std::pair{0.5f, 0.5f}, std::pair{1.7f, -0.4f}}) {
            const auto [uR, vR] = math::curlVelocity(x + kStep, y, kTime, kFreq, detail);
            const auto [uL, vL] = math::curlVelocity(x - kStep, y, kTime, kFreq, detail);
            const auto [uT, vT] = math::curlVelocity(x, y + kStep, kTime, kFreq, detail);
            const auto [uB, vB] = math::curlVelocity(x, y - kStep, kTime, kFreq, detail);
            const float divergence = (uR - uL) + (vT - vB);
            const float scale = std::fabs(uR) + std::fabs(uL) + std::fabs(vT) + std::fabs(vB) + 1.0f;
            EXPECT_NEAR(divergence / scale, 0.0f, 1e-3f) << x << "," << y << " detail " << detail;
        }
    }
}

TEST(FluidMath, CurlVelocityMovesWithTimeAndDetail) {
    const auto base = math::curlVelocity(0.3f, 0.6f, 0.5f, 2.0f, 1.0f);
    const auto again = math::curlVelocity(0.3f, 0.6f, 0.5f, 2.0f, 1.0f);
    EXPECT_EQ(base, again);
    const auto later = math::curlVelocity(0.3f, 0.6f, 1.5f, 2.0f, 1.0f);
    EXPECT_NE(base, later);
    const auto coarse = math::curlVelocity(0.3f, 0.6f, 0.5f, 2.0f, 0.0f);
    EXPECT_NE(base, coarse);
    EXPECT_TRUE(std::isfinite(base.first) && std::isfinite(base.second));
}

TEST(FluidMath, ConfinementDeltaV) {
    // omega = (0.5 / dx) * velDiff = 0.8; strength * dx * omega * dt.
    EXPECT_NEAR(math::confinementDeltaV(3.0f, 0.25f, 0.4f, 0.1f), 0.06f, kTol);
    EXPECT_NEAR(math::confinementDeltaV(0.0f, 0.25f, 0.4f, 0.1f), 0.0f, kTol);
}

TEST(FluidMath, SoftLimitFlowShrinksByLength) {
    const auto [x, y] = math::softLimitFlow(3.0f, 4.0f);
    EXPECT_NEAR(x, 1.6363636f, kTol);
    EXPECT_NEAR(y, 2.1818182f, kTol);
    const auto [zx, zy] = math::softLimitFlow(0.0f, 0.0f);
    EXPECT_EQ(zx, 0.0f);
    EXPECT_EQ(zy, 0.0f);
}

TEST(FluidMath, TerminalSpeedCapOnlyCapsFastFlow) {
    const auto [sx, sy] = math::terminalSpeedCap(3.0f, 4.0f);
    EXPECT_NEAR(sx, 3.0f, kTol);
    EXPECT_NEAR(sy, 4.0f, kTol);
    const auto [fx, fy] = math::terminalSpeedCap(30.0f, 40.0f);
    EXPECT_NEAR(fx, 7.2f, kTol);
    EXPECT_NEAR(fy, 9.6f, kTol);
    const auto [ex, ey] = math::terminalSpeedCap(0.0f, 12.0f);
    EXPECT_NEAR(ex, 0.0f, kTol);
    EXPECT_NEAR(ey, 12.0f, kTol);
}

TEST(FluidMath, StateSideIsTheCeilingSquareRootAtLeastTwo) {
    EXPECT_EQ(math::stateSide(0), 2);
    EXPECT_EQ(math::stateSide(1), 2);
    EXPECT_EQ(math::stateSide(4), 2);
    EXPECT_EQ(math::stateSide(5), 3);
    EXPECT_EQ(math::stateSide(16), 4);
    EXPECT_EQ(math::stateSide(17), 5);
}

TEST(FluidMath, AttractorForceSaturates) {
    // f = 1 / (0.45 + 0.05) = 2; 2 * 6 / (6 + 2).
    EXPECT_NEAR(math::attractorForce(1.0f, 0.45f), 1.5f, kTol);
    EXPECT_NEAR(math::attractorForce(0.0f, 0.45f), 0.0f, kTol);
    EXPECT_LT(math::attractorForce(1000.0f, 0.0f), 6.0f);
}

TEST(FluidMath, IsCapturedIsStrictlyInsideTheRadius) {
    EXPECT_FALSE(math::isCaptured(1.0f, 1.0f, 4.0f, 5.0f, 5.0f));
    EXPECT_TRUE(math::isCaptured(1.0f, 1.0f, 4.0f, 5.0f, 5.1f));
    EXPECT_FALSE(math::isCaptured(1.0f, 1.0f, 4.0f, 5.0f, 4.9f));
    EXPECT_TRUE(math::isCaptured(2.0f, 3.0f, 2.0f, 3.0f, 0.1f));
}

TEST(FluidMath, SegDistReturnsDistanceAndProjection) {
    const auto mid = math::segDist(0.0f, 0.0f, 4.0f, 0.0f, 2.0f, 3.0f);
    EXPECT_NEAR(mid.first, 3.0f, kTol);
    EXPECT_NEAR(mid.second, 0.5f, kTol);
    const auto before = math::segDist(0.0f, 0.0f, 4.0f, 0.0f, -2.0f, 0.0f);
    EXPECT_NEAR(before.first, 2.0f, kTol);
    EXPECT_NEAR(before.second, 0.0f, kTol);
    const auto after = math::segDist(0.0f, 0.0f, 4.0f, 0.0f, 6.0f, 0.0f);
    EXPECT_NEAR(after.first, 2.0f, kTol);
    EXPECT_NEAR(after.second, 1.0f, kTol);
    const auto vertical = math::segDist(1.0f, 1.0f, 1.0f, 5.0f, 4.0f, 2.0f);
    EXPECT_NEAR(vertical.first, 3.0f, kTol);
    EXPECT_NEAR(vertical.second, 0.25f, kTol);
    const auto point = math::segDist(1.0f, 1.0f, 1.0f, 1.0f, 4.0f, 5.0f);
    EXPECT_NEAR(point.first, 5.0f, kTol);
    EXPECT_NEAR(point.second, 0.0f, kTol);
}

TEST(FluidMath, DragStepIsFrameRateIndependent) {
    EXPECT_NEAR(math::dragStep(0.0f, 1.0f, 0.5f, kFrame), 0.5f, kTol);
    EXPECT_NEAR(math::dragStep(0.0f, 1.0f, 0.5f, 2.0f * kFrame), 0.75f, kTol);
    EXPECT_NEAR(math::dragStep(2.0f, 0.0f, 0.25f), 1.5f, kTol);
    EXPECT_NEAR(math::dragStep(2.0f, 0.0f, 0.0f), 2.0f, kTol);
}

TEST(FluidMath, BloomPrefilterScale) {
    EXPECT_NEAR(math::bloomPrefilterScale(2.0f, 1.0f, 0.5f), 0.5f, kTol);
    EXPECT_NEAR(math::bloomPrefilterScale(1.0f, 1.0f, 0.5f), 0.125025f, kTol);
    EXPECT_NEAR(math::bloomPrefilterScale(1.2f, 1.0f, 0.5f), 0.20418419f, kTol);
    EXPECT_NEAR(math::bloomPrefilterScale(0.2f, 1.0f, 0.5f), 0.0f, kTol);
    EXPECT_NEAR(math::bloomPrefilterScale(0.0f, 1.0f, 0.5f), 0.0f, kTol);
}

TEST(FluidMath, ScaledFeaturesScalesLevelsAndBandsOnly) {
    GeodeFeatureFrame f{};
    f.rms = 0.3f;
    f.bass = 0.4f;
    f.mid = 0.5f;
    f.treble = 0.6f;
    f.centroid = 0.7f;
    f.bands[0] = 0.25f;
    f.bands[GEODE_BAND_COUNT - 1] = 0.7f;

    const GeodeFeatureFrame same = scaledFeatures(f, 1.0f);
    EXPECT_EQ(same.rms, f.rms);
    EXPECT_EQ(same.bands[0], f.bands[0]);

    const GeodeFeatureFrame doubled = scaledFeatures(f, 2.0f);
    EXPECT_NEAR(doubled.rms, 0.6f, kTol);
    EXPECT_NEAR(doubled.bass, 0.8f, kTol);
    EXPECT_NEAR(doubled.mid, 1.0f, kTol);
    EXPECT_NEAR(doubled.treble, 1.2f, kTol);
    EXPECT_NEAR(doubled.bands[0], 0.5f, kTol);
    EXPECT_NEAR(doubled.bands[GEODE_BAND_COUNT - 1], 1.4f, kTol);
    EXPECT_EQ(doubled.centroid, f.centroid);

    // Below the minimum drive the frame is scaled by the minimum, not left alone.
    const GeodeFeatureFrame quiet = scaledFeatures(f, 0.0f);
    EXPECT_NEAR(quiet.rms, 0.06f, kTol);
    EXPECT_NEAR(quiet.bass, 0.08f, kTol);
    EXPECT_NEAR(quiet.mid, 0.1f, kTol);
    EXPECT_NEAR(quiet.treble, 0.12f, kTol);
}

TEST(WaterMath, CatchWellIsAnUnlitPixel) {
    EXPECT_TRUE(water::isCatchWell(0.0f, 0.0f, 0.0f));
    EXPECT_TRUE(water::isCatchWell(0.0f, -1.0f, 0.0f));
    EXPECT_FALSE(water::isCatchWell(0.1f, 0.0f, 0.0f));
    EXPECT_FALSE(water::isCatchWell(0.0f, 0.1f, 0.0f));
    EXPECT_FALSE(water::isCatchWell(0.0f, 0.0f, 0.1f));
}

TEST(WaterMath, CatchWellRadiusIsClamped) {
    EXPECT_NEAR(water::catchWellRadius(0.01f), 0.03f, kTol);
    EXPECT_NEAR(water::catchWellRadius(0.5f), 0.3f, kTol);
    EXPECT_NEAR(water::catchWellRadius(0.2f), 0.2f, kTol);
}

TEST(WaterMath, CatchWellAmplitude) {
    EXPECT_NEAR(water::catchWellAmplitude(1.0f, 0.12f, 1.0f), -0.56f, kTol);
    // Speed clamps to 2, the spread to 2.5, the ripple strength to 2.
    EXPECT_NEAR(water::catchWellAmplitude(5.0f, 0.06f, 0.5f), -1.06f, kTol);
    EXPECT_NEAR(water::catchWellAmplitude(0.0f, 0.03f, 3.0f), -0.3f, kTol);
    EXPECT_NEAR(water::catchWellAmplitude(-1.0f, 0.03f, 3.0f), -0.3f, kTol);
    EXPECT_NEAR(water::catchWellAmplitude(1.0f, 0.3f, 1.0f), -0.224f, kTol);
    EXPECT_NEAR(water::catchWellAmplitude(1.0f, 0.5f, 1.0f), -0.224f, kTol);
    EXPECT_NEAR(water::catchWellAmplitude(1.0f, 0.12f, -1.0f), 0.0f, kTol);
}

TEST(CurlFlowMath, BeatDriveAndFieldAmp) {
    EXPECT_NEAR(curl::beatDrive(0.5f, 1.5f), 0.75f, kTol);
    EXPECT_NEAR(curl::beatDrive(0.5f, 3.0f), 1.0f, kTol);
    EXPECT_NEAR(curl::beatDrive(0.5f, -1.0f), 0.0f, kTol);
    EXPECT_NEAR(curl::fieldAmp(1.0f, 0.0f), 0.55f, kTol);
    EXPECT_NEAR(curl::fieldAmp(2.0f, 1.0f), 2.09f, kTol);
    EXPECT_NEAR(curl::fieldAmp(10.0f, 5.0f), 3.85f, kTol);
    EXPECT_NEAR(curl::fieldAmp(0.0f, -1.0f), 0.11f, kTol);
}

TEST(CurlFlowMath, RetentionAndFades) {
    EXPECT_NEAR(curl::retention(0.5f), 0.8f, kTol);
    EXPECT_NEAR(curl::retention(2.0f), 1.0f, kTol);
    EXPECT_NEAR(curl::retention(-1.0f), 0.6f, kTol);
    EXPECT_NEAR(curl::retention(0.5f, false), 0.45f, kTol);
    EXPECT_NEAR(curl::fadeAlpha(true, 0.5f, kFrame), 0.224f, kTol);
    EXPECT_NEAR(curl::fadeAlpha(false, 0.5f, 2.0f * kFrame), 0.80946775f, kTol);
    EXPECT_NEAR(curl::warpDecay(0.8f, kFrame), 0.796f, kTol);
    EXPECT_NEAR(curl::warpDecay(0.8f, 2.0f * kFrame), 0.633616f, kTol);
    EXPECT_NEAR(curl::warpDecay(1.5f, kFrame), 0.99f, kTol);
    EXPECT_NEAR(curl::warpDecay(-1.0f, kFrame), 0.0f, kTol);
}

TEST(CurlFlowMath, ParticleBrightness) {
    EXPECT_NEAR(curl::particleBrightness(0.5f), 1.025f, kTol);
    EXPECT_NEAR(curl::particleBrightness(2.0f), 1.2f, kTol);
    EXPECT_NEAR(curl::particleBrightness(-1.0f), 0.85f, kTol);
}
