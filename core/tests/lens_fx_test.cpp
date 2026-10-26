// The lens effects and the 3D particle layer (LensFx.hpp), and how a Style pack declares them
// ("lens", "particles3d"). lens_frag.glsl, dof_frag.glsl, tonemap_frag.glsl and particles3d_vert.glsl
// mirror the maths held here.
#include <gtest/gtest.h>

#include <cmath>
#include <limits>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "geode/viz/LensFx.hpp"
#include "geode/viz/StylePacks.hpp"

namespace geode::viz {
namespace {

namespace packs = geode::viz::packs;

constexpr float kTolerance = 1e-5f;
constexpr float kNan = std::numeric_limits<float>::quiet_NaN();

// ---- declaring the effects in a pack --------------------------------------------------------------

constexpr const char* kFamilies = R"({
  "version": 1,
  "families": [
    {"name": "shader", "parameters": []},
    {"name": "plate", "parameters": [{"key": "mode", "type": "int", "min": 0, "max": 4, "default": 1}]}
  ]
})";

std::optional<packs::StylePack> manifestWith(const std::string& family, const std::string& fields,
                                             std::vector<packs::PackProblem>& problems) {
    std::vector<packs::PackProblem> familyProblems;
    const auto families = packs::parseFamilies(kFamilies, familyProblems);
    EXPECT_TRUE(families.has_value());
    const std::string text = R"({"version": 1, "id": "x", "label": "X", "family": ")" + family + "\"" +
                             (fields.empty() ? "" : ", " + fields) + "}";
    return packs::parseManifest("x", text, families.value_or(std::vector<packs::FamilySchema>{}), problems);
}

TEST(LensManifest, ReadsEveryField) {
    std::vector<packs::PackProblem> problems;
    const auto pack = manifestWith("shader", R"("lens": {"threshold": 0.7,
        "flare": {"amount": 0.6, "ghosts": 5, "halo": 0.3, "dispersion": 0.2},
        "godRays": {"amount": 0.4, "source": [0.25, 0.75], "length": 0.8, "decay": 0.9},
        "streaks": {"amount": 0.5, "shape": "zoom", "spokes": 6, "length": 0.35},
        "depthOfField": {"aperture": 0.45, "focus": 0.3}},
        "particles3d": {"kind": "sparks", "amount": 0.8, "count": 1.5, "speed": 2, "size": 0.5, "tint": [1, 0.5, 0.25]})",
                                   problems);
    ASSERT_TRUE(pack.has_value()) << (problems.empty() ? "" : problems.front().detail);
    const lens::LensSpec& l = pack->lensEffects;
    EXPECT_FLOAT_EQ(l.threshold, 0.7f);
    EXPECT_FLOAT_EQ(l.flare.amount, 0.6f);
    EXPECT_EQ(l.flare.ghosts, 5);
    EXPECT_FLOAT_EQ(l.flare.halo, 0.3f);
    EXPECT_FLOAT_EQ(l.flare.dispersion, 0.2f);
    EXPECT_FLOAT_EQ(l.godRays.amount, 0.4f);
    EXPECT_FLOAT_EQ(l.godRays.x, 0.25f);
    EXPECT_FLOAT_EQ(l.godRays.y, 0.75f);
    EXPECT_FLOAT_EQ(l.godRays.length, 0.8f);
    EXPECT_FLOAT_EQ(l.godRays.decay, 0.9f);
    EXPECT_FLOAT_EQ(l.streaks.amount, 0.5f);
    EXPECT_EQ(l.streaks.shape, lens::StreakShape::Zoom);
    EXPECT_EQ(l.streaks.spokes, 6);
    EXPECT_FLOAT_EQ(l.streaks.length, 0.35f);
    EXPECT_FLOAT_EQ(l.depthOfField.aperture, 0.45f);
    EXPECT_FLOAT_EQ(l.depthOfField.focus, 0.3f);
    EXPECT_TRUE(l.glare());
    const lens::ParticleSpec& p = pack->particles3d;
    EXPECT_EQ(p.kind, lens::ParticleKind::Sparks);
    EXPECT_FLOAT_EQ(p.amount, 0.8f);
    EXPECT_FLOAT_EQ(p.count, 1.5f);
    EXPECT_FLOAT_EQ(p.speed, 2.0f);
    EXPECT_FLOAT_EQ(p.size, 0.5f);
    EXPECT_FLOAT_EQ(p.tint[0], 1.0f);
    EXPECT_FLOAT_EQ(p.tint[1], 0.5f);
    EXPECT_FLOAT_EQ(p.tint[2], 0.25f);
    EXPECT_TRUE(problems.empty());
}

TEST(LensManifest, NothingDeclaredIsEverythingOff) {
    std::vector<packs::PackProblem> problems;
    const auto pack = manifestWith("shader", "", problems);
    ASSERT_TRUE(pack.has_value());
    const lens::LensSpec defaults;
    EXPECT_FALSE(pack->lensEffects.glare());
    EXPECT_FLOAT_EQ(pack->lensEffects.threshold, defaults.threshold);
    EXPECT_FLOAT_EQ(pack->lensEffects.depthOfField.aperture, 0.0f);
    EXPECT_FLOAT_EQ(pack->particles3d.amount, 0.0f);
    EXPECT_EQ(pack->particles3d.kind, lens::ParticleKind::Streaks);
}

TEST(LensManifest, EachPartIsOptional) {
    std::vector<packs::PackProblem> problems;
    const auto pack =
        manifestWith("shader", R"("lens": {"streaks": {"amount": 0.5}}, "particles3d": {"amount": 0.3})", problems);
    ASSERT_TRUE(pack.has_value());
    const lens::LensSpec defaults;
    EXPECT_FLOAT_EQ(pack->lensEffects.flare.amount, 0.0f);
    EXPECT_FLOAT_EQ(pack->lensEffects.godRays.amount, 0.0f);
    EXPECT_EQ(pack->lensEffects.streaks.shape, lens::StreakShape::Star);
    EXPECT_EQ(pack->lensEffects.streaks.spokes, defaults.streaks.spokes);
    EXPECT_FLOAT_EQ(pack->lensEffects.streaks.length, defaults.streaks.length);
    EXPECT_FLOAT_EQ(pack->lensEffects.godRays.x, defaults.godRays.x);
    EXPECT_FLOAT_EQ(pack->lensEffects.godRays.y, defaults.godRays.y);
    EXPECT_TRUE(pack->lensEffects.glare());
    EXPECT_EQ(pack->particles3d.kind, lens::ParticleKind::Streaks);
    EXPECT_FLOAT_EQ(pack->particles3d.count, 1.0f);
    EXPECT_FLOAT_EQ(pack->particles3d.tint[1], 1.0f);
}

