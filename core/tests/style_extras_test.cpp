#include "geode/viz/StyleExtras.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>

namespace geode::viz {
namespace {

using mod::Extra;

constexpr float kTolerance = 1e-5f;

// The packs as they ship (GEODE_STYLE_PACK_ROOT/styles/...), read the way the APK reader reads them.
std::optional<std::string> readShipped(const std::string& path) {
    std::ifstream in(std::string(GEODE_STYLE_PACK_ROOT) + "/" + path, std::ios::binary);
    if (!in) return std::nullopt;
    std::ostringstream text;
    text << in.rdbuf();
    return text.str();
}

ExtraValues with(Extra extra, float value) {
    ExtraValues x = ExtraValues::neutral();
    x.value[static_cast<size_t>(extra)] = value;
    x.declared[static_cast<size_t>(extra)] = true;
    return x;
}

packs::StylePack packWith(std::vector<packs::ExtraSpec> extras) {
    packs::StylePack pack;
    pack.id = "plate";
    pack.family = "shader";
    pack.extras = std::move(extras);
    return pack;
}

// ---- the values a renderer reads ----------------------------------------------------------------

TEST(ExtraValues, NeutralDrawsTheStyleAsTuned) {
    const ExtraValues x = ExtraValues::neutral();
    for (size_t e = 0; e < mod::kExtraCount; ++e) {
        const auto extra = static_cast<Extra>(e);
        const bool offset = extra == Extra::Variant || extra == Extra::CenterX || extra == Extra::CenterY;
        EXPECT_EQ(x[extra], offset ? 0.0f : 1.0f) << e;
        EXPECT_EQ(ExtraValues::neutralOf(extra), x[extra]) << e;
        EXPECT_FALSE(x.has(extra)) << e;
    }
}

TEST(ExtraValues, AMultiplierKeepsOffItsFloorAndReadsNotANumberAsNeutral) {
    EXPECT_EQ(with(Extra::Scale, 0.01f).multiplier(Extra::Scale), 0.05f);
    EXPECT_EQ(with(Extra::Scale, 1.5f).multiplier(Extra::Scale), 1.5f);
    EXPECT_EQ(with(Extra::Scale, 0.0f).multiplier(Extra::Scale, 0.0f), 0.0f);
    EXPECT_EQ(with(Extra::Scale, 0.3f).multiplier(Extra::Scale, 0.5f), 0.5f);
    EXPECT_EQ(with(Extra::Scale, std::numeric_limits<float>::quiet_NaN()).multiplier(Extra::Scale), 1.0f);
    EXPECT_EQ(with(Extra::Scale, std::numeric_limits<float>::infinity()).multiplier(Extra::Scale), 1.0f);
}

// ---- from the pack to the values ----------------------------------------------------------------

TEST(StyleExtras, APackDeclaresItsRangesAndTheVariantStepsWhole) {
    mod::FamilyRanges ranges = mod::FamilyRanges::sharedCore();
    extras::declare(ranges, packWith({{"variant", 0.0f, 9.0f, 0.0f}, {"scale", 0.0f, 2.0f, 1.0f}, {"density", 0.0f, 5.0f, 1.0f}}));
    const mod::NativeRange& variant = ranges.extras[static_cast<size_t>(Extra::Variant)];
    EXPECT_EQ(variant.min, 0.0f);
    EXPECT_EQ(variant.max, 9.0f);
    EXPECT_TRUE(variant.stepped);
    EXPECT_EQ(variant.curve, mod::RangeCurve::Linear);
    const mod::NativeRange& scale = ranges.extras[static_cast<size_t>(Extra::Scale)];
    EXPECT_EQ(scale.max, 2.0f);
    EXPECT_FALSE(scale.stepped);
    const mod::NativeRange& count = ranges.extras[static_cast<size_t>(Extra::Count)];
    EXPECT_EQ(count.min, 0.0f);
    EXPECT_EQ(count.max, 1.0f) << "undeclared: left at the shared 0..1";
}

TEST(StyleExtras, AStyleGetsItsDeclaredExtrasAndNeutralForTheRest) {
    EXPECT_FALSE(extras::of(nullptr, nullptr).has(Extra::Scale));
    EXPECT_EQ(extras::of(nullptr, nullptr)[Extra::Scale], 1.0f);

    const packs::StylePack pack = packWith({{"scale", 0.0f, 2.0f, 1.25f}, {"center_x", -1.0f, 1.0f, 0.5f}, {"density", 0.0f, 5.0f, 3.0f}});
    const ExtraValues defaults = extras::of(&pack, nullptr);
    EXPECT_TRUE(defaults.has(Extra::Scale));
    EXPECT_EQ(defaults[Extra::Scale], 1.25f);
    EXPECT_TRUE(defaults.has(Extra::CenterX));
    EXPECT_EQ(defaults[Extra::CenterX], 0.5f);
    EXPECT_FALSE(defaults.has(Extra::Count));
    EXPECT_EQ(defaults[Extra::Count], 1.0f);

    mod::NativeFrame frame;
    frame.extras[static_cast<size_t>(Extra::Scale)] = 1.75f;
    frame.extras[static_cast<size_t>(Extra::CenterX)] = -0.25f;
    frame.extras[static_cast<size_t>(Extra::Count)] = 1.9f;
    const ExtraValues live = extras::of(&pack, &frame);
    EXPECT_EQ(live[Extra::Scale], 1.75f);
    EXPECT_EQ(live[Extra::CenterX], -0.25f);
    EXPECT_EQ(live[Extra::Count], 1.0f) << "not declared: the frame's value is not read";
    EXPECT_FALSE(live.has(Extra::Count));
}

// ---- the mappings onto a family's parameters ----------------------------------------------------

TEST(StyleExtras, AMultiplierScalesAtItsDepthAndKeepsTheSign) {
    EXPECT_FLOAT_EQ(extras::scaled(2.0f, 1.5f), 3.0f);
    EXPECT_FLOAT_EQ(extras::scaled(2.0f, 1.0f), 2.0f);
    EXPECT_FLOAT_EQ(extras::scaled(2.0f, 1.5f, 0.5f), 2.5f);
    EXPECT_FLOAT_EQ(extras::scaled(2.0f, 0.0f, 0.5f), 1.0f);
    EXPECT_FLOAT_EQ(extras::scaled(2.0f, -1.0f), 0.0f);
    EXPECT_FLOAT_EQ(extras::scaled(2.0f, 0.0f, 2.0f), 0.0f);
    EXPECT_FLOAT_EQ(extras::scaled(-0.5f, 2.0f), -1.0f);
}

TEST(StyleExtras, ANudgeMovesAParameterThePackMayLeaveAtZero) {
    EXPECT_FLOAT_EQ(extras::nudged(0.0f, 2.0f, 0.5f, 0.0f, 1.0f), 0.5f);
    EXPECT_FLOAT_EQ(extras::nudged(0.3f, 1.0f, 0.5f, 0.0f, 1.0f), 0.3f);
    EXPECT_FLOAT_EQ(extras::nudged(0.3f, 1.4f, 0.5f, 0.0f, 1.0f), 0.5f);
    EXPECT_FLOAT_EQ(extras::nudged(0.0f, 0.0f, 0.5f, 0.0f, 1.0f), 0.0f);
    EXPECT_FLOAT_EQ(extras::nudged(0.8f, 2.0f, 0.5f, 0.0f, 1.0f), 1.0f);
    EXPECT_FLOAT_EQ(extras::nudged(0.8f, 2.0f, 0.5f, 0.0f, 2.0f), 1.3f);
}

TEST(StyleExtras, PersistenceDividesTheLossAndDampingMultipliesIt) {
    EXPECT_NEAR(extras::persisting(0.9f, 2.0f), 0.95f, kTolerance);
    EXPECT_NEAR(extras::persisting(0.9f, 0.5f), 0.8f, kTolerance);
    EXPECT_NEAR(extras::persisting(0.9f, 1.0f), 0.9f, kTolerance);
    EXPECT_EQ(extras::persisting(0.9f, 0.0f), 0.0f) << "the loss over the floor, kept to 0";
    EXPECT_NEAR(extras::persisting(0.99f, 0.0f), 0.8f, kTolerance) << "the Extra is floored at 0.05";
    EXPECT_EQ(extras::persisting(1.2f, 1.0f), 1.0f);

    EXPECT_NEAR(extras::damped(0.985f, 2.0f), 0.97f, kTolerance);
    EXPECT_NEAR(extras::damped(0.985f, 1.0f), 0.985f, kTolerance);
    EXPECT_EQ(extras::damped(0.985f, 0.0f), 1.0f);
    EXPECT_EQ(extras::damped(0.985f, -1.0f), 1.0f);
    EXPECT_EQ(extras::damped(0.5f, 3.0f), 0.0f);

    EXPECT_FLOAT_EQ(extras::dissipating(1.0f, 2.0f), 0.5f);
    EXPECT_FLOAT_EQ(extras::dissipating(1.0f, 1.0f), 1.0f);
    EXPECT_FLOAT_EQ(extras::dissipating(1.0f, 0.0f), 20.0f);
    EXPECT_FLOAT_EQ(extras::dissipating(-1.0f, 1.0f), 0.0f);
}

TEST(StyleExtras, TheVariantWrapsAndACountStaysInRange) {
    EXPECT_EQ(extras::variant(3, 0.0f, 10), 3);
    EXPECT_EQ(extras::variant(3, 2.4f, 10), 5);
    EXPECT_EQ(extras::variant(8, 3.0f, 10), 1);
    EXPECT_EQ(extras::variant(1, -2.0f, 10), 9);
    EXPECT_EQ(extras::variant(2, 0.6f, 4), 3);
    EXPECT_EQ(extras::variant(5, 4.0f, 0), 0);

    EXPECT_EQ(extras::count(5, 1.0f, 8), 5);
    EXPECT_EQ(extras::count(5, 2.0f, 8), 8);
    EXPECT_EQ(extras::count(5, 1.4f, 8), 7);
    EXPECT_EQ(extras::count(5, 0.5f, 8), 3);
    EXPECT_EQ(extras::count(5, 0.0f, 8), 1);
    EXPECT_EQ(extras::count(5, -1.0f, 8), 1);
    EXPECT_EQ(extras::count(5, 2.0f, 0), 1);
}

TEST(StyleExtras, LifeGrowsAndSpreadsByItsOwnRuleAndChangesLook) {
    styles::LifeStyle lenia;
    lenia.rule = 0;
    lenia.mu = 0.16f;
    lenia.sigma = 0.02f;
    lenia.feed = 0.03f;
    lenia.kill = 0.06f;
    lenia.look = 2;
    styles::LifeStyle grown = extras::apply(lenia, with(Extra::Growth, 2.0f));
    EXPECT_FLOAT_EQ(grown.mu, 0.2f);
    EXPECT_FLOAT_EQ(grown.sigma, 0.02f);
    EXPECT_FLOAT_EQ(grown.feed, 0.03f) << "Gray-Scott's rates are not Lenia's";
    styles::LifeStyle narrow = extras::apply(lenia, with(Extra::Spread, 0.0f));
    EXPECT_FLOAT_EQ(narrow.sigma, 0.017f);
    EXPECT_FLOAT_EQ(narrow.mu, 0.16f);
    EXPECT_FLOAT_EQ(narrow.kill, 0.06f);
    EXPECT_EQ(extras::apply(lenia, with(Extra::Variant, 5.0f)).look, 1);
    EXPECT_EQ(extras::apply(lenia, ExtraValues::neutral()).look, 2);

    styles::LifeStyle grayScott = lenia;
    grayScott.rule = 1;
    styles::LifeStyle fed = extras::apply(grayScott, with(Extra::Growth, 2.0f));
    EXPECT_FLOAT_EQ(fed.feed, 0.0375f);
    EXPECT_FLOAT_EQ(fed.mu, 0.16f);
    styles::LifeStyle killed = extras::apply(grayScott, with(Extra::Spread, 2.0f));
    EXPECT_FLOAT_EQ(killed.kill, 0.069f);
    EXPECT_FLOAT_EQ(killed.sigma, 0.02f);
}

TEST(StyleExtras, MyceliumAgentsReachTurnMoveAndMarkWithinTheirRanges) {
    styles::MycoStyle myco;
    myco.sensorDist = 9.0f;
    myco.turnAngle = 0.6f;
    myco.moveStep = 1.0f;
    myco.decay = 0.9f;
    myco.deposit = 0.12f;
    EXPECT_FLOAT_EQ(extras::apply(myco, with(Extra::Reach, 2.0f)).sensorDist, 18.0f);
    EXPECT_FLOAT_EQ(extras::apply(myco, with(Extra::Reach, 4.0f)).sensorDist, 32.0f);
    EXPECT_FLOAT_EQ(extras::apply(myco, with(Extra::Turn, 2.0f)).turnAngle, 1.2f);
    EXPECT_FLOAT_EQ(extras::apply(myco, with(Extra::Turn, 3.0f)).turnAngle, 1.5708f);
    EXPECT_FLOAT_EQ(extras::apply(myco, with(Extra::Drift, 1.5f)).moveStep, 1.5f);
    EXPECT_FLOAT_EQ(extras::apply(myco, with(Extra::Drift, 3.0f)).moveStep, 2.0f);
    EXPECT_NEAR(extras::apply(myco, with(Extra::Decay, 2.0f)).decay, 0.95f, kTolerance);
    EXPECT_FLOAT_EQ(extras::apply(myco, with(Extra::Decay, 100.0f)).decay, 0.99f);
    EXPECT_FLOAT_EQ(extras::apply(myco, with(Extra::Force, 2.0f)).deposit, 0.24f);
    EXPECT_FLOAT_EQ(extras::apply(myco, with(Extra::Force, 5.0f)).deposit, 0.5f);
    const styles::MycoStyle same = extras::apply(myco, ExtraValues::neutral());
    EXPECT_FLOAT_EQ(same.sensorDist, 9.0f);
    EXPECT_NEAR(same.decay, 0.9f, kTolerance);
}

TEST(StyleExtras, AcidDivesTurnsFeedsBackAndSwitchesModeWithinBounds) {
    styles::AcidStyle acid;
    acid.mode = 7;
    acid.zoom = 1.02f;
    acid.rotate = -0.002f;
    acid.feedback = 0.95f;
    acid.hueRate = 0.04f;
    acid.liquid = 0.3f;
    acid.modulate = 0.35f;
    EXPECT_EQ(extras::apply(acid, with(Extra::Variant, 4.0f)).mode, 1);
    EXPECT_NEAR(extras::apply(acid, with(Extra::Flight, 2.0f)).zoom, 1.04f, kTolerance);
    EXPECT_NEAR(extras::apply(acid, with(Extra::Flight, 4.0f)).zoom, 1.05f, kTolerance);
    EXPECT_NEAR(extras::apply(acid, with(Extra::Flight, 0.0f)).zoom, 1.0f, kTolerance);
    styles::AcidStyle shrinking = acid;
    shrinking.zoom = 0.98f;
    EXPECT_NEAR(extras::apply(shrinking, with(Extra::Flight, 4.0f)).zoom, 0.95f, kTolerance);
    EXPECT_FLOAT_EQ(extras::apply(acid, with(Extra::Spin, 2.0f)).rotate, -0.004f);
    EXPECT_NEAR(extras::apply(acid, with(Extra::Decay, 2.0f)).feedback, 0.975f, kTolerance);
    EXPECT_FLOAT_EQ(extras::apply(acid, with(Extra::Decay, 0.05f)).feedback, 0.8f);
    EXPECT_FLOAT_EQ(extras::apply(acid, with(Extra::Decay, 100.0f)).feedback, 0.99f);
    EXPECT_FLOAT_EQ(extras::apply(acid, with(Extra::Drift, 2.0f)).hueRate, 0.08f);
    EXPECT_FLOAT_EQ(extras::apply(acid, with(Extra::Viscosity, 2.0f)).liquid, 0.8f);
    EXPECT_FLOAT_EQ(extras::apply(acid, with(Extra::Viscosity, 0.0f)).liquid, 0.0f);
    EXPECT_FLOAT_EQ(extras::apply(acid, with(Extra::Swirl, 2.0f)).modulate, 0.7f);
    EXPECT_FLOAT_EQ(extras::apply(acid, with(Extra::Swirl, 4.0f)).modulate, 1.0f);
    const styles::AcidStyle same = extras::apply(acid, ExtraValues::neutral());
    EXPECT_EQ(same.mode, 7);
    EXPECT_NEAR(same.zoom, 1.02f, kTolerance);
    EXPECT_NEAR(same.feedback, 0.95f, kTolerance);
    EXPECT_FLOAT_EQ(same.liquid, 0.3f);
}

TEST(StyleExtras, FluidCurlsThickensFadesAndSplats) {
    styles::FluidStyle fluid;
    fluid.look = 3;
    fluid.curl = 1.3f;
    fluid.velocityDissipation = 0.7f;
    fluid.densityDissipation = 0.6f;
    fluid.splatRadius = 1.4f;
    fluid.splatForce = 1.0f;
    fluid.bloom = 1.2f;
    EXPECT_FLOAT_EQ(extras::apply(fluid, with(Extra::Swirl, 2.0f)).curl, 2.6f);
    EXPECT_FLOAT_EQ(extras::apply(fluid, with(Extra::Viscosity, 2.0f)).velocityDissipation, 1.4f);
    EXPECT_FLOAT_EQ(extras::apply(fluid, with(Extra::Decay, 2.0f)).densityDissipation, 0.3f);
    EXPECT_FLOAT_EQ(extras::apply(fluid, with(Extra::Size, 0.5f)).splatRadius, 0.7f);
    EXPECT_FLOAT_EQ(extras::apply(fluid, with(Extra::Force, 2.0f)).splatForce, 2.0f);
    const styles::FluidStyle same = extras::apply(fluid, with(Extra::Swirl, 2.0f));
    EXPECT_EQ(same.look, 3);
    EXPECT_FLOAT_EQ(same.bloom, 1.2f);
    EXPECT_FLOAT_EQ(same.velocityDissipation, 0.7f);
}

TEST(StyleExtras, CymaticsScalesItsPlatesLinesFillSwirlAndFlow) {
    styles::CymaticsStyle plate;
    plate.scale = 0.9f;
    plate.line = 0.8f;
    plate.fill = 1.1f;
    plate.swirl = 1.0f;
    plate.flow = 0.5f;
    plate.modeCap = 6;
    plate.shaderStyle = 4;
    EXPECT_FLOAT_EQ(extras::apply(plate, with(Extra::Scale, 2.0f)).scale, 1.8f);
    EXPECT_FLOAT_EQ(extras::apply(plate, with(Extra::Thickness, 2.0f)).line, 1.6f);
    EXPECT_FLOAT_EQ(extras::apply(plate, with(Extra::Size, 0.5f)).fill, 0.55f);
    EXPECT_FLOAT_EQ(extras::apply(plate, with(Extra::Swirl, 0.0f)).swirl, 0.0f);
    EXPECT_FLOAT_EQ(extras::apply(plate, with(Extra::Drift, 2.0f)).flow, 1.0f);
    const styles::CymaticsStyle same = extras::apply(plate, with(Extra::Scale, 2.0f));
    EXPECT_EQ(same.modeCap, 6);
    EXPECT_EQ(same.shaderStyle, 4);
    EXPECT_FLOAT_EQ(same.line, 0.8f);
}

// ---- the packs that ship ------------------------------------------------------------------------

TEST(ShippedStyleExtras, EveryDeclaredExtraRoundTripsFromItsManifestToTheScene) {
    const packs::StylePackRegistry registry = packs::StylePackRegistry::load(readShipped);
    ASSERT_TRUE(registry.problems().empty());
    size_t declared = 0;
    for (const packs::StylePack& pack : registry.styles()) {
        mod::FamilyRanges ranges = mod::FamilyRanges::sharedCore();
        extras::declare(ranges, pack);
        const ExtraValues defaults = extras::of(&pack, nullptr);
        std::array<bool, mod::kExtraCount> named{};
        for (const packs::ExtraSpec& extra : pack.extras) {
            const int at = mod::extraNamed(extra.name);
            ASSERT_GE(at, 0) << pack.id << " declares \"" << extra.name << "\", which no Extra is";
            const auto e = static_cast<size_t>(at);
            named[e] = true;
            ++declared;
            EXPECT_TRUE(defaults.declared[e]) << pack.id << " " << extra.name;
            EXPECT_EQ(defaults.value[e], extra.defaultValue) << pack.id << " " << extra.name;
            EXPECT_EQ(extra.defaultValue, ExtraValues::neutralOf(static_cast<Extra>(e)))
                << pack.id << " " << extra.name << ": until Customized, a Style draws as its pack tunes it";
            for (const float fraction : {0.0f, 0.25f, 0.5f, 1.0f}) {
                mod::Evaluated evaluated;
                evaluated.extras[e] = fraction;
                const mod::NativeFrame frame = mod::toNative(ranges, evaluated);
                const ExtraValues live = extras::of(&pack, &frame);
                EXPECT_FLOAT_EQ(live.value[e], mod::toNative(ranges.extras[e], fraction)) << pack.id << " " << extra.name;
                EXPECT_GE(live.value[e], extra.min) << pack.id << " " << extra.name;
                EXPECT_LE(live.value[e], extra.max) << pack.id << " " << extra.name;
            }
        }
        for (size_t e = 0; e < mod::kExtraCount; ++e) {
            if (named[e]) continue;
            EXPECT_FALSE(defaults.declared[e]) << pack.id << " " << e;
            EXPECT_EQ(defaults.value[e], ExtraValues::neutralOf(static_cast<Extra>(e))) << pack.id << " " << e;
        }
    }
    EXPECT_GT(declared, 0u);
}

TEST(ShippedStyleExtras, AtTheirDefaultsTheExtrasLeaveEveryFamilyProfileAsTheyFoundIt) {
    const packs::StylePackRegistry registry = packs::StylePackRegistry::load(readShipped);
    for (const packs::StylePack& pack : registry.styles()) {
        const ExtraValues x = extras::of(&pack, nullptr);
        if (pack.family == styles::family::kLife) {
            const styles::LifeStyle base = styles::lifeProfile(pack);
            const styles::LifeStyle moved = extras::apply(base, x);
            EXPECT_FLOAT_EQ(moved.mu, base.mu) << pack.id;
            EXPECT_FLOAT_EQ(moved.sigma, base.sigma) << pack.id;
            EXPECT_FLOAT_EQ(moved.feed, base.feed) << pack.id;
            EXPECT_FLOAT_EQ(moved.kill, base.kill) << pack.id;
            EXPECT_EQ(moved.look, base.look) << pack.id;
        } else if (pack.family == styles::family::kMycelium) {
            const styles::MycoStyle base = styles::mycoProfile(pack);
            const styles::MycoStyle moved = extras::apply(base, x);
            EXPECT_FLOAT_EQ(moved.sensorDist, base.sensorDist) << pack.id;
            EXPECT_FLOAT_EQ(moved.turnAngle, base.turnAngle) << pack.id;
            EXPECT_FLOAT_EQ(moved.moveStep, base.moveStep) << pack.id;
            EXPECT_NEAR(moved.decay, base.decay, kTolerance) << pack.id;
            EXPECT_FLOAT_EQ(moved.deposit, base.deposit) << pack.id;
        } else if (pack.family == styles::family::kAcid) {
            const styles::AcidStyle base = styles::acidProfile(pack);
            const styles::AcidStyle moved = extras::apply(base, x);
            EXPECT_EQ(moved.mode, base.mode) << pack.id;
            EXPECT_NEAR(moved.zoom, base.zoom, kTolerance) << pack.id;
            EXPECT_FLOAT_EQ(moved.rotate, base.rotate) << pack.id;
            EXPECT_NEAR(moved.feedback, base.feedback, kTolerance) << pack.id;
            EXPECT_FLOAT_EQ(moved.hueRate, base.hueRate) << pack.id;
            EXPECT_FLOAT_EQ(moved.liquid, base.liquid) << pack.id;
            EXPECT_FLOAT_EQ(moved.modulate, base.modulate) << pack.id;
        } else if (pack.family == styles::family::kFluid) {
            const styles::FluidStyle base = styles::fluidProfile(pack);
            const styles::FluidStyle moved = extras::apply(base, x);
            EXPECT_FLOAT_EQ(moved.curl, base.curl) << pack.id;
            EXPECT_FLOAT_EQ(moved.velocityDissipation, base.velocityDissipation) << pack.id;
            EXPECT_FLOAT_EQ(moved.densityDissipation, base.densityDissipation) << pack.id;
            EXPECT_FLOAT_EQ(moved.splatRadius, base.splatRadius) << pack.id;
            EXPECT_FLOAT_EQ(moved.splatForce, base.splatForce) << pack.id;
        } else if (pack.family == styles::family::kCymatics) {
            const styles::CymaticsStyle base = styles::cymaticsProfile(pack);
            const styles::CymaticsStyle moved = extras::apply(base, x);
            EXPECT_FLOAT_EQ(moved.scale, base.scale) << pack.id;
            EXPECT_FLOAT_EQ(moved.line, base.line) << pack.id;
            EXPECT_FLOAT_EQ(moved.fill, base.fill) << pack.id;
            EXPECT_FLOAT_EQ(moved.swirl, base.swirl) << pack.id;
            EXPECT_FLOAT_EQ(moved.flow, base.flow) << pack.id;
        }
    }
}

}  // namespace
}  // namespace geode::viz
