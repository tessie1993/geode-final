#include "geode/viz/Params.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <limits>
#include <set>
#include <string>
#include <string_view>

using geode::viz::blendParams;
using geode::viz::lerpParams;
using geode::viz::SceneParams;

namespace {

constexpr float kTol = 1e-5f;
constexpr int kPaletteCount = 21;

}  // namespace

TEST(SceneParams, PaletteTable) {
    const auto& palettes = SceneParams::palettes();
    ASSERT_EQ(static_cast<int>(palettes.size()), kPaletteCount);
    EXPECT_EQ(std::string(palettes[2].name), "Fire");
    EXPECT_FLOAT_EQ(palettes[2].range, 0.14f);
}

TEST(SceneParams, PaletteBaseAndRangeComeFromTheTableUnlessOverridden) {
    SceneParams p;
    EXPECT_FLOAT_EQ(p.paletteBase(), 0.0f);
    EXPECT_FLOAT_EQ(p.paletteRange(), 1.0f);
    EXPECT_FLOAT_EQ(p.palette2Base(), 0.5f);
    EXPECT_FLOAT_EQ(p.palette2Range(), 0.45f);
    EXPECT_FALSE(p.usesCustomPalette());
    EXPECT_FALSE(p.usesCustomPalette2());

    p.palette = 1;
    p.palette2 = 0;
    EXPECT_FLOAT_EQ(p.paletteBase(), 0.5f);
    EXPECT_FLOAT_EQ(p.paletteRange(), 0.45f);
    EXPECT_FLOAT_EQ(p.palette2Base(), 0.0f);
    EXPECT_FLOAT_EQ(p.palette2Range(), 1.0f);

    // Out-of-range indices clamp to the table.
    p.palette = 99;
    p.palette2 = -5;
    EXPECT_FLOAT_EQ(p.paletteBase(), 0.167f);
    EXPECT_FLOAT_EQ(p.paletteRange(), 0.08f);
    EXPECT_FLOAT_EQ(p.palette2Base(), 0.0f);
    EXPECT_FLOAT_EQ(p.palette2Range(), 1.0f);

    // An override of zero is still an override.
    p.paletteBaseOverride = 0.0f;
    EXPECT_TRUE(p.usesCustomPalette());
    EXPECT_FLOAT_EQ(p.paletteBase(), 0.0f);
    EXPECT_FLOAT_EQ(p.paletteRange(), 0.08f);
    p.paletteBaseOverride = SceneParams::kUnsetOverride;
    p.paletteRangeOverride = 0.0f;
    EXPECT_TRUE(p.usesCustomPalette());
    EXPECT_FLOAT_EQ(p.paletteBase(), 0.167f);
    EXPECT_FLOAT_EQ(p.paletteRange(), 0.0f);

    p.palette2BaseOverride = 0.3f;
    EXPECT_TRUE(p.usesCustomPalette2());
    EXPECT_FLOAT_EQ(p.palette2Base(), 0.3f);
    p.palette2BaseOverride = SceneParams::kUnsetOverride;
    EXPECT_FALSE(p.usesCustomPalette2());
    p.palette2RangeOverride = 0.0f;
    EXPECT_TRUE(p.usesCustomPalette2());
    EXPECT_FLOAT_EQ(p.palette2Range(), 0.0f);
}

TEST(SceneParams, EveryLerpedFloatIsANamedSettableField) {
    const auto& fields = SceneParams::lerpedFloats();
    ASSERT_EQ(static_cast<int>(fields.size()), SceneParams::kLerpedFloatCount);
    std::set<std::string> names;
    for (const auto& f : fields) {
        ASSERT_NE(f.name, nullptr);
        ASSERT_TRUE(f.member != nullptr) << f.name;
        EXPECT_TRUE(names.insert(f.name).second) << f.name;
        SceneParams p;
        constexpr float kProbe = 0.4375f;
        ASSERT_TRUE(p.set(f.name, kProbe)) << f.name;
        EXPECT_EQ(p.*f.member, kProbe) << f.name;
    }
}

TEST(SceneParams, EveryFieldNameIsUniqueAndSettable) {
    const auto& names = SceneParams::fieldNames();
    ASSERT_EQ(static_cast<int>(names.size()), SceneParams::kFieldCount);
    std::set<std::string> seen;
    for (const char* name : names) {
        ASSERT_NE(name, nullptr);
        EXPECT_TRUE(seen.insert(name).second) << name;
        SceneParams p;
        EXPECT_TRUE(p.set(name, 1.0f)) << name;
    }
}