TEST(LensManifest, ReadsEveryShapeAndKind) {
    const std::pair<const char*, lens::StreakShape> shapes[] = {{"star", lens::StreakShape::Star},
                                                                {"zoom", lens::StreakShape::Zoom},
                                                                {"anamorphic", lens::StreakShape::Anamorphic}};
    for (const auto& [name, shape] : shapes) {
        std::vector<packs::PackProblem> problems;
        const auto pack = manifestWith(
            "shader", std::string(R"("lens": {"streaks": {"amount": 1, "shape": ")") + name + "\"}}", problems);
        ASSERT_TRUE(pack.has_value()) << name;
        EXPECT_EQ(pack->lensEffects.streaks.shape, shape) << name;
    }
    std::vector<packs::PackProblem> problems;
    const auto pack = manifestWith("shader", R"("particles3d": {"kind": "streaks", "amount": 1})", problems);
    ASSERT_TRUE(pack.has_value());
    EXPECT_EQ(pack->particles3d.kind, lens::ParticleKind::Streaks);
}

TEST(LensManifest, HoldsValuesToTheirRangesAndRoundsCounts) {
    std::vector<packs::PackProblem> problems;
    const auto pack = manifestWith("shader", R"("lens": {"threshold": 3,
        "flare": {"amount": 2, "ghosts": 3.6, "halo": -1, "dispersion": 4},
        "godRays": {"amount": 0.5, "source": [-1, 2], "length": 0.01, "decay": 1.5},
        "streaks": {"amount": 0.5, "spokes": 5.4, "length": 9},
        "depthOfField": {"aperture": 7, "focus": 0}},
        "particles3d": {"amount": -2, "count": 9, "speed": 0, "size": 10, "tint": [-1, 5, 1]})",
                                   problems);
    ASSERT_TRUE(pack.has_value());
    const lens::LensSpec& l = pack->lensEffects;
    EXPECT_FLOAT_EQ(l.threshold, lens::kMaxThreshold);
    EXPECT_FLOAT_EQ(l.flare.amount, 1.0f);
    EXPECT_EQ(l.flare.ghosts, 4);
    EXPECT_FLOAT_EQ(l.flare.halo, 0.0f);
    EXPECT_FLOAT_EQ(l.flare.dispersion, 1.0f);
    EXPECT_FLOAT_EQ(l.godRays.x, 0.0f);
    EXPECT_FLOAT_EQ(l.godRays.y, 1.0f);
    EXPECT_FLOAT_EQ(l.godRays.length, lens::kMinRayLength);
    EXPECT_FLOAT_EQ(l.godRays.decay, lens::kMaxRayDecay);
    EXPECT_EQ(l.streaks.spokes, 5);
    EXPECT_FLOAT_EQ(l.streaks.length, lens::kMaxStreakLength);
    EXPECT_FLOAT_EQ(l.depthOfField.aperture, 1.0f);
    EXPECT_FLOAT_EQ(l.depthOfField.focus, lens::kMinFocus);
    const lens::ParticleSpec& p = pack->particles3d;
    EXPECT_FLOAT_EQ(p.amount, 0.0f);
    EXPECT_FLOAT_EQ(p.count, lens::kMaxParticleCount);
    EXPECT_FLOAT_EQ(p.speed, lens::kMinParticleSpeed);
    EXPECT_FLOAT_EQ(p.size, lens::kMaxParticleSize);
    EXPECT_FLOAT_EQ(p.tint[0], 0.0f);
    EXPECT_FLOAT_EQ(p.tint[1], lens::kMaxTint);
    EXPECT_FLOAT_EQ(p.tint[2], 1.0f);
}

TEST(LensManifest, RejectsMalformedDeclarations) {
    const char* bad[] = {
        R"("lens": 1)",
        R"("lens": {"threshold": "high"})",
        R"("lens": {"flare": true})",
        R"("lens": {"flare": {"amount": "lots"}})",
        R"("lens": {"flare": {"ghosts": "four"}})",
        R"("lens": {"flare": {"halo": []}})",
        R"("lens": {"flare": {"dispersion": {}}})",
        R"("lens": {"godRays": 2})",
        R"("lens": {"godRays": {"amount": null}})",
        R"("lens": {"godRays": {"source": [0.5]}})",
        R"("lens": {"godRays": {"source": [0.5, "top"]}})",
        R"("lens": {"godRays": {"source": 0.5}})",
        R"("lens": {"godRays": {"length": "long"}})",
        R"("lens": {"godRays": {"decay": "slow"}})",
        R"("lens": {"streaks": "on"})",
        R"("lens": {"streaks": {"shape": "spiral"}})",
        R"("lens": {"streaks": {"shape": 1}})",
        R"("lens": {"streaks": {"amount": "half"}})",
        R"("lens": {"streaks": {"spokes": "six"}})",
        R"("lens": {"streaks": {"length": "far"}})",
        R"("lens": {"depthOfField": 0.5})",
        R"("lens": {"depthOfField": {"aperture": "wide"}})",
        R"("lens": {"depthOfField": {"focus": "near"}})",
        R"("particles3d": [])",
        R"("particles3d": {"kind": "comets"})",
        R"("particles3d": {"kind": 0})",
        R"("particles3d": {"amount": "all"})",
        R"("particles3d": {"count": "many"})",
        R"("particles3d": {"speed": "fast"})",
        R"("particles3d": {"size": "big"})",
        R"("particles3d": {"tint": [1, 1]})",
        R"("particles3d": {"tint": "white"})",
    };
    for (const char* fields : bad) {
        std::vector<packs::PackProblem> problems;
        EXPECT_FALSE(manifestWith("shader", fields, problems)) << fields;
        ASSERT_EQ(problems.size(), 1u) << fields;
        EXPECT_EQ(problems.front().error, packs::PackError::BadValue) << fields;
        EXPECT_EQ(problems.front().where, "x") << fields;
    }
}

