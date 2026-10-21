#include "geode/viz/ThermalGovernor.hpp"

#include <gtest/gtest.h>

#include <cmath>

using geode::viz::ThermalGovernor;
using geode::viz::ThermalTier;
using geode::viz::thermalTierInfo;

namespace {

constexpr float kFrameSeconds = 1.0f / 60.0f;
constexpr int kStatusModerate = 2;
constexpr int kStatusNone = 0;

void run(ThermalGovernor& governor, float seconds) {
    const int frames = static_cast<int>(std::lround(seconds / kFrameSeconds));
    for (int i = 0; i < frames; ++i) governor.onFrame(kFrameSeconds);
}

}  // namespace

TEST(ThermalTierInfo, CeilingsFallWithHeat) {
    EXPECT_FLOAT_EQ(thermalTierInfo(ThermalTier::Full).renderScale, 1.0f);
    EXPECT_GT(thermalTierInfo(ThermalTier::Full).renderScale, thermalTierInfo(ThermalTier::Eased).renderScale);
    EXPECT_GT(thermalTierInfo(ThermalTier::Eased).renderScale, thermalTierInfo(ThermalTier::Reduced).renderScale);
    EXPECT_GT(thermalTierInfo(ThermalTier::Reduced).renderScale, thermalTierInfo(ThermalTier::Minimal).renderScale);
}

TEST(ThermalTierInfo, HotTiersDropTheOptionalPasses) {
    EXPECT_TRUE(thermalTierInfo(ThermalTier::Eased).optionalPasses);
    EXPECT_FALSE(thermalTierInfo(ThermalTier::Reduced).optionalPasses);
}

TEST(ThermalGovernor, StartsCool) {
    ThermalGovernor governor;
    EXPECT_EQ(governor.tier(), ThermalTier::Full);
    EXPECT_FLOAT_EQ(governor.renderCeiling(), 1.0f);
}

TEST(ThermalGovernor, EscalatesAfterTheDwell) {
    ThermalGovernor governor;
    governor.setPlatformStatus(kStatusModerate);
    run(governor, 2.0f);
    EXPECT_EQ(governor.tier(), ThermalTier::Full);
    run(governor, 3.0f);
    EXPECT_EQ(governor.tier(), ThermalTier::Reduced);
    EXPECT_FLOAT_EQ(governor.renderCeiling(), thermalTierInfo(ThermalTier::Reduced).renderScale);
}

TEST(ThermalGovernor, RelaxesOneTierAtATime) {
    ThermalGovernor governor;
    governor.setPlatformStatus(kStatusModerate);
    run(governor, 5.0f);
    ASSERT_EQ(governor.tier(), ThermalTier::Reduced);
    governor.setPlatformStatus(kStatusNone);
    run(governor, 30.0f);
    EXPECT_EQ(governor.tier(), ThermalTier::Reduced);
    run(governor, 32.0f);
    EXPECT_EQ(governor.tier(), ThermalTier::Eased);
}

TEST(ThermalGovernor, HeadroomForecastEscalates) {
    ThermalGovernor governor;
    governor.setThermalHeadroom(0.97f);
    run(governor, 5.0f);
    EXPECT_EQ(governor.tier(), ThermalTier::Minimal);
}

TEST(ThermalGovernor, RendersArePinnedToFull) {
    ThermalGovernor governor;
    governor.setPlatformStatus(kStatusModerate);
    run(governor, 5.0f);
    ASSERT_EQ(governor.tier(), ThermalTier::Reduced);
    governor.beginOffscreenRender();
    EXPECT_TRUE(governor.offscreen());
    EXPECT_EQ(governor.tier(), ThermalTier::Full);
    EXPECT_FLOAT_EQ(governor.renderCeiling(), 1.0f);
    governor.endOffscreenRender();
    EXPECT_FALSE(governor.offscreen());
    EXPECT_EQ(governor.tier(), ThermalTier::Reduced);
}

TEST(ThermalGovernor, SlowFramesAloneDoNotHeatIt) {
    // How busy the GPU is belongs to the load scale; the governor reads only the platform.
    ThermalGovernor governor;
    for (int i = 0; i < 600; ++i) governor.onFrame(0.1f);
    EXPECT_EQ(governor.tier(), ThermalTier::Full);
}