TEST(SceneParams, SetRejectsUnknownNamesAndNonFiniteValues) {
    SceneParams p;
    EXPECT_FALSE(p.set("noSuchField", 1.0f));
    EXPECT_FALSE(p.set("speed", std::numeric_limits<float>::quiet_NaN()));
    EXPECT_FALSE(p.set("speed", std::numeric_limits<float>::infinity()));
    EXPECT_FALSE(p.set("symmetry", -std::numeric_limits<float>::infinity()));
    EXPECT_FLOAT_EQ(p.speed, 1.0f);
    EXPECT_EQ(p.symmetry, SceneParams::kDefaultSymmetryFolds);
}

TEST(SceneParams, SetConvertsByFieldType) {
    SceneParams p;
    EXPECT_TRUE(p.set("speed", 2.5f));
    EXPECT_FLOAT_EQ(p.speed, 2.5f);
    EXPECT_TRUE(p.set("paramFadeSec", 0.75f));
    EXPECT_FLOAT_EQ(p.paramFadeSec, 0.75f);

    EXPECT_TRUE(p.set("symmetry", 3.6f));
    EXPECT_EQ(p.symmetry, 4);
    EXPECT_TRUE(p.set("symmetry", 2.4f));
    EXPECT_EQ(p.symmetry, 2);
    EXPECT_TRUE(p.set("cymaticsModes", 7.0f));
    EXPECT_EQ(p.cymaticsModes, 7);

    EXPECT_TRUE(p.set("mirror", 0.6f));
    EXPECT_TRUE(p.mirror);
    EXPECT_TRUE(p.set("mirror", 0.5f));
    EXPECT_FALSE(p.mirror);
    EXPECT_TRUE(p.set("rippleOverlayEnabled", 1.0f));
    EXPECT_TRUE(p.rippleOverlayEnabled);
    EXPECT_TRUE(p.set("fluidSparkle", 0.0f));
    EXPECT_FALSE(p.fluidSparkle);
}

TEST(SceneParams, LerpGlidesFloatsAndTakesTheRestFromTheTarget) {
    SceneParams from;
    SceneParams to;
    from.speed = 1.0f;
    to.speed = 3.0f;
    from.formDrive = 0.0f;
    to.formDrive = 1.0f;
    to.symmetry = 3;
    to.mirror = true;
    to.paletteBaseOverride = 0.25f;
    to.paramFadeSec = 2.0f;

    const SceneParams q = lerpParams(from, to, 0.25f);
    EXPECT_NEAR(q.speed, 1.5f, kTol);
    EXPECT_NEAR(q.formDrive, 0.25f, kTol);
    EXPECT_EQ(q.symmetry, 3);
    EXPECT_TRUE(q.mirror);
    EXPECT_FLOAT_EQ(q.paletteBaseOverride, 0.25f);
    EXPECT_FLOAT_EQ(q.paramFadeSec, 2.0f);

    EXPECT_NEAR(lerpParams(from, to, 0.0f).speed, 1.0f, kTol);
    EXPECT_NEAR(lerpParams(from, to, 1.0f).speed, 3.0f, kTol);
    // lerp is not clamped.
    EXPECT_NEAR(lerpParams(from, to, 2.0f).speed, 5.0f, kTol);
}

TEST(SceneParams, BlendClampsAndSwitchesDiscreteFieldsAtHalfway) {
    SceneParams a;
    SceneParams b;
    a.zoom = 1.0f;
    b.zoom = 2.0f;
    a.symmetry = 3;
    b.symmetry = 8;
    b.kaleidoscope = true;

    const SceneParams quarter = blendParams(a, b, 0.25f);
    EXPECT_NEAR(quarter.zoom, 1.25f, kTol);
    EXPECT_EQ(quarter.symmetry, 3);
    EXPECT_FALSE(quarter.kaleidoscope);

    EXPECT_EQ(blendParams(a, b, 0.49f).symmetry, 3);
    const SceneParams half = blendParams(a, b, 0.5f);
    EXPECT_EQ(half.symmetry, 8);
    EXPECT_TRUE(half.kaleidoscope);
    EXPECT_NEAR(half.zoom, 1.5f, kTol);

    EXPECT_NEAR(blendParams(a, b, 2.0f).zoom, 2.0f, kTol);
    EXPECT_EQ(blendParams(a, b, 2.0f).symmetry, 8);
    EXPECT_NEAR(blendParams(a, b, -1.0f).zoom, 1.0f, kTol);
    EXPECT_EQ(blendParams(a, b, -1.0f).symmetry, 3);
}