TEST(LensManifest, OnlyAShaderStyleDeclaresDepthOfField) {
    std::vector<packs::PackProblem> problems;
    EXPECT_FALSE(manifestWith("plate", R"("lens": {"depthOfField": {"aperture": 0.5}})", problems));
    ASSERT_EQ(problems.size(), 1u);
    EXPECT_EQ(problems.front().error, packs::PackError::BadValue);
    problems.clear();
    const auto glare =
        manifestWith("plate", R"("lens": {"flare": {"amount": 0.5}}, "particles3d": {"amount": 0.5})", problems);
    ASSERT_TRUE(glare.has_value());
    EXPECT_FLOAT_EQ(glare->lensEffects.flare.amount, 0.5f);
    EXPECT_FLOAT_EQ(glare->particles3d.amount, 0.5f);
}

// ---- held to range -------------------------------------------------------------------------------

TEST(LensSpec, NotANumberTakesTheDefault) {
    lens::LensSpec spec;
    spec.threshold = kNan;
    spec.flare = {kNan, 4, kNan, kNan};
    spec.godRays = {kNan, kNan, kNan, kNan, kNan};
    spec.streaks.amount = kNan;
    spec.streaks.length = kNan;
    spec.depthOfField = {kNan, kNan};
    const lens::LensSpec s = lens::sanitized(spec);
    const lens::LensSpec d;
    EXPECT_FLOAT_EQ(s.threshold, d.threshold);
    EXPECT_FLOAT_EQ(s.flare.amount, 0.0f);
    EXPECT_FLOAT_EQ(s.flare.halo, d.flare.halo);
    EXPECT_FLOAT_EQ(s.flare.dispersion, d.flare.dispersion);
    EXPECT_FLOAT_EQ(s.godRays.amount, 0.0f);
    EXPECT_FLOAT_EQ(s.godRays.x, d.godRays.x);
    EXPECT_FLOAT_EQ(s.godRays.y, d.godRays.y);
    EXPECT_FLOAT_EQ(s.godRays.length, d.godRays.length);
    EXPECT_FLOAT_EQ(s.godRays.decay, d.godRays.decay);
    EXPECT_FLOAT_EQ(s.streaks.amount, 0.0f);
    EXPECT_FLOAT_EQ(s.streaks.length, d.streaks.length);
    EXPECT_FLOAT_EQ(s.depthOfField.aperture, 0.0f);
    EXPECT_FLOAT_EQ(s.depthOfField.focus, d.depthOfField.focus);
    EXPECT_FALSE(s.glare());

    lens::ParticleSpec particles;
    particles.amount = kNan;
    particles.count = kNan;
    particles.speed = kNan;
    particles.size = kNan;
    particles.tint = {kNan, kNan, kNan};
    const lens::ParticleSpec p = lens::sanitized(particles);
    EXPECT_FLOAT_EQ(p.amount, 0.0f);
    EXPECT_FLOAT_EQ(p.count, 1.0f);
    EXPECT_FLOAT_EQ(p.speed, 1.0f);
    EXPECT_FLOAT_EQ(p.size, 1.0f);
    EXPECT_FLOAT_EQ(p.tint[0], 1.0f);
    EXPECT_FLOAT_EQ(p.tint[2], 1.0f);
}

TEST(LensSpec, HoldsEachEndOfEveryRange) {
    lens::LensSpec low;
    low.threshold = 0.1f;
    low.flare = {-1.0f, 0, -1.0f, -1.0f};
    low.godRays = {-1.0f, -1.0f, -1.0f, 0.0f, 0.0f};
    low.streaks = {-1.0f, lens::StreakShape::Anamorphic, 0, 0.0f};
    low.depthOfField = {-1.0f, 0.0f};
    const lens::LensSpec l = lens::sanitized(low);
    EXPECT_FLOAT_EQ(l.threshold, lens::kMinThreshold);
    EXPECT_EQ(l.flare.ghosts, lens::kMinGhosts);
    EXPECT_FLOAT_EQ(l.godRays.length, lens::kMinRayLength);
    EXPECT_FLOAT_EQ(l.godRays.decay, lens::kMinRayDecay);
    EXPECT_EQ(l.streaks.spokes, lens::kMinSpokes);
    EXPECT_EQ(l.streaks.shape, lens::StreakShape::Anamorphic);
    EXPECT_FLOAT_EQ(l.streaks.length, lens::kMinStreakLength);
    EXPECT_FLOAT_EQ(l.depthOfField.focus, lens::kMinFocus);
    EXPECT_FLOAT_EQ(l.godRays.x, 0.0f);

    lens::LensSpec high;
    high.flare.ghosts = 99;
    high.streaks.spokes = 99;
    high.godRays.x = 5.0f;
    high.godRays.y = 5.0f;
    high.depthOfField.focus = 1.0f;
    const lens::LensSpec h = lens::sanitized(high);
    EXPECT_EQ(h.flare.ghosts, lens::kMaxGhosts);
    EXPECT_EQ(h.streaks.spokes, lens::kMaxSpokes);
    EXPECT_FLOAT_EQ(h.godRays.x, 1.0f);
    EXPECT_FLOAT_EQ(h.godRays.y, 1.0f);
    EXPECT_FLOAT_EQ(h.depthOfField.focus, lens::kMaxFocus);

    lens::ParticleSpec small;
    small.count = 0.0f;
    small.speed = 9.0f;
    small.size = 0.0f;
    small.amount = 0.5f;
    const lens::ParticleSpec p = lens::sanitized(small);
    EXPECT_FLOAT_EQ(p.count, lens::kMinParticleCount);
    EXPECT_FLOAT_EQ(p.speed, lens::kMaxParticleSpeed);
    EXPECT_FLOAT_EQ(p.size, lens::kMinParticleSize);
    EXPECT_FLOAT_EQ(p.amount, 0.5f);
}

