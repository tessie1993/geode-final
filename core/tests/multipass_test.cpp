#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <set>
#include <utility>

#include "geode/viz/MultiPass.hpp"

namespace mp = geode::viz::multipass;

namespace {

mp::Spec on(float scale = mp::kDefaultScale, bool coarse = true, bool temporal = true) { return mp::Spec{true, scale, coarse, temporal}; }

}  // namespace

TEST(MultiPassSpec, HoldsTheScaleToItsRange) {
    EXPECT_FLOAT_EQ(mp::sanitized(on(0.1f)).scale, mp::kMinScale);
    EXPECT_FLOAT_EQ(mp::sanitized(on(0.9f)).scale, mp::kMaxScale);
    EXPECT_FLOAT_EQ(mp::sanitized(on(0.6f)).scale, 0.6f);
    EXPECT_FLOAT_EQ(mp::sanitized(on(mp::kMinScale)).scale, 0.25f);
    EXPECT_FLOAT_EQ(mp::sanitized(on(mp::kMaxScale)).scale, 0.75f);
    EXPECT_FLOAT_EQ(mp::sanitized(on(std::numeric_limits<float>::quiet_NaN())).scale, mp::kDefaultScale);
    EXPECT_FLOAT_EQ(mp::sanitized(on(std::numeric_limits<float>::infinity())).scale, mp::kDefaultScale);
    // The rest is left as declared.
    const mp::Spec kept = mp::sanitized(mp::Spec{false, 0.5f, false, true});
    EXPECT_FALSE(kept.on);
    EXPECT_FALSE(kept.coarse);
    EXPECT_TRUE(kept.temporal);
    EXPECT_FLOAT_EQ(mp::kDefaultScale, 0.5f);
}

TEST(MultiPassSizes, MarchAtTheScaleRoundedUp) {
    const mp::Size half = mp::marchSize(1080, 1920, 0.5f);
    EXPECT_EQ(half.width, 540);
    EXPECT_EQ(half.height, 960);
    // Rounded up, so the march covers the target.
    const mp::Size odd = mp::marchSize(1081, 1919, 0.5f);
    EXPECT_EQ(odd.width, 541);
    EXPECT_EQ(odd.height, 960);
    const mp::Size quarter = mp::marchSize(100, 40, 0.25f);
    EXPECT_EQ(quarter.width, 25);
    EXPECT_EQ(quarter.height, 10);
    // The scale is held to its range here too.
    const mp::Size clamped = mp::marchSize(100, 100, 1.0f);
    EXPECT_EQ(clamped.width, 75);
    EXPECT_EQ(clamped.height, 75);
    // Never empty, even for an empty target.
    const mp::Size tiny = mp::marchSize(0, -3, 0.5f);
    EXPECT_EQ(tiny.width, 1);
    EXPECT_EQ(tiny.height, 1);
    const mp::Size one = mp::marchSize(1, 1, 0.25f);
    EXPECT_EQ(one.width, 1);
    EXPECT_EQ(one.height, 1);
}

TEST(MultiPassSizes, CoarseCoversEveryMarchPixel) {
    const mp::Size coarse = mp::coarseSize(mp::Size{540, 960});
    EXPECT_EQ(coarse.width, 135);
    EXPECT_EQ(coarse.height, 240);
    const mp::Size up = mp::coarseSize(mp::Size{541, 961});
    EXPECT_EQ(up.width, 136);
    EXPECT_EQ(up.height, 241);
    const mp::Size small = mp::coarseSize(mp::Size{3, 4});
    EXPECT_EQ(small.width, 1);
    EXPECT_EQ(small.height, 1);
    const mp::Size five = mp::coarseSize(mp::Size{5, 8});
    EXPECT_EQ(five.width, 2);
    EXPECT_EQ(five.height, 2);
    const mp::Size empty = mp::coarseSize(mp::Size{0, -1});
    EXPECT_EQ(empty.width, 1);
    EXPECT_EQ(empty.height, 1);
    EXPECT_EQ(mp::kCoarseDivisor, 4);
}

