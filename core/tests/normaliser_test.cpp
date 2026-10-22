#include "geode/viz/Normaliser.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <limits>

#include "geode/viz/SharedEffects.hpp"

namespace geode::viz::mod {
namespace {

constexpr float kTolerance = 1e-4f;
constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();

TEST(Normaliser, AFractionSpreadsOverItsRangeAlongItsCurve) {
    const NativeRange linear{2.0f, 6.0f, RangeCurve::Linear, false};
    EXPECT_EQ(toNative(linear, 0.0f), 2.0f);
    EXPECT_EQ(toNative(linear, 0.25f), 3.0f);
    EXPECT_EQ(toNative(linear, 1.0f), 6.0f);
    EXPECT_EQ(toNative(linear, 4.0f), 6.0f) << "kept to 0..1 first";
    EXPECT_EQ(toNative(linear, -1.0f), 2.0f);
    EXPECT_EQ(toNative(linear, kNaN), 2.0f);
    EXPECT_NEAR(toNative(NativeRange{0.0f, 4.0f, RangeCurve::Exponential, false}, 0.5f), 1.0f, kTolerance);
    EXPECT_NEAR(toNative(NativeRange{0.0f, 4.0f, RangeCurve::Logarithmic, false}, 0.25f), 2.0f, kTolerance);
    EXPECT_EQ(toNative(NativeRange{1.0f, 8.0f, RangeCurve::Linear, true}, 0.5f), 5.0f) << "4.5 lands on a step";
    EXPECT_EQ(toNative(NativeRange{1.0f, -1.0f, RangeCurve::Linear, false}, 0.25f), 0.5f)
        << "a range may run downwards";
}

TEST(Normaliser, ReadsAManifestsCurveAndExtraNames) {
    EXPECT_EQ(curveNamed("linear"), RangeCurve::Linear);
    EXPECT_EQ(curveNamed("exponential"), RangeCurve::Exponential);
    EXPECT_EQ(curveNamed("logarithmic"), RangeCurve::Logarithmic);
    EXPECT_EQ(curveNamed(""), RangeCurve::Linear);
    EXPECT_EQ(curveNamed("wobbly"), RangeCurve::Linear);
    EXPECT_EQ(extraNamed("scale"), 0);
    EXPECT_EQ(extraNamed("flight"), 4);
    EXPECT_EQ(extraNamed("center_y"), static_cast<int>(kExtraCount) - 1);
    EXPECT_EQ(extraNamed("density"), -1);
    EXPECT_EQ(extraNamed("SCALE"), -1);
}

TEST(Normaliser, TheSharedCoreRangesGiveTheSharedCoresOwnValues) {
    const FamilyRanges core = FamilyRanges::sharedCore();
    for (size_t p = 0; p < kSharedCount; ++p) {
        for (const float u : {0.0f, 0.13f, 0.49f, 0.5f, 0.77f, 1.0f}) {
            EXPECT_NEAR(toNative(core.shared[p], u), fromUnit(static_cast<Shared>(p), u), kTolerance)
                << sharedSpecs()[p].name << " at " << u;
        }
    }
    for (size_t e = 0; e < kExtraCount; ++e) EXPECT_EQ(toNative(core.extras[e], 0.3f), 0.3f);
}

TEST(Normaliser, AFamilyDeclaresItsOwnRanges) {
    FamilyRanges family = FamilyRanges::sharedCore();
    EXPECT_TRUE(family.declareExtra("count", 3.0f, 12.0f, "linear", true));
    EXPECT_TRUE(family.declareExtra("fog", 0.0f, 0.5f, "exponential"));
    EXPECT_FALSE(family.declareExtra("density", 0.0f, 2.0f));
    EXPECT_FALSE(family.declareExtra("size", 0.0f, std::numeric_limits<float>::infinity()));
    EXPECT_EQ(family.extras[static_cast<size_t>(extraNamed("size"))].max, 1.0f) << "unchanged";
    EXPECT_TRUE(family.declareShared(Shared::Zoom, NativeRange{0.5f, 2.0f}));
    EXPECT_FALSE(family.declareShared(Shared::Zoom, NativeRange{kNaN, 2.0f}));
    EXPECT_FALSE(family.declareShared(static_cast<Shared>(kSharedCount), NativeRange{}));

    Evaluated evaluated;
    evaluated.unitShared[static_cast<size_t>(Shared::Zoom)] = 0.5f;
    evaluated.extras[static_cast<size_t>(extraNamed("count"))] = 0.5f;
    evaluated.extras[static_cast<size_t>(extraNamed("fog"))] = 0.5f;
    const NativeFrame frame = toNative(family, evaluated);
    EXPECT_NEAR(frame[Shared::Zoom], 1.25f, kTolerance);
    EXPECT_EQ(frame.extras[static_cast<size_t>(extraNamed("count"))], 8.0f);
    EXPECT_NEAR(frame.extras[static_cast<size_t>(extraNamed("fog"))], 0.125f, kTolerance);
}

TEST(Normaliser, TheSafetyClampRunsLast) {
    FamilyRanges family = FamilyRanges::sharedCore();
    Evaluated evaluated;
    evaluated.unitShared[static_cast<size_t>(Shared::Vignette)] = kNaN;
    evaluated.unitShared[static_cast<size_t>(Shared::Tile)] = 9.0f;
    family.declareShared(Shared::Glow, NativeRange{1.0f, 0.0f});
    evaluated.unitShared[static_cast<size_t>(Shared::Glow)] = 0.25f;
    const NativeFrame frame = toNative(family, evaluated);
    for (size_t p = 0; p < kSharedCount; ++p) {
        EXPECT_TRUE(std::isfinite(frame.shared[p])) << sharedSpecs()[p].name;
    }
    EXPECT_EQ(frame[Shared::Vignette], 0.0f);
    EXPECT_EQ(frame[Shared::Tile], spec(Shared::Tile).max);
    EXPECT_NEAR(frame[Shared::Glow], 0.75f, kTolerance);
}

TEST(Normaliser, TheEffectsAreReadFromTheFamilysOwnUnits) {
    FamilyRanges family = FamilyRanges::sharedCore();
    family.declareShared(Shared::Trails, NativeRange{0.0f, 0.5f});
    CustomizationPlan plan = CustomizationPlan::defaults();
    plan.shared[static_cast<size_t>(Shared::Trails)] = 0.5f;
    plan.shared[static_cast<size_t>(Shared::ParticleKind)] = 1.0f;
    ReactionGraph graph;
    const SharedEffects e = SharedEffects::from(toNative(family, graph.evaluate(plan, SignalFrame{}, 1.0f / 60.0f)));
    EXPECT_NEAR(e.trails, 0.25f, kTolerance);
    EXPECT_EQ(e.particleKind, 3);
}

}  // namespace
}  // namespace geode::viz::mod