TEST(LensSpec, GlareIsAnyOfFlareRaysAndStreaks) {
    lens::LensSpec spec;
    EXPECT_FALSE(spec.glare());
    spec.depthOfField.aperture = 1.0f;
    EXPECT_FALSE(spec.glare());
    spec.flare.amount = 0.1f;
    EXPECT_TRUE(spec.glare());
    spec.flare.amount = 0.0f;
    spec.godRays.amount = 0.1f;
    EXPECT_TRUE(spec.glare());
    spec.godRays.amount = 0.0f;
    spec.streaks.amount = 0.1f;
    EXPECT_TRUE(spec.glare());
}

// ---- tiers ---------------------------------------------------------------------------------------

TEST(LensTiers, EachTierSetsTheSamplesAndTheHotOnesSwitchThemOff) {
    for (int tier = 0; tier < 4; ++tier) {
        EXPECT_EQ(lens::raySamples(tier, true), lens::kRaySamples[static_cast<size_t>(tier)]) << tier;
        EXPECT_EQ(lens::streakSamples(tier, true), lens::kStreakSamples[static_cast<size_t>(tier)]) << tier;
        EXPECT_EQ(lens::dofSamples(tier, true), lens::kDofSamples[static_cast<size_t>(tier)]) << tier;
        EXPECT_EQ(lens::raySamples(tier, false), 0) << tier;
        EXPECT_EQ(lens::streakSamples(tier, false), 0) << tier;
        EXPECT_EQ(lens::dofSamples(tier, false), 0) << tier;
    }
    EXPECT_EQ(lens::raySamples(0, true), 24);
    EXPECT_EQ(lens::raySamples(1, true), 16);
    EXPECT_EQ(lens::raySamples(2, true), 0);
    EXPECT_EQ(lens::streakSamples(1, true), 6);
    EXPECT_EQ(lens::dofSamples(1, true), 16);
    // A tier outside the four counts as the hottest.
    EXPECT_EQ(lens::raySamples(-1, true), lens::kRaySamples[3]);
    EXPECT_EQ(lens::dofSamples(4, true), lens::kDofSamples[3]);
    EXPECT_EQ(lens::streakSamples(7, true), lens::kStreakSamples[3]);
}

// ---- the bloom chain shared with the glare ------------------------------------------------------

TEST(LensChain, TheGlareLowersTheChainThresholdOnlyWhileOn) {
    lens::Glare off;
    off.threshold = 0.6f;
    EXPECT_FLOAT_EQ(lens::chainThreshold(1.0f, off), 1.0f);
    lens::Glare on = off;
    on.flare = 0.5f;
    on.raySamples = 16;
    ASSERT_TRUE(on.on());
    EXPECT_FLOAT_EQ(lens::chainThreshold(1.0f, on), 0.6f);
    EXPECT_FLOAT_EQ(lens::chainThreshold(0.55f, on), 0.55f);
    lens::Glare noSamples = on;
    noSamples.raySamples = 0;
    EXPECT_FALSE(noSamples.on());
    EXPECT_FLOAT_EQ(lens::chainThreshold(1.0f, noSamples), 1.0f);
}

TEST(LensChain, GlareIsOnWithAnyAmountAndSamples) {
    lens::Glare g;
    g.raySamples = 24;
    EXPECT_FALSE(g.on());
    g.rays = 0.2f;
    EXPECT_TRUE(g.on());
    g.rays = 0.0f;
    g.streaks = 0.2f;
    EXPECT_TRUE(g.on());
}

TEST(LensChain, TheBloomShareTakesTheBloomBackOutOfTheChain) {
    EXPECT_FLOAT_EQ(lens::bloomShare(2.0f, 2.0f), 1.0f);
    EXPECT_FLOAT_EQ(lens::bloomShare(2.0f, 0.5f), 0.25f);
    EXPECT_FLOAT_EQ(lens::bloomShare(1.0f, 3.0f), 1.0f);
    EXPECT_FLOAT_EQ(lens::bloomShare(1.0f, -1.0f), 0.0f);
    EXPECT_FLOAT_EQ(lens::bloomShare(0.0f, 0.0f), 0.0f);
    EXPECT_FLOAT_EQ(lens::bloomShare(lens::kShareEpsilon, lens::kShareEpsilon), 0.0f);
    EXPECT_FLOAT_EQ(lens::bloomShare(lens::kShareEpsilon * 2.0f, lens::kShareEpsilon), 0.5f);
    EXPECT_FLOAT_EQ(lens::bloomShare(kNan, 1.0f), 0.0f);
    EXPECT_FLOAT_EQ(lens::bloomShare(1.0f, kNan), 0.0f);
    EXPECT_FLOAT_EQ(lens::bloomShare(1.0f, std::numeric_limits<float>::infinity()), 0.0f);
}

// ---- depth of field ------------------------------------------------------------------------------

TEST(LensDepthOfField, TheCircleIsZeroInFocusAndFullAtHalfTheFocalDepth) {
    EXPECT_FLOAT_EQ(lens::circleOfConfusion(0.5f, 0.5f, 1.0f), 0.0f);
    EXPECT_NEAR(lens::circleOfConfusion(0.25f, 0.5f, 1.0f), 1.0f, kTolerance);
    EXPECT_NEAR(lens::circleOfConfusion(1.0f, 0.5f, 1.0f), 0.5f, kTolerance);
    EXPECT_NEAR(lens::circleOfConfusion(1.0f, 0.5f, 0.5f), 0.25f, kTolerance);
    EXPECT_NEAR(lens::circleOfConfusion(0.4f, 0.5f, 1.0f), 0.25f, kTolerance);
    // Nearer than half the focal depth is still the full radius, never more.
    EXPECT_FLOAT_EQ(lens::circleOfConfusion(0.1f, 0.5f, 1.0f), 1.0f);
    EXPECT_FLOAT_EQ(lens::circleOfConfusion(0.4f, 0.5f, 0.0f), 0.0f);
    EXPECT_FLOAT_EQ(lens::circleOfConfusion(0.4f, 0.5f, -1.0f), 0.0f);
    // An aperture past 1 is 1.
    EXPECT_NEAR(lens::circleOfConfusion(1.0f, 0.5f, 3.0f), 0.5f, kTolerance);
}