TEST(MultiPassPlan, RunsThePassesTheStyleDeclares) {
    const mp::Plan full = mp::plan(on(0.5f, true, true), 800, 600, true);
    EXPECT_TRUE(full.passes);
    EXPECT_TRUE(full.coarse);
    EXPECT_TRUE(full.temporal);
    EXPECT_EQ(full.march.width, 400);
    EXPECT_EQ(full.march.height, 300);
    EXPECT_EQ(full.coarseSize.width, 100);
    EXPECT_EQ(full.coarseSize.height, 75);

    const mp::Plan plain = mp::plan(on(0.75f, false, false), 800, 600, true);
    EXPECT_TRUE(plain.passes);
    EXPECT_FALSE(plain.coarse);
    EXPECT_FALSE(plain.temporal);
    EXPECT_EQ(plain.march.width, 600);
    EXPECT_EQ(plain.march.height, 450);

    const mp::Plan coarseOnly = mp::plan(on(0.5f, true, false), 8, 8, true);
    EXPECT_TRUE(coarseOnly.coarse);
    EXPECT_FALSE(coarseOnly.temporal);
    const mp::Plan temporalOnly = mp::plan(on(0.5f, false, true), 8, 8, true);
    EXPECT_FALSE(temporalOnly.coarse);
    EXPECT_TRUE(temporalOnly.temporal);
}

TEST(MultiPassPlan, DrawsInOnePassWithoutHalfFloatsOrADeclaration) {
    for (const mp::Plan& p : {mp::plan(mp::Spec{}, 800, 600, true), mp::plan(on(), 800, 600, false), mp::plan(on(), 0, 600, true),
                              mp::plan(on(), 800, 0, true), mp::plan(on(), -1, -1, true)}) {
        EXPECT_FALSE(p.passes);
        EXPECT_FALSE(p.coarse);
        EXPECT_FALSE(p.temporal);
    }
    // The smallest real target still runs them.
    EXPECT_TRUE(mp::plan(on(), 1, 1, true).passes);
}

TEST(MultiPassJitter, HaltonIsTheRadicalInverse) {
    EXPECT_FLOAT_EQ(mp::halton(0, 2), 0.0f);
    EXPECT_FLOAT_EQ(mp::halton(1, 2), 0.5f);
    EXPECT_FLOAT_EQ(mp::halton(2, 2), 0.25f);
    EXPECT_FLOAT_EQ(mp::halton(3, 2), 0.75f);
    EXPECT_FLOAT_EQ(mp::halton(6, 2), 0.375f);
    EXPECT_FLOAT_EQ(mp::halton(1, 3), 1.0f / 3.0f);
    EXPECT_FLOAT_EQ(mp::halton(2, 3), 2.0f / 3.0f);
    EXPECT_FLOAT_EQ(mp::halton(4, 3), 4.0f / 9.0f);
    EXPECT_FLOAT_EQ(mp::halton(5, 3), 7.0f / 9.0f);
    EXPECT_FLOAT_EQ(mp::halton(7, 5), 0.4f + 1.0f / 25.0f);
    // A base below 2 has no expansion.
    EXPECT_FLOAT_EQ(mp::halton(5, 1), 0.0f);
    EXPECT_FLOAT_EQ(mp::halton(5, 0), 0.0f);
}

