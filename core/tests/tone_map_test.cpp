// The HDR pipeline's maths (ToneMap.hpp), which tonemap_frag.glsl and bloom_down_frag.glsl mirror.
#include "geode/viz/ToneMap.hpp"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <limits>

#include "geode/viz/VisualSafety.hpp"

namespace geode::viz {
namespace {

constexpr float kTolerance = 1e-5f;

// ---- the sRGB transfer, extended above 1 ----------------------------------------------------

TEST(ToneMap, DecodeFollowsTheSrgbCurveAndKeepsTheHeadroom) {
    EXPECT_EQ(tone::srgbDecode(0.0f), 0.0f);
    EXPECT_EQ(tone::srgbDecode(-0.5f), 0.0f);
    EXPECT_NEAR(tone::srgbDecode(0.04f), 0.04f / 12.92f, kTolerance);
    EXPECT_NEAR(tone::srgbDecode(0.04045f), 0.04045f / 12.92f, kTolerance);
    EXPECT_NEAR(tone::srgbDecode(0.5f), std::pow(0.555f / 1.055f, 2.4f), kTolerance);
    EXPECT_NEAR(tone::srgbDecode(1.0f), 1.0f, kTolerance);
    EXPECT_NEAR(tone::srgbDecode(2.0f), std::pow(2.055f / 1.055f, 2.4f), 1e-4f);
    EXPECT_GT(tone::srgbDecode(2.0f), 4.9f);
}

TEST(ToneMap, EncodeIsTheInverseOfDecode) {
    EXPECT_EQ(tone::srgbEncode(0.0f), 0.0f);
    EXPECT_EQ(tone::srgbEncode(-1.0f), 0.0f);
    EXPECT_NEAR(tone::srgbEncode(0.002f), 0.002f * 12.92f, kTolerance);
    EXPECT_NEAR(tone::srgbEncode(0.0031308f), 0.0031308f * 12.92f, 1e-4f);
    EXPECT_NEAR(tone::srgbEncode(0.25f), 1.055f * std::pow(0.25f, 1.0f / 2.4f) - 0.055f, kTolerance);
    for (const float encoded : {0.01f, 0.2f, 0.5f, 0.9f, 1.0f, 1.7f, 3.0f}) {
        EXPECT_NEAR(tone::srgbEncode(tone::srgbDecode(encoded)), encoded, 1e-4f * std::max(1.0f, encoded)) << encoded;
    }
}

// ---- the filmic curve -------------------------------------------------------------------------

TEST(ToneMap, AcesFitIsTheFittedCurve) {
    EXPECT_EQ(tone::acesFit(0.0f), 0.0f);
    EXPECT_NEAR(tone::acesFit(1.0f), (2.51f + 0.03f) / (2.43f + 0.59f + 0.14f), kTolerance);
    EXPECT_NEAR(tone::acesFit(0.5f), 0.5f * (2.51f * 0.5f + 0.03f) / (0.5f * (2.43f * 0.5f + 0.59f) + 0.14f), kTolerance);
}

TEST(ToneMap, BlackStaysBlackAndTheWhitePointIsWhite) {
    EXPECT_EQ(tone::toneMap(0.0f), 0.0f);
    EXPECT_EQ(tone::toneMap(-2.0f), 0.0f);
    EXPECT_NEAR(tone::toneMap(tone::kWhite), 1.0f, kTolerance);
    EXPECT_EQ(tone::toneMap(tone::kWhite * 2.0f), 1.0f);
    EXPECT_EQ(tone::toneMap(tone::kHdrCeiling), 1.0f);
}

TEST(ToneMap, TheMiddleStaysWhereEachStyleWasTuned) {
    EXPECT_NEAR(tone::toneMap(0.18f), 0.18f, 0.015f);
    EXPECT_NEAR(tone::toneMap(0.5f), 0.5f, 0.05f);
    EXPECT_NEAR(tone::toneMap(1.0f), 0.78f, 0.01f);
    EXPECT_NEAR(tone::toneMap(0.5f),
                tone::acesFit(0.5f * tone::kExposure) / tone::acesFit(tone::kWhite * tone::kExposure), kTolerance);
}

TEST(ToneMap, HeadroomRollsOffSmoothlyInsteadOfClipping) {
    float previous = tone::toneMap(0.0f);
    for (int i = 1; i <= 400; ++i) {
        const float encoded = static_cast<float>(i) * tone::kWhite / 400.0f;
        const float shown = tone::toneMap(encoded);
        EXPECT_GT(shown, previous) << encoded;
        previous = shown;
    }
    // A value half again over a Style's white is still told apart from one twice over it.
    EXPECT_LT(tone::toneMap(1.5f), tone::toneMap(2.0f) - 0.03f);
    EXPECT_LT(tone::toneMap(2.0f), 1.0f);
}

TEST(ToneMap, SanitiseBoundsTheHdrRange) {
    EXPECT_EQ(tone::sanitize(std::numeric_limits<float>::quiet_NaN()), 0.0f);
    EXPECT_EQ(tone::sanitize(-1.0f), 0.0f);
    EXPECT_EQ(tone::sanitize(2.5f), 2.5f);
    EXPECT_EQ(tone::sanitize(100.0f), tone::kHdrCeiling);
    EXPECT_EQ(tone::sanitize(std::numeric_limits<float>::infinity()), tone::kHdrCeiling);
}

// ---- bloom ------------------------------------------------------------------------------------

TEST(ToneMap, BloomGathersOnlyLightAboveTheSoftThreshold) {
    EXPECT_EQ(tone::bloomContribution(0.4f, 1.0f, 0.5f), 0.0f);
    EXPECT_EQ(tone::bloomContribution(0.5f, 1.0f, 0.5f), 0.0f);
    EXPECT_NEAR(tone::bloomContribution(0.75f, 1.0f, 0.5f), 0.03125f, kTolerance);
    EXPECT_NEAR(tone::bloomContribution(1.0f, 1.0f, 0.5f), 0.125f, kTolerance);
    // Past the knee it is exactly the light above the threshold, and meets it smoothly.
    EXPECT_NEAR(tone::bloomContribution(1.5f, 1.0f, 0.5f), 0.5f, kTolerance);
    EXPECT_NEAR(tone::bloomContribution(3.0f, 1.0f, 0.5f), 2.0f, kTolerance);
    EXPECT_NEAR(tone::bloomContribution(1.49f, 1.0f, 0.5f), 0.49f, 1e-3f);
    EXPECT_EQ(tone::bloomContribution(-3.0f, 1.0f, 0.5f), 0.0f);
}

TEST(ToneMap, BloomWithoutAKneeIsAHardThreshold) {
    EXPECT_EQ(tone::bloomContribution(0.9f, 1.0f, 0.0f), 0.0f);
    EXPECT_NEAR(tone::bloomContribution(1.25f, 1.0f, 0.0f), 0.25f, kTolerance);
    EXPECT_EQ(tone::bloomContribution(-2.0f, -1.0f, 0.0f), 1.0f);
}

TEST(ToneMap, BloomFollowsTheBloomParameterWithinTheSafetyCap) {
    const tone::BloomSettings none = tone::bloomFor(0.0f, true);
    EXPECT_TRUE(none.on);
    EXPECT_FLOAT_EQ(none.threshold, tone::kBloomThreshold);
    EXPECT_FLOAT_EQ(none.knee, tone::kBloomKnee);
    EXPECT_FLOAT_EQ(none.strength, tone::kBloomStrength);

    const tone::BloomSettings half = tone::bloomFor(safety::kMaxFlashDepth * 0.5f, true);
    EXPECT_FLOAT_EQ(half.threshold, 0.8f);
    EXPECT_FLOAT_EQ(half.strength, tone::kBloomStrength + tone::kBloomParamStrength * safety::kMaxFlashDepth * 0.5f);

    const tone::BloomSettings full = tone::bloomFor(safety::kMaxFlashDepth, true);
    EXPECT_FLOAT_EQ(full.threshold, tone::kBloomLowestThreshold);
    EXPECT_FLOAT_EQ(full.strength, tone::kBloomStrength + tone::kBloomParamStrength * safety::kMaxFlashDepth);

    const tone::BloomSettings over = tone::bloomFor(5.0f, true);
    EXPECT_FLOAT_EQ(over.threshold, full.threshold);
    EXPECT_FLOAT_EQ(over.strength, full.strength);
    const tone::BloomSettings under = tone::bloomFor(-1.0f, true);
    EXPECT_FLOAT_EQ(under.threshold, none.threshold);
    EXPECT_FLOAT_EQ(under.strength, none.strength);
}

TEST(ToneMap, BloomIsOffWhenTheThermalTierDropsOptionalPasses) {
    const tone::BloomSettings off = tone::bloomFor(safety::kMaxFlashDepth, false);
    EXPECT_FALSE(off.on);
    EXPECT_EQ(off.strength, 0.0f);
}

TEST(ToneMap, TheMipChainHalvesFromHalfTheOutput) {
    std::array<tone::LevelSize, tone::kMaxLevels> sizes{};
    ASSERT_EQ(tone::bloomLevels(1080, 2400, sizes), tone::kMaxLevels);
    const int widths[] = {540, 270, 135, 68, 34, 17};
    const int heights[] = {1200, 600, 300, 150, 75, 38};
    for (size_t i = 0; i < sizes.size(); ++i) {
        EXPECT_EQ(sizes[i].width, widths[i]) << i;
        EXPECT_EQ(sizes[i].height, heights[i]) << i;
    }
}

TEST(ToneMap, TheMipChainStopsBeforeALevelGetsTooSmall) {
    std::array<tone::LevelSize, tone::kMaxLevels> sizes{};
    sizes.fill({99, 99});
    ASSERT_EQ(tone::bloomLevels(64, 16, sizes), 1);
    EXPECT_EQ(sizes[0].width, 32);
    EXPECT_EQ(sizes[0].height, 8);
    for (size_t i = 1; i < sizes.size(); ++i) {
        EXPECT_EQ(sizes[i].width, 0) << i;
        EXPECT_EQ(sizes[i].height, 0) << i;
    }
    EXPECT_EQ(tone::bloomLevels(32, 32, sizes), 2);
    EXPECT_EQ(tone::bloomLevels(15, 15, sizes), 1);
    EXPECT_EQ(tone::bloomLevels(14, 14, sizes), 0);
    EXPECT_EQ(sizes[0].width, 0);
    EXPECT_EQ(tone::bloomLevels(0, 0, sizes), 0);
    EXPECT_EQ(tone::bloomLevels(-4, 100, sizes), 0);
}

}  // namespace
}  // namespace geode::viz