TEST(LensDepthOfField, DepthAndFocusAreHeldToTheirRanges) {
    // Not a number reads as far; past 1 is 1; below the nearest depth is the nearest depth.
    EXPECT_NEAR(lens::circleOfConfusion(kNan, 0.5f, 1.0f), 0.5f, kTolerance);
    EXPECT_NEAR(lens::circleOfConfusion(4.0f, 0.5f, 1.0f), 0.5f, kTolerance);
    EXPECT_FLOAT_EQ(lens::circleOfConfusion(0.0f, 0.04f, 0.05f),
                    lens::circleOfConfusion(lens::kMinDepth, 0.04f, 0.05f));
    EXPECT_NEAR(lens::circleOfConfusion(lens::kMinDepth, 0.05f, 0.05f), 0.05f * (1.0f / 0.02f - 1.0f / 0.05f) * 0.05f,
                kTolerance);
    // The focus: not a number is 0.5, and it is held to kMinFocus..kMaxFocus.
    EXPECT_FLOAT_EQ(lens::circleOfConfusion(0.5f, kNan, 1.0f), 0.0f);
    EXPECT_NEAR(lens::circleOfConfusion(1.0f, 1.0f, 1.0f), (1.0f / 0.95f - 1.0f) * 0.95f, kTolerance);
    EXPECT_NEAR(lens::circleOfConfusion(1.0f, 0.0f, 0.5f), 0.5f * (1.0f / 0.05f - 1.0f) * 0.05f, kTolerance);
    EXPECT_FLOAT_EQ(lens::circleOfConfusion(0.5f, 0.5f, kNan), 0.0f);
}

TEST(LensDepthOfField, DepthAlphaIsHalfAtTheSubject) {
    EXPECT_FLOAT_EQ(lens::depthAlpha(2.0f, 2.0f), 0.5f);
    EXPECT_FLOAT_EQ(lens::depthAlpha(6.0f, 2.0f), 0.75f);
    EXPECT_FLOAT_EQ(lens::depthAlpha(0.0f, 2.0f), 0.0f);
    EXPECT_FLOAT_EQ(lens::depthAlpha(-3.0f, 2.0f), 0.0f);
    EXPECT_FLOAT_EQ(lens::depthAlpha(1.0f, 0.0f), 1.0f);
    EXPECT_FLOAT_EQ(lens::depthAlpha(1.0f, -1.0f), 1.0f);
    EXPECT_FLOAT_EQ(lens::depthAlpha(kNan, 1.0f), 1.0f);
    EXPECT_FLOAT_EQ(lens::depthAlpha(1.0f, kNan), 1.0f);
    EXPECT_LT(lens::depthAlpha(1e6f, 1.0f), 1.0f);
}

TEST(LensDepthOfField, IsTheActiveStylesAsItsTierAllows) {
    lens::LensSpec spec;
    EXPECT_FALSE(lens::dofFor(spec, 0, true).on());
    EXPECT_EQ(lens::dofFor(spec, 0, true).samples, 0);
    spec.depthOfField = {0.6f, 0.3f};
    const lens::Dof cool = lens::dofFor(spec, 0, true);
    EXPECT_TRUE(cool.on());
    EXPECT_EQ(cool.samples, 24);
    EXPECT_FLOAT_EQ(cool.aperture, 0.6f);
    EXPECT_FLOAT_EQ(cool.focus, 0.3f);
    EXPECT_EQ(lens::dofFor(spec, 1, true).samples, 16);
    EXPECT_FALSE(lens::dofFor(spec, 2, true).on());
    EXPECT_FALSE(lens::dofFor(spec, 0, false).on());
    spec.depthOfField = {2.0f, 0.0f};
    const lens::Dof held = lens::dofFor(spec, 0, true);
    EXPECT_FLOAT_EQ(held.aperture, 1.0f);
    EXPECT_FLOAT_EQ(held.focus, lens::kMinFocus);
    spec.depthOfField = {kNan, kNan};
    const lens::Dof none = lens::dofFor(spec, 0, true);
    EXPECT_FALSE(none.on());
    EXPECT_FLOAT_EQ(none.focus, 0.5f);
    lens::Dof samplesOnly;
    samplesOnly.samples = 8;
    EXPECT_FALSE(samplesOnly.on());
}

// ---- gliding -------------------------------------------------------------------------------------

TEST(LensGlide, ApproachMovesByAtMostTheStep) {
    EXPECT_FLOAT_EQ(lens::approach(0.0f, 1.0f, 0.25f), 0.25f);
    EXPECT_FLOAT_EQ(lens::approach(1.0f, 0.0f, 0.25f), 0.75f);
    EXPECT_FLOAT_EQ(lens::approach(0.9f, 1.0f, 0.25f), 1.0f);
    EXPECT_FLOAT_EQ(lens::approach(0.1f, 0.0f, 0.25f), 0.0f);
    EXPECT_FLOAT_EQ(lens::approach(0.5f, 0.5f, 0.25f), 0.5f);
    EXPECT_FLOAT_EQ(lens::approach(0.3f, 1.0f, 0.0f), 0.3f);
    EXPECT_FLOAT_EQ(lens::approach(0.3f, 1.0f, -1.0f), 0.3f);
    EXPECT_FLOAT_EQ(lens::approach(0.3f, 1.0f, kNan), 0.3f);
    EXPECT_FLOAT_EQ(lens::approach(0.3f, 1.0f, std::numeric_limits<float>::infinity()), 0.3f);
}