TEST(MultiPassJitter, CoversThePixelOverOneCycleAndRepeats) {
    std::set<std::pair<float, float>> seen;
    std::array<float, 2> sum{0.0f, 0.0f};
    int left = 0;
    int below = 0;
    for (std::uint32_t f = 0; f < mp::kJitterCycle; ++f) {
        const auto j = mp::jitter(f);
        EXPECT_GE(j[0], -0.5f);
        EXPECT_LT(j[0], 0.5f);
        EXPECT_GE(j[1], -0.5f);
        EXPECT_LT(j[1], 0.5f);
        seen.insert({j[0], j[1]});
        sum[0] += j[0];
        sum[1] += j[1];
        left += j[0] < 0.0f ? 1 : 0;
        below += j[1] < 0.0f ? 1 : 0;
        const auto again = mp::jitter(f + mp::kJitterCycle);
        EXPECT_FLOAT_EQ(again[0], j[0]);
        EXPECT_FLOAT_EQ(again[1], j[1]);
    }
    EXPECT_EQ(seen.size(), mp::kJitterCycle);
    // Spread evenly: about centred, half of the points on each side.
    EXPECT_NEAR(sum[0] / mp::kJitterCycle, 0.0f, 0.07f);
    EXPECT_NEAR(sum[1] / mp::kJitterCycle, 0.0f, 0.07f);
    EXPECT_EQ(left, 4);
    EXPECT_GE(below, 3);
    EXPECT_LE(below, 5);
    // The first frame's offset is the first Halton point, not the origin.
    const auto first = mp::jitter(0);
    EXPECT_FLOAT_EQ(first[0], 0.0f);
    EXPECT_FLOAT_EQ(first[1], 1.0f / 3.0f - 0.5f);
    const auto second = mp::jitter(1);
    EXPECT_FLOAT_EQ(second[0], -0.25f);
    EXPECT_FLOAT_EQ(second[1], 2.0f / 3.0f - 0.5f);
    EXPECT_EQ(mp::kJitterCycle, 8u);
}

TEST(MultiPassCost, HalfScaleMarchesAboutAQuarterOfThePixels) {
    EXPECT_FLOAT_EQ(mp::marchShare(mp::Spec{}), 1.0f);
    EXPECT_FLOAT_EQ(mp::marchShare(on(0.5f, false, true)), 0.25f);
    EXPECT_FLOAT_EQ(mp::marchShare(on(0.5f, true, true)), 0.25f + 0.125f * 0.125f);
    EXPECT_FLOAT_EQ(mp::marchShare(on(0.75f, false, false)), 0.5625f);
    EXPECT_FLOAT_EQ(mp::marchShare(on(2.0f, false, false)), 0.5625f);
    // Well under half a single pass at the default, even with the prepass.
    EXPECT_LT(mp::marchShare(on()), 0.3f);
}

TEST(MultiPassHistory, ReadableOnlyAfterAKeptFrameAtTheSameSize) {
    mp::History history;
    EXPECT_EQ(history.frame(), 0u);
    EXPECT_FALSE(history.begin(100, 50));
    history.kept();
    EXPECT_EQ(history.frame(), 1u);
    EXPECT_TRUE(history.begin(100, 50));
    history.kept();
    EXPECT_EQ(history.frame(), 2u);
    // A resize drops it for one frame.
    EXPECT_FALSE(history.begin(100, 60));
    history.kept();
    EXPECT_TRUE(history.begin(100, 60));
    EXPECT_FALSE(history.begin(90, 60));
    history.kept();
    EXPECT_TRUE(history.begin(90, 60));
    // A frame without the passes keeps nothing.
    history.lost();
    EXPECT_FALSE(history.begin(90, 60));
    history.kept();
    EXPECT_TRUE(history.begin(90, 60));
    EXPECT_EQ(history.frame(), 5u);
    // A new visit starts again, jitter cycle included.
    history.reset();
    EXPECT_EQ(history.frame(), 0u);
    EXPECT_FALSE(history.begin(90, 60));
}

TEST(MultiPassHistory, TheFrameCounterWraps) {
    mp::History history;
    for (int i = 0; i < 3; ++i) history.kept();
    EXPECT_EQ(history.frame(), 3u);
    EXPECT_EQ(mp::kFrameWrap, 1u << 24);
}

TEST(MultiPassConstants, MatchTheShaders) {
    // lib_multipass.glsl and multipass_resolve_frag.glsl spell these out; a change here must land there.
    EXPECT_FLOAT_EQ(mp::kDepthK, 4.0f);
    EXPECT_FLOAT_EQ(mp::kPositionWrap, 1024.0f);
    EXPECT_EQ(mp::kCameraTexels, 5);
    EXPECT_FLOAT_EQ(mp::kHistoryWeight, 0.9f);
    EXPECT_FLOAT_EQ(mp::kMaxCameraStep, 4.0f);
    EXPECT_FLOAT_EQ(mp::kMinScale, 0.25f);
    EXPECT_FLOAT_EQ(mp::kMaxScale, 0.75f);
}