TEST(LensGlide, GlideRisesAndFallsAtTheirOwnRates) {
    EXPECT_NEAR(lens::glide(0.0f, 1.0f, 0.5f, 2.0f, 0.5f), 1.0f - std::exp(-1.0f), kTolerance);
    EXPECT_NEAR(lens::glide(1.0f, 0.0f, 0.5f, 2.0f, 0.5f), std::exp(-0.25f), kTolerance);
    EXPECT_NEAR(lens::glide(1.0f, 0.0f, 0.5f, 2.0f, 2.0f), std::exp(-1.0f), kTolerance);
    // The same per second whatever the frame rate.
    const float once = lens::glide(0.2f, 0.9f, 0.3f, 1.0f, 0.1f);
    const float twice = lens::glide(lens::glide(0.2f, 0.9f, 0.3f, 1.0f, 0.05f), 0.9f, 0.3f, 1.0f, 0.05f);
    EXPECT_NEAR(once, twice, kTolerance);
    EXPECT_FLOAT_EQ(lens::glide(0.2f, 0.9f, 0.0f, 1.0f, 0.1f), 0.9f);
    EXPECT_FLOAT_EQ(lens::glide(0.9f, 0.2f, 0.5f, 0.0f, 0.1f), 0.2f);
    EXPECT_FLOAT_EQ(lens::glide(0.2f, 0.9f, 0.3f, 1.0f, 0.0f), 0.2f);
    EXPECT_FLOAT_EQ(lens::glide(0.2f, 0.9f, 0.3f, 1.0f, -0.1f), 0.2f);
    EXPECT_FLOAT_EQ(lens::glide(0.2f, 0.9f, 0.3f, 1.0f, kNan), 0.2f);
    EXPECT_FLOAT_EQ(lens::glide(0.2f, kNan, 0.3f, 1.0f, 0.1f), 0.2f);
    EXPECT_FLOAT_EQ(lens::glide(0.2f, 0.9f, 0.3f, 1.0f, std::numeric_limits<float>::infinity()), 0.2f);
}

// ---- the glare each frame ------------------------------------------------------------------------

lens::LensSpec glareSpec() {
    lens::LensSpec spec;
    spec.threshold = 0.7f;
    spec.flare = {1.0f, 6, 0.4f, 0.3f};
    spec.godRays = {0.5f, 0.2f, 0.8f, 0.9f, 0.85f};
    spec.streaks = {0.8f, lens::StreakShape::Zoom, 6, 0.5f};
    return spec;
}

TEST(LensDrive, FadesTheStylesAmountsIn) {
    lens::LensDrive drive;
    const lens::Glare first = drive.step(glareSpec(), {}, 0, true, false, 0.3f);
    // Half of kStyleFadeSeconds: each amount has moved up to half of the whole range, at the silent swing.
    EXPECT_NEAR(first.flare, 0.5f * lens::kGlareRest, kTolerance);
    EXPECT_NEAR(first.rays, 0.5f * lens::kGlareRest, kTolerance);
    EXPECT_NEAR(first.streaks, 0.5f * lens::kGlareRest, kTolerance);
    EXPECT_TRUE(first.on());
    const lens::Glare settled = drive.step(glareSpec(), {}, 0, true, false, 1.0f);
    EXPECT_NEAR(settled.flare, lens::kGlareRest, kTolerance);
    EXPECT_NEAR(settled.rays, 0.5f * lens::kGlareRest, kTolerance);
    EXPECT_NEAR(settled.streaks, 0.8f * lens::kGlareRest, kTolerance);
    EXPECT_FLOAT_EQ(settled.threshold, 0.7f);
    EXPECT_EQ(settled.ghosts, 6);
    EXPECT_FLOAT_EQ(settled.halo, 0.4f);
    EXPECT_FLOAT_EQ(settled.dispersion, 0.3f);
    EXPECT_FLOAT_EQ(settled.rayX, 0.2f);
    EXPECT_FLOAT_EQ(settled.rayY, 0.8f);
    EXPECT_FLOAT_EQ(settled.rayLength, 0.9f);
    EXPECT_FLOAT_EQ(settled.rayDecay, 0.85f);
    EXPECT_EQ(settled.streakShape, lens::StreakShape::Zoom);
    EXPECT_EQ(settled.spokes, 6);
    EXPECT_NEAR(settled.streakLength, 0.5f * lens::kGlareRest, kTolerance);
    EXPECT_EQ(settled.raySamples, 24);
    EXPECT_EQ(settled.streakSamples, 8);
}

TEST(LensDrive, TheMusicSwellsTheGlareNoFasterThanASwell) {
    lens::LensDrive drive;
    drive.step(glareSpec(), {}, 0, true, false, 1.0f);
    const lens::Drive loud{1.0f, 1.0f};
    const lens::Glare quick = drive.step(glareSpec(), loud, 0, true, false, 1.0f / 60.0f);
    const float risen = 1.0f - std::exp(-(1.0f / 60.0f) / lens::kSwellRiseSeconds);
    EXPECT_NEAR(drive.swell(), risen, kTolerance);
    EXPECT_NEAR(quick.flare, lens::kGlareRest + lens::kGlareSwing * risen, kTolerance);
    for (int i = 0; i < 600; ++i) drive.step(glareSpec(), loud, 0, true, false, 1.0f / 60.0f);
    const lens::Glare full = drive.step(glareSpec(), loud, 0, true, false, 1.0f / 60.0f);
    EXPECT_NEAR(full.flare, lens::kGlareRest + lens::kGlareSwing, 1e-4f);
    EXPECT_NEAR(full.streakLength, 0.5f * (lens::kGlareRest + lens::kGlareSwing), 1e-4f);
    // Back down at the slower rate.
    drive.step(glareSpec(), {}, 0, true, false, lens::kSwellFallSeconds);
    EXPECT_NEAR(drive.swell(), std::exp(-1.0f), 1e-4f);
    // A level outside 0..1 counts as its nearest end.
    lens::LensDrive over;
    over.step(glareSpec(), {5.0f, 0.0f}, 0, true, false, 100.0f);
    EXPECT_NEAR(over.swell(), 1.0f, kTolerance);
}

TEST(LensDrive, AHotTierFadesTheGlareOutAndSwitchesItOff) {
    lens::LensDrive drive;
    drive.step(glareSpec(), {}, 0, true, false, 1.0f);
    const lens::Glare half = drive.step(glareSpec(), {}, 2, true, false, 0.3f);
    EXPECT_EQ(half.raySamples, 0);
    EXPECT_FALSE(half.on());
    EXPECT_NEAR(half.flare, 0.5f * lens::kGlareRest, kTolerance);
    const lens::Glare gone = drive.step(glareSpec(), {}, 0, false, false, 1.0f);
    EXPECT_FLOAT_EQ(gone.flare, 0.0f);
    EXPECT_FLOAT_EQ(gone.rays, 0.0f);
    EXPECT_FLOAT_EQ(gone.streaks, 0.0f);
    EXPECT_FALSE(gone.on());
    // Cooled down again, it fades back in.
    const lens::Glare back = drive.step(glareSpec(), {}, 1, true, false, 0.3f);
    EXPECT_EQ(back.raySamples, 16);
    EXPECT_EQ(back.streakSamples, 6);
    EXPECT_NEAR(back.flare, 0.5f * lens::kGlareRest, kTolerance);
}

TEST(LensDrive, AStyleChangeFadesTheOldGlareOut) {
    lens::LensDrive drive;
    drive.step(glareSpec(), {}, 0, true, false, 1.0f);
    const lens::Glare fading = drive.step(lens::LensSpec{}, {}, 0, true, false, 0.15f);
    EXPECT_NEAR(fading.flare, 0.75f * lens::kGlareRest, kTolerance);
    EXPECT_TRUE(fading.on());
    const lens::Glare stalled = drive.step(lens::LensSpec{}, {}, 0, true, false, 0.0f);
    EXPECT_NEAR(stalled.flare, 0.75f * lens::kGlareRest, kTolerance);
    const lens::Glare nan = drive.step(lens::LensSpec{}, {}, 0, true, false, kNan);
    EXPECT_NEAR(nan.flare, 0.75f * lens::kGlareRest, kTolerance);
    drive.reset();
    EXPECT_FLOAT_EQ(drive.swell(), 0.0f);
    EXPECT_FALSE(drive.step(lens::LensSpec{}, {}, 0, true, false, 0.0f).on());
}

TEST(LensDrive, ReducedMotionShortensTheStreaksAndTheSpecIsHeld) {
    lens::LensDrive drive;
    lens::LensSpec spec = glareSpec();
    spec.threshold = 9.0f;
    spec.flare.ghosts = 40;
    const lens::Glare g = drive.step(spec, {}, 0, true, true, 1.0f);
    EXPECT_NEAR(g.streakLength, 0.5f * lens::kGlareRest * lens::kReducedMotionStreak, kTolerance);
    EXPECT_FLOAT_EQ(g.threshold, lens::kMaxThreshold);
    EXPECT_EQ(g.ghosts, lens::kMaxGhosts);
}

// ---- the particles -------------------------------------------------------------------------------

TEST(LensParticles, TheCountFollowsTheTierAndTheStyle) {
    EXPECT_EQ(lens::particleCount(1.0f, 0), 900);
    EXPECT_EQ(lens::particleCount(2.0f, 0), lens::kMaxParticles);
    EXPECT_EQ(lens::particleCount(1.0f, 1), 675);
    EXPECT_EQ(lens::particleCount(1.0f, 2), 450);
    EXPECT_EQ(lens::particleCount(1.0f, 3), 270);
    EXPECT_EQ(lens::particleCount(1.0f, -2), 270);
    EXPECT_EQ(lens::particleCount(1.0f, 4), 270);
    EXPECT_EQ(lens::particleCount(0.0f, 0), 225);
    EXPECT_EQ(lens::particleCount(9.0f, 0), lens::kMaxParticles);
    EXPECT_EQ(lens::particleCount(kNan, 0), 900);
    EXPECT_EQ(lens::particleCount(0.5f, 0), 450);
}

lens::ParticleSpec particleSpec() {
    lens::ParticleSpec spec;
    spec.amount = 1.0f;
    spec.count = 1.0f;
    spec.speed = 2.0f;
    spec.size = 1.5f;
    spec.tint = {1.0f, 0.5f, 0.25f};
    return spec;
}

TEST(LensParticles, FlyAtAnIntegratedSpeedThatTheMusicRaises) {
    lens::ParticleFlight flight;
    const lens::Particles first = flight.step(particleSpec(), {}, 0, false, 0.3f);
    const float silentSpeed = 2.0f * lens::kFlightSpeed * lens::kSpeedRest;
    EXPECT_NEAR(flight.speed(), silentSpeed, kTolerance);
    EXPECT_NEAR(first.phase, silentSpeed * 0.3f, kTolerance);
    EXPECT_NEAR(first.tail, silentSpeed * lens::kStreakShutter, kTolerance);
    EXPECT_EQ(first.count, 900);
    EXPECT_NEAR(first.emission, 0.5f * lens::kEmissionRest, kTolerance);
    EXPECT_FLOAT_EQ(first.size, 1.5f);
    EXPECT_FLOAT_EQ(first.tint[1], 0.5f);
    EXPECT_EQ(first.kind, lens::ParticleKind::Streaks);
    EXPECT_TRUE(first.on());
    // Loud: the swell rises, and with it the speed and the emission.
    for (int i = 0; i < 600; ++i) flight.step(particleSpec(), {1.0f, 0.0f}, 0, false, 1.0f / 60.0f);
    const lens::Particles loud = flight.step(particleSpec(), {1.0f, 0.0f}, 0, false, 1.0f / 60.0f);
    EXPECT_NEAR(flight.speed(), 2.0f * lens::kFlightSpeed * (lens::kSpeedRest + lens::kSpeedSwing), 1e-3f);
    EXPECT_NEAR(loud.emission, 1.0f, 1e-4f);
    const float before = flight.phase();
    flight.step(particleSpec(), {1.0f, 0.0f}, 0, false, 0.01f);
    EXPECT_NEAR(flight.phase(), std::fmod(before + flight.speed() * 0.01f, lens::kPhaseWrap), 1e-3f);
}

TEST(LensParticles, AHitLiftsTheSwellByItsShareAndRisesNoFasterThanASwell) {
    lens::ParticleFlight flight;
    flight.step(particleSpec(), {0.0f, 1.0f}, 0, false, 1.0f / 60.0f);
    const float risen = lens::kHitShare * (1.0f - std::exp(-(1.0f / 60.0f) / lens::kParticleRiseSeconds));
    EXPECT_NEAR(flight.speed(), 2.0f * lens::kFlightSpeed * (lens::kSpeedRest + lens::kSpeedSwing * risen), 1e-4f);
    lens::ParticleFlight settled;
    settled.step(particleSpec(), {0.2f, 1.0f}, 0, false, 100.0f);
    EXPECT_NEAR(settled.speed(), 2.0f * lens::kFlightSpeed * (lens::kSpeedRest + lens::kSpeedSwing * lens::kHitShare),
                1e-4f);
    lens::ParticleFlight level;
    level.step(particleSpec(), {0.9f, 1.0f}, 0, false, 100.0f);
    EXPECT_NEAR(level.speed(), 2.0f * lens::kFlightSpeed * (lens::kSpeedRest + lens::kSpeedSwing * 0.9f), 1e-4f);
    // Back down at the slower rate.
    level.step(particleSpec(), {}, 0, false, lens::kParticleFallSeconds);
    EXPECT_NEAR(level.speed(),
                2.0f * lens::kFlightSpeed * (lens::kSpeedRest + lens::kSpeedSwing * 0.9f * std::exp(-1.0f)), 1e-3f);
}

TEST(LensParticles, ThePhaseWrapsAtWholeSpansAndHoldsOnAStall) {
    lens::ParticleFlight flight;
    lens::ParticleSpec fast = particleSpec();
    fast.speed = lens::kMaxParticleSpeed;
    const float step = 50.0f;
    float expected = 0.0f;
    for (int i = 0; i < 20; ++i) {
        flight.step(fast, {}, 0, false, step);
        expected = std::fmod(expected + flight.speed() * step, lens::kPhaseWrap);
    }
    EXPECT_NEAR(flight.phase(), expected, 1e-2f);
    EXPECT_LT(flight.phase(), lens::kPhaseWrap);
    EXPECT_FLOAT_EQ(std::fmod(lens::kPhaseWrap, lens::kDepthSpan), 0.0f);
    const float held = flight.phase();
    flight.step(fast, {}, 0, false, 0.0f);
    EXPECT_FLOAT_EQ(flight.phase(), held);
    flight.step(fast, {}, 0, false, kNan);
    EXPECT_FLOAT_EQ(flight.phase(), held);
}

TEST(LensParticles, FadeInAndOutWithTheStyleAndFollowTheTier) {
    lens::ParticleFlight flight;
    lens::ParticleSpec none;
    const lens::Particles off = flight.step(none, {}, 0, false, 0.1f);
    EXPECT_EQ(off.count, 0);
    EXPECT_FALSE(off.on());
    flight.step(particleSpec(), {}, 3, false, 1.0f);
    const lens::Particles hot = flight.step(particleSpec(), {}, 3, false, 0.0f);
    EXPECT_EQ(hot.count, 270);
    EXPECT_NEAR(hot.emission, lens::kEmissionRest, kTolerance);
    const lens::Particles fading = flight.step(none, {}, 0, false, 0.3f);
    EXPECT_EQ(fading.count, lens::kBaseParticles);
    EXPECT_NEAR(fading.emission, 0.5f * lens::kEmissionRest, kTolerance);
    const lens::Particles gone = flight.step(none, {}, 0, false, 0.3f);
    EXPECT_EQ(gone.count, 0);
    EXPECT_FLOAT_EQ(gone.emission, 0.0f);
    lens::Particles countOnly;
    countOnly.count = 10;
    EXPECT_FALSE(countOnly.on());
}

TEST(LensParticles, SparksHaveShortTailsAndReducedMotionSlowsTheFlight) {
    lens::ParticleFlight flight;
    lens::ParticleSpec sparks = particleSpec();
    sparks.kind = lens::ParticleKind::Sparks;
    const lens::Particles p = flight.step(sparks, {}, 0, true, 0.1f);
    const float speed = 2.0f * lens::kFlightSpeed * lens::kSpeedRest * lens::kReducedMotionSpeed;
    EXPECT_NEAR(flight.speed(), speed, kTolerance);
    EXPECT_NEAR(p.tail, speed * lens::kSparkShutter, kTolerance);
    EXPECT_EQ(p.kind, lens::ParticleKind::Sparks);
    flight.reset();
    EXPECT_FLOAT_EQ(flight.phase(), 0.0f);
    EXPECT_FLOAT_EQ(flight.speed(), 0.0f);
    const lens::Particles fresh = flight.step(sparks, {}, 0, false, 0.0f);
    EXPECT_EQ(fresh.count, 0);
}

TEST(LensParticles, TheSpecIsHeldToItsRanges) {
    lens::ParticleFlight flight;
    lens::ParticleSpec wild = particleSpec();
    wild.speed = 100.0f;
    wild.size = 100.0f;
    wild.tint = {9.0f, -1.0f, 1.0f};
    wild.count = 100.0f;
    const lens::Particles p = flight.step(wild, {}, 0, false, 1.0f);
    EXPECT_NEAR(flight.speed(), lens::kMaxParticleSpeed * lens::kFlightSpeed * lens::kSpeedRest, kTolerance);
    EXPECT_FLOAT_EQ(p.size, lens::kMaxParticleSize);
    EXPECT_FLOAT_EQ(p.tint[0], lens::kMaxTint);
    EXPECT_FLOAT_EQ(p.tint[1], 0.0f);
    EXPECT_EQ(p.count, lens::kMaxParticles);
}

}  // namespace
}  // namespace geode::viz
